package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.sql.RawSqlRow
import kotlin.test.Test
import kotlin.test.assertEquals

class D1MigrationDatabaseTest {
    @Test
    fun sendsExplicitTransactionBodyAndHistoryWriteAsOneD1Batch() = runSuspend {
        val transport = RecordingD1Transport()
        val database = D1MigrationDatabase(transport) { "test-owner" }

        database.withMigrationLock("kagiroi_migrations") {
            database.executeRaw("BEGIN;")
            database.executeRaw("ALTER TABLE users ADD COLUMN email TEXT;")
            database.markApplied("20261101090000_add_email", 0, "a".repeat(64))
            database.executeRaw("COMMIT;")
        }

        assertEquals(
            listOf(
                MigrationHistorySql.Sqlite.createTable,
                "INSERT INTO kagiroi_migrations (migration_id, step_index, step_sha256, status, applied_at) VALUES " +
                    "('__kagiroi_lock__', -1, 'test-owner', 'executing', CURRENT_TIMESTAMP)",
                "DELETE FROM kagiroi_migrations WHERE migration_id = '__kagiroi_lock__' AND step_index = -1 " +
                    "AND step_sha256 = 'test-owner'",
            ),
            transport.executed,
        )
        assertEquals(
            listOf(
                listOf(
                    "ALTER TABLE users ADD COLUMN email TEXT;",
                    MigrationHistorySql.Sqlite.markApplied("20261101090000_add_email", 0, "a".repeat(64)),
                ),
            ),
            transport.batches,
        )
    }
}

private class RecordingD1Transport : D1MigrationTransport {
    val executed = mutableListOf<String>()
    val batches = mutableListOf<List<String>>()

    override suspend fun execute(sql: String) {
        executed += sql
    }

    override suspend fun query(sql: String): List<RawSqlRow> = emptyList()

    override suspend fun batch(statements: List<String>) {
        batches += statements
    }

    override suspend fun close() = Unit
}
