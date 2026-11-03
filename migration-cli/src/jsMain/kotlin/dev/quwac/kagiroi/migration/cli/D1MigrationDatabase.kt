@file:Suppress("TooGenericExceptionCaught")

package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.d1.D1Database
import dev.quwac.kagiroi.sql.RawSqlRow
import kotlinx.coroutines.flow.toList

/**
 * D1's HTTP API has no connection-bound transaction. Explicit transactional steps are buffered
 * until COMMIT and submitted through D1's atomic batch endpoint with their history write.
 */
internal class D1MigrationDatabase(
    private val transport: D1MigrationTransport,
    private val newLockOwner: () -> String = ::d1LockOwner,
) : MigrationDatabase {
    internal constructor(database: D1Database) : this(D1DatabaseTransport(database))

    private var transaction: MutableList<String>? = null
    private var lockOwner: String? = null

    override suspend fun <T> withMigrationLock(namespace: String, action: suspend () -> T): T {
        check(lockOwner == null) { "A migration lock is already active" }
        transport.execute(MigrationHistorySql.Sqlite.createTable)
        val owner = newLockOwner()
        try {
            transport.execute(d1LockInsert(owner))
        } catch (failure: Throwable) {
            if (isReservedLockCollision(failure)) {
                throw IllegalStateException("Migration lock is already held: $namespace", failure)
            }
            throw failure
        }
        lockOwner = owner
        try {
            return action()
        } finally {
            try {
                transport.execute(d1LockDelete(owner))
            } finally {
                lockOwner = null
            }
        }
    }

    override suspend fun ensureHistoryTable() {
        executeRaw(MigrationHistorySql.Sqlite.createTable)
    }

    override suspend fun readHistory(): List<MigrationHistoryEntry> = try {
        transport.query(MigrationHistorySql.Sqlite.readHistory).map { row ->
            MigrationHistoryEntry(
                migrationId = row[0]?.toString() ?: error("Migration history migration_id is null"),
                stepIndex = row[1]?.toString()?.toIntOrNull() ?: error("Migration history step_index is invalid"),
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
        if (MigrationHistorySql.Sqlite.isMissingTable(failure)) emptyList() else throw failure
    }

    override suspend fun executeRaw(sql: String) {
        when (transactionControl(sql)) {
            D1TransactionControl.Begin -> {
                check(transaction == null) { "Nested transactions are not supported for D1 migration execution" }
                transaction = mutableListOf()
            }

            D1TransactionControl.Commit -> {
                val statements = requireNotNull(transaction) { "COMMIT has no open D1 migration transaction" }
                transaction = null
                if (statements.isNotEmpty()) transport.batch(statements)
            }

            D1TransactionControl.Rollback -> {
                check(transaction != null) { "ROLLBACK has no open D1 migration transaction" }
                transaction = null
            }

            null -> {
                val statements = transaction
                if (statements == null) transport.execute(sql) else statements += sql
            }
        }
    }

    override suspend fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String) {
        executeRaw(MigrationHistorySql.Sqlite.insertExecuting(migrationId, stepIndex, sha256))
    }

    override suspend fun markApplied(migrationId: String, stepIndex: Int, sha256: String) {
        executeRaw(MigrationHistorySql.Sqlite.markApplied(migrationId, stepIndex, sha256))
    }

    override suspend fun deleteHistory(migrationId: String, stepIndex: Int) {
        executeRaw(MigrationHistorySql.Sqlite.deleteHistory(migrationId, stepIndex))
    }

    override suspend fun replaceHistoryHash(migrationId: String, stepIndex: Int, sha256: String) {
        executeRaw(MigrationHistorySql.Sqlite.replaceHistoryHash(migrationId, stepIndex, sha256))
    }

    override suspend fun clearMigrationLock() {
        check(transaction == null) { "Cannot clear a D1 migration lock during a transaction" }
        transport.execute(d1LockDelete())
    }

    override suspend fun close() {
        transport.close()
    }
}

internal interface D1MigrationTransport {
    suspend fun execute(sql: String)

    suspend fun query(sql: String): List<RawSqlRow>

    suspend fun batch(statements: List<String>)

    suspend fun close()
}

private class D1DatabaseTransport(private val database: D1Database) : D1MigrationTransport {
    override suspend fun execute(sql: String) {
        database.executeRaw(sql)
    }

    override suspend fun query(sql: String): List<RawSqlRow> = database.queryRaw(sql).toList()

    override suspend fun batch(statements: List<String>) {
        database.batch { statements.forEach { sql -> raw(sql) } }
    }

    override suspend fun close() {
        database.close()
    }
}

private enum class D1TransactionControl { Begin, Commit, Rollback }

private fun transactionControl(sql: String): D1TransactionControl? = when (
    sql.trim().removeSuffix(";").trim().uppercase()
) {
    "BEGIN", "BEGIN TRANSACTION", "START TRANSACTION" -> D1TransactionControl.Begin
    "COMMIT", "COMMIT TRANSACTION" -> D1TransactionControl.Commit
    "ROLLBACK", "ROLLBACK TRANSACTION" -> D1TransactionControl.Rollback
    else -> null
}

private fun d1LockInsert(owner: String): String =
    "INSERT INTO kagiroi_migrations (migration_id, step_index, step_sha256, status, applied_at) VALUES " +
        "('__kagiroi_lock__', -1, '${sqlString(owner)}', 'executing', CURRENT_TIMESTAMP)"

private fun d1LockDelete(owner: String? = null): String =
    "DELETE FROM kagiroi_migrations WHERE migration_id = '__kagiroi_lock__' AND step_index = -1 " +
        (owner?.let { "AND step_sha256 = '${sqlString(it)}'" } ?: "")

@Suppress("UnusedParameter")
private fun d1LockOwner(): String = js("globalThis.crypto.randomUUID()") as String
