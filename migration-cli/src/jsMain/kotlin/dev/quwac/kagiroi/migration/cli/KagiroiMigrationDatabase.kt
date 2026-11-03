@file:Suppress("TooGenericExceptionCaught")

package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.migration.MigrationHistoryDialect
import dev.quwac.kagiroi.migration.MigrationHistoryStatements
import dev.quwac.kagiroi.sql.DedicatedSqlSession
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.dedicatedSession
import kotlinx.coroutines.flow.toList

/** Bridges a Kagiroi driver session to the deliberately small migration execution surface. */
internal class KagiroiMigrationDatabase(
    private val database: SqlDatabase,
    private val historySql: MigrationHistorySql,
    private val lock: MigrationLock,
) : MigrationDatabase {
    private var lockedSession: DedicatedSqlSession? = null

    override suspend fun <T> withMigrationLock(namespace: String, action: suspend () -> T): T {
        check(lockedSession == null) { "A migration lock is already active" }
        return database.dedicatedSession {
            lockedSession = this
            var acquired = false
            try {
                if (lock.requiresHistoryTable) executeRaw(historySql.createTable)
                lock.acquire(this, namespace)
                acquired = true
                action()
            } finally {
                try {
                    if (acquired) lock.release(this, namespace)
                } finally {
                    lockedSession = null
                }
            }
        }
    }

    override suspend fun ensureHistoryTable() {
        sql { executeRaw(historySql.createTable) }
    }

    override suspend fun readHistory(): List<MigrationHistoryEntry> = try {
        sql { queryRaw(historySql.readHistory).toList() }.map { row ->
            MigrationHistoryEntry(
                migrationId = row[0]?.toString() ?: error("Migration history migration_id is null"),
                stepIndex = row[1]?.toString()?.toIntOrNull()
                    ?: error("Migration history step_index is invalid"),
                stepSha256 = row[2]?.toString() ?: error("Migration history step_sha256 is null"),
                status = when (row[3]?.toString()) {
                    "executing" -> MigrationHistoryStatus.Executing
                    "applied" -> MigrationHistoryStatus.Applied
                    else -> error("Unknown Kagiroi migration history status")
                },
                appliedAt = row[4]?.toString(),
            )
        }
    } catch (failure: Throwable) {
        if (historySql.isMissingTable(failure)) emptyList() else throw failure
    }

    override suspend fun executeRaw(sql: String) {
        sql { executeRaw(sql) }
    }

    override suspend fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String) {
        sql { executeRaw(historySql.insertExecuting(migrationId, stepIndex, sha256)) }
    }

    override suspend fun markApplied(migrationId: String, stepIndex: Int, sha256: String) {
        sql { executeRaw(historySql.markApplied(migrationId, stepIndex, sha256)) }
    }

    override suspend fun deleteHistory(migrationId: String, stepIndex: Int) {
        sql { executeRaw(historySql.deleteHistory(migrationId, stepIndex)) }
    }

    override suspend fun replaceHistoryHash(migrationId: String, stepIndex: Int, sha256: String) {
        sql { executeRaw(historySql.replaceHistoryHash(migrationId, stepIndex, sha256)) }
    }

    override suspend fun clearMigrationLock() {
        sql { lock.clearReservation(this) }
    }

    override suspend fun close() {
        database.close()
    }

    private suspend fun <T> sql(block: suspend DedicatedSqlSession.() -> T): T {
        val session = lockedSession
        return if (session == null) database.dedicatedSession(block) else block(session)
    }
}

internal interface MigrationLock {
    val requiresHistoryTable: Boolean get() = false

    suspend fun acquire(session: DedicatedSqlSession, namespace: String)

    suspend fun release(session: DedicatedSqlSession, namespace: String)

    suspend fun clearReservation(session: DedicatedSqlSession): Unit =
        error("This migration provider does not use a durable migration lock reservation")
}

internal object PostgresMigrationLock : MigrationLock {
    override suspend fun acquire(session: DedicatedSqlSession, namespace: String) {
        session.queryRaw("SELECT pg_advisory_lock(hashtext('${sqlString(namespace)}'))").toList()
    }

    override suspend fun release(session: DedicatedSqlSession, namespace: String) {
        session.queryRaw("SELECT pg_advisory_unlock(hashtext('${sqlString(namespace)}'))").toList()
    }
}

internal object MySqlMigrationLock : MigrationLock {
    override suspend fun acquire(session: DedicatedSqlSession, namespace: String) {
        val row = session.queryRaw("SELECT GET_LOCK('${sqlString(namespace)}', 0)").toList().singleOrNull()
            ?: error("MySQL did not return a migration lock result")
        require(row[0]?.toString() == "1") { "Migration lock is already held: $namespace" }
    }

    override suspend fun release(session: DedicatedSqlSession, namespace: String) {
        session.queryRaw("SELECT RELEASE_LOCK('${sqlString(namespace)}')").toList()
    }
}

/** SQL Server session locks are released with the checked-out connection. */
internal object SqlServerMigrationLock : MigrationLock {
    override suspend fun acquire(session: DedicatedSqlSession, namespace: String) {
        val escaped = sqlString(namespace)
        val row = session.queryRaw(
            """
            DECLARE @result INT;
            EXEC @result = sys.sp_getapplock @Resource = N'$escaped', @LockMode = 'Exclusive',
                @LockOwner = 'Session', @LockTimeout = 0;
            SELECT @result AS result;
            """.trimIndent(),
        ).toList().lastOrNull() ?: error("SQL Server did not return a migration lock result")
        require(row[0]?.toString()?.toIntOrNull()?.let { it >= 0 } == true) {
            "Migration lock is already held: $namespace"
        }
    }

    override suspend fun release(session: DedicatedSqlSession, namespace: String) {
        session.executeRaw(
            "EXEC sys.sp_releaseapplock @Resource = N'${sqlString(namespace)}', @LockOwner = 'Session'",
        )
    }
}

internal object OracleMigrationLock : MigrationLock {
    override suspend fun acquire(session: DedicatedSqlSession, namespace: String) {
        session.executeRaw(oracleLockBlock("REQUEST", namespace))
    }

    override suspend fun release(session: DedicatedSqlSession, namespace: String) {
        session.executeRaw(oracleLockBlock("RELEASE", namespace))
    }
}

/** SQLite has no advisory lock. A reserved history row gives every client one durable lock owner. */
internal class SqliteMigrationLock(private val owner: String) : MigrationLock {
    override val requiresHistoryTable: Boolean = true

    override suspend fun acquire(session: DedicatedSqlSession, namespace: String) {
        try {
            session.executeRaw(
                "INSERT INTO kagiroi_migrations (migration_id, step_index, step_sha256, status, applied_at) VALUES " +
                    "('__kagiroi_lock__', -1, '${sqlString(owner)}', 'executing', CURRENT_TIMESTAMP)",
            )
        } catch (failure: Throwable) {
            if (isReservedLockCollision(failure)) {
                throw IllegalStateException("Migration lock is already held: $namespace", failure)
            }
            throw failure
        }
    }

    override suspend fun release(session: DedicatedSqlSession, namespace: String) {
        session.executeRaw(sqliteLockDelete(owner))
    }

    override suspend fun clearReservation(session: DedicatedSqlSession) {
        session.executeRaw(sqliteLockDelete())
    }
}

private fun sqliteLockDelete(owner: String? = null): String =
    "DELETE FROM kagiroi_migrations WHERE migration_id = '__kagiroi_lock__' AND step_index = -1" +
        (owner?.let { " AND step_sha256 = '${sqlString(it)}'" } ?: "")

private fun oracleLockBlock(operation: String, namespace: String): String = when (operation) {
    "REQUEST" ->
        """
        DECLARE
            lock_handle VARCHAR2(128);
            result_code INTEGER;
        BEGIN
            DBMS_LOCK.ALLOCATE_UNIQUE('KAGIROI_${sqlString(namespace)}', lock_handle);
            result_code := DBMS_LOCK.REQUEST(lock_handle, DBMS_LOCK.X_MODE, 0, FALSE);
            IF result_code <> 0 THEN
                RAISE_APPLICATION_ERROR(-20001, 'Kagiroi migration lock is already held');
            END IF;
        END;
        """.trimIndent()

    "RELEASE" ->
        """
        DECLARE
            lock_handle VARCHAR2(128);
            result_code INTEGER;
        BEGIN
            DBMS_LOCK.ALLOCATE_UNIQUE('KAGIROI_${sqlString(namespace)}', lock_handle);
            result_code := DBMS_LOCK.RELEASE(lock_handle);
            IF result_code <> 0 THEN
                RAISE_APPLICATION_ERROR(-20002, 'Kagiroi migration lock release failed');
            END IF;
        END;
        """.trimIndent()

    else -> error("Unknown Oracle migration lock operation")
}

internal sealed class MigrationHistorySql(val createTable: String, val readHistory: String) {
    protected open val tableName: String = "kagiroi_migrations"

    abstract fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String): String

    abstract fun markApplied(migrationId: String, stepIndex: Int, sha256: String): String

    fun deleteHistory(migrationId: String, stepIndex: Int): String =
        "DELETE FROM $tableName WHERE migration_id = '${sqlString(migrationId)}' AND step_index = $stepIndex"

    fun replaceHistoryHash(migrationId: String, stepIndex: Int, sha256: String): String =
        "UPDATE $tableName SET step_sha256 = '${sqlString(sha256)}' " +
            "WHERE migration_id = '${sqlString(migrationId)}' AND step_index = $stepIndex"

    abstract fun isMissingTable(failure: Throwable): Boolean

    data object Postgres : MigrationHistorySql(
        createTable = MigrationHistoryStatements(MigrationHistoryDialect.PostgreSql).createTable,
        readHistory = MigrationHistoryStatements(MigrationHistoryDialect.PostgreSql).readHistory,
    ) {
        private val statements = MigrationHistoryStatements(MigrationHistoryDialect.PostgreSql)
        override fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String): String =
            statements.insertExecuting(migrationId, stepIndex, sha256)

        override fun markApplied(migrationId: String, stepIndex: Int, sha256: String): String =
            statements.markApplied(migrationId, stepIndex, sha256)

        override fun isMissingTable(failure: Throwable): Boolean = failure.sqlStateOrVendorCode() == "42P01"
    }

    data object MySql : MigrationHistorySql(
        createTable = MigrationHistoryStatements(MigrationHistoryDialect.MySql).createTable,
        readHistory = MigrationHistoryStatements(MigrationHistoryDialect.MySql).readHistory,
    ) {
        private val statements = MigrationHistoryStatements(MigrationHistoryDialect.MySql)
        override fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String): String =
            statements.insertExecuting(migrationId, stepIndex, sha256)

        override fun markApplied(migrationId: String, stepIndex: Int, sha256: String): String =
            statements.markApplied(migrationId, stepIndex, sha256)

        override fun isMissingTable(failure: Throwable): Boolean = failure.sqlStateOrVendorCode() == "42S02"
    }

    data object Sqlite : MigrationHistorySql(
        createTable =
        """
            CREATE TABLE IF NOT EXISTS kagiroi_migrations (
                migration_id TEXT NOT NULL,
                step_index INTEGER NOT NULL,
                step_sha256 TEXT NOT NULL,
                status TEXT NOT NULL,
                applied_at TEXT NULL,
                PRIMARY KEY (migration_id, step_index)
            )
        """.trimIndent(),
        readHistory =
        "SELECT migration_id, step_index, step_sha256, status, applied_at FROM kagiroi_migrations " +
            "WHERE step_index >= 0 ORDER BY migration_id, step_index",
    ) {
        override fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String): String = insert(
            migrationId,
            stepIndex,
            sha256,
            "NULL",
            "executing",
        )

        override fun markApplied(migrationId: String, stepIndex: Int, sha256: String): String =
            insert(migrationId, stepIndex, sha256, "CURRENT_TIMESTAMP", "applied") +
                " ON CONFLICT (migration_id, step_index) DO UPDATE SET " +
                "step_sha256 = excluded.step_sha256, status = 'applied', applied_at = CURRENT_TIMESTAMP"

        override fun isMissingTable(failure: Throwable): Boolean =
            failure.message?.contains("no such table: kagiroi_migrations", ignoreCase = true) == true
    }

    data object SqlServer : MigrationHistorySql(
        createTable =
        """
            IF OBJECT_ID(N'dbo.kagiroi_migrations', N'U') IS NULL
            BEGIN
                CREATE TABLE dbo.kagiroi_migrations (
                    migration_id NVARCHAR(255) NOT NULL,
                    step_index INT NOT NULL,
                    step_sha256 NVARCHAR(64) NOT NULL,
                    status NVARCHAR(16) NOT NULL,
                    applied_at DATETIMEOFFSET NULL,
                    CONSTRAINT PK_kagiroi_migrations PRIMARY KEY (migration_id, step_index)
                )
            END
        """.trimIndent(),
        readHistory =
        "SELECT migration_id, step_index, step_sha256, status, CONVERT(NVARCHAR(34), applied_at, 127) " +
            "FROM dbo.kagiroi_migrations ORDER BY migration_id, step_index",
    ) {
        override val tableName: String = "dbo.kagiroi_migrations"

        override fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String): String = insert(
            migrationId,
            stepIndex,
            sha256,
            "NULL",
            "executing",
        )

        override fun markApplied(migrationId: String, stepIndex: Int, sha256: String): String =
            """
            UPDATE dbo.kagiroi_migrations SET step_sha256 = '${sqlString(sha256)}', status = 'applied',
                applied_at = SYSDATETIMEOFFSET()
            WHERE migration_id = '${sqlString(migrationId)}' AND step_index = $stepIndex;
            IF @@ROWCOUNT = 0
            BEGIN
                INSERT INTO dbo.kagiroi_migrations (migration_id, step_index, step_sha256, status, applied_at)
                VALUES ('${sqlString(
                migrationId,
            )}', $stepIndex, '${sqlString(sha256)}', 'applied', SYSDATETIMEOFFSET());
            END
            """.trimIndent()

        override fun isMissingTable(failure: Throwable): Boolean = failure.sqlStateOrVendorCode() == "208"
    }

    data object Oracle : MigrationHistorySql(
        createTable =
        """
            BEGIN
                EXECUTE IMMEDIATE 'CREATE TABLE kagiroi_migrations (
                    migration_id VARCHAR2(255 CHAR) NOT NULL,
                    step_index NUMBER(10) NOT NULL,
                    step_sha256 VARCHAR2(64 CHAR) NOT NULL,
                    status VARCHAR2(16 CHAR) NOT NULL,
                    applied_at TIMESTAMP WITH TIME ZONE NULL,
                    PRIMARY KEY (migration_id, step_index)
                )';
            EXCEPTION
                WHEN OTHERS THEN
                    IF SQLCODE != -955 THEN RAISE; END IF;
            END;
        """.trimIndent(),
        readHistory =
        "SELECT migration_id, step_index, step_sha256, status, TO_CHAR(applied_at, " +
            "'YYYY-MM-DD\"T\"HH24:MI:SS.FF9TZH:TZM') FROM kagiroi_migrations ORDER BY migration_id, step_index",
    ) {
        override fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String): String = insert(
            migrationId,
            stepIndex,
            sha256,
            "NULL",
            "executing",
        )

        override fun markApplied(migrationId: String, stepIndex: Int, sha256: String): String =
            """
            MERGE INTO kagiroi_migrations destination
            USING (SELECT '${sqlString(migrationId)}' migration_id, $stepIndex step_index FROM dual) source
            ON (destination.migration_id = source.migration_id AND destination.step_index = source.step_index)
            WHEN MATCHED THEN UPDATE SET destination.step_sha256 = '${sqlString(sha256)}',
                destination.status = 'applied', destination.applied_at = SYSTIMESTAMP
            WHEN NOT MATCHED THEN INSERT (migration_id, step_index, step_sha256, status, applied_at)
                VALUES ('${sqlString(migrationId)}', $stepIndex, '${sqlString(sha256)}', 'applied', SYSTIMESTAMP)
            """.trimIndent()

        override fun isMissingTable(failure: Throwable): Boolean = failure.sqlStateOrVendorCode() == "ORA-00942"
    }

    protected fun insert(
        migrationId: String,
        stepIndex: Int,
        sha256: String,
        appliedAt: String,
        status: String,
    ): String = "INSERT INTO kagiroi_migrations (migration_id, step_index, step_sha256, status, applied_at) VALUES " +
        "('${sqlString(migrationId)}', $stepIndex, '${sqlString(sha256)}', '$status', $appliedAt)"
}

private fun Throwable.sqlStateOrVendorCode(): String? = (this as? ExecutionFailure)?.metadata?.sqlState
    ?: (this as? ExecutionFailure)?.metadata?.vendorCode

internal fun sqlString(value: String): String = value.replace("'", "''")

internal fun isReservedLockCollision(failure: Throwable): Boolean = failure.message?.let { message ->
    message.contains("unique constraint", ignoreCase = true) ||
        message.contains("primary key", ignoreCase = true)
} == true
