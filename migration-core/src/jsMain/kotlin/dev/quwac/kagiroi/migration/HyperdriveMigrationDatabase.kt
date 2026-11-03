package dev.quwac.kagiroi.migration

import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.DatabaseError
import dev.quwac.kagiroi.sql.ExecutionFailure
import dev.quwac.kagiroi.sql.ResultConversionFailure
import dev.quwac.kagiroi.sql.SqlDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Wraps a Kagiroi Hyperdrive database. Use a cache-disabled Hyperdrive configuration.
 * The caller must not concurrently use [database] while this adapter is active.
 * Direct connections using advisory locks do not share this reservation.
 */
class HyperdriveMigrationDatabase(
    private val database: SqlDatabase,
    private val dialect: MigrationHistoryDialect,
    private val newOwner: () -> String,
) : MigrationDatabase {
    private val statements = MigrationHistoryStatements(dialect)
    private var locked = false

    override fun validateDialect(dialect: MigrationDialect) {
        val supported = when (this.dialect) {
            MigrationHistoryDialect.PostgreSql -> setOf(MigrationDialect.PostgreSql)
            MigrationHistoryDialect.MySql -> setOf(MigrationDialect.MySql, MigrationDialect.MariaDb)
        }
        require(dialect in supported) { "Migration artifact dialect ${dialect.id} does not match Hyperdrive target" }
    }

    @Suppress("TooGenericExceptionCaught", "ThrowingExceptionFromFinally", "ThrowsCount")
    override suspend fun <T> withMigrationLock(namespace: String, action: suspend () -> T): T {
        check(!locked) { "A migration lock is already active" }
        locked = true
        try {
            ensureHistoryTable()
            val candidate = newOwner()
            require(candidate.isNotBlank() && candidate.length <= LOCK_OWNER_MAX_LENGTH) {
                "Lock owner must have 1 to 64 characters"
            }
            try {
                database.executeRaw(statements.reserveLock(candidate))
            } catch (failure: ConstraintViolation) {
                if (failure.kind != ConstraintKind.UNIQUE) throw failure
                throw IllegalStateException("Migration lock is already held: $namespace", failure)
            }
            var originalFailure: Throwable? = null
            try {
                return action()
            } catch (failure: Throwable) {
                originalFailure = failure
                throw failure
            } finally {
                try {
                    withContext(NonCancellable) { database.executeRaw(statements.releaseLock(candidate)) }
                } catch (releaseFailure: Throwable) {
                    val original = originalFailure
                    if (original == null) throw releaseFailure
                    original.addSuppressed(releaseFailure)
                }
            }
        } finally {
            locked = false
        }
    }

    override suspend fun ensureHistoryTable() {
        database.executeRaw(statements.createTable)
    }

    override suspend fun readHistory(): List<MigrationHistoryEntry> = try {
        database.queryRaw(statements.readHistory).toList().map { row ->
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
    } catch (failure: DatabaseError) {
        val sqlState = when (failure) {
            is ExecutionFailure -> failure.metadata.sqlState
            is ResultConversionFailure -> failure.metadata.sqlState
            else -> null
        }
        val missing = when (dialect) {
            MigrationHistoryDialect.PostgreSql -> sqlState == "42P01"
            MigrationHistoryDialect.MySql -> sqlState == "42S02"
        }
        if (missing) emptyList() else throw failure
    }

    override suspend fun executeRaw(sql: String) {
        database.executeRaw(sql)
    }
    override suspend fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String) {
        database.executeRaw(statements.insertExecuting(migrationId, stepIndex, sha256))
    }
    override suspend fun markApplied(migrationId: String, stepIndex: Int, sha256: String) {
        database.executeRaw(statements.markApplied(migrationId, stepIndex, sha256))
    }
    override suspend fun deleteHistory(migrationId: String, stepIndex: Int) {
        database.executeRaw(statements.deleteHistory(migrationId, stepIndex))
    }
    override suspend fun replaceHistoryHash(migrationId: String, stepIndex: Int, sha256: String) {
        database.executeRaw(statements.replaceHistoryHash(migrationId, stepIndex, sha256))
    }
    override suspend fun clearMigrationLock() {
        database.executeRaw(statements.releaseLock())
    }
    override suspend fun close() {
        database.close()
    }
}

private const val LOCK_OWNER_MAX_LENGTH = 64
