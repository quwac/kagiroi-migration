package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.migration.MigrationArtifact
import dev.quwac.kagiroi.migration.MigrationArtifactMetadata
import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.NamedMigrationArtifact
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationExecutorTest {
    @Test
    fun pending_sql_can_be_edited_without_updating_metadata() = runSuspend {
        val original = artifact("20261101090000_init.sql", "SELECT 1;")
        val edited = original.copy(artifact = original.artifact.copy(body = "BEGIN; SELECT 2; COMMIT;\nSELECT 3;"))
        val database = FakeMigrationDatabase()

        val result = MigrationExecutor(database).apply(listOf(edited))

        assertEquals(2, result.pending.size)
        assertEquals(2, database.history.size)
        assertEquals(edited.artifact.steps.map { it.sha256 }, database.history.map { it.stepSha256 })
        assertEquals(original.artifact.metadata, edited.artifact.metadata)
    }

    @Test
    fun edited_applied_sql_is_rejected_without_repairing_history() = runSuspend {
        val original = artifact("20261101090000_init.sql", "SELECT 1;")
        val originalHash = original.artifact.steps.single().sha256
        val database = FakeMigrationDatabase().also {
            it.history += MigrationHistoryEntry(original.id, 0, originalHash, MigrationHistoryStatus.Applied)
        }
        val edited = original.copy(artifact = original.artifact.copy(body = "SELECT 2;"))

        assertFailsWith<IllegalArgumentException> { MigrationExecutor(database).apply(listOf(edited)) }

        assertEquals(listOf("lock", "ensure"), database.calls)
        assertEquals(originalHash, database.history.single().stepSha256)
    }

    @Test
    fun appended_sql_in_an_applied_file_is_rejected_before_execution() = runSuspend {
        val original = artifact("20261101090000_init.sql", "BEGIN; SELECT 1; COMMIT;")
        val originalHash = original.artifact.steps.single().sha256
        val database = FakeMigrationDatabase().also {
            it.history += MigrationHistoryEntry(original.id, 0, originalHash, MigrationHistoryStatus.Applied)
        }
        val appended = original.copy(artifact = original.artifact.copy(body = original.artifact.body + "\nSELECT 2;"))

        assertFailsWith<IllegalArgumentException> { MigrationExecutor(database).apply(listOf(appended)) }

        assertEquals(listOf("lock", "ensure"), database.calls)
        assertEquals(originalHash, database.history.single().stepSha256)
    }

    @Test
    fun transactional_step_records_history_before_commit() = runSuspend {
        val database = FakeMigrationDatabase()
        val executor = MigrationExecutor(database)

        executor.apply(listOf(artifact("20261101090000_init.sql", "BEGIN;\nCREATE TABLE users (id BIGINT);\nCOMMIT;")))

        assertEquals(
            listOf(
                "lock",
                "ensure",
                "BEGIN;",
                "CREATE TABLE users (id BIGINT);",
                "applied:20261101090000_init:0",
                "COMMIT;",
            ),
            database.calls,
        )
    }

    @Test
    fun failed_transactional_history_write_rolls_back_the_user_step() = runSuspend {
        val database = FakeMigrationDatabase(failApplied = true)

        assertFailsWith<IllegalStateException> {
            runSuspend {
                MigrationExecutor(database).apply(
                    listOf(artifact("20261101090000_init.sql", "BEGIN;\nCREATE TABLE users (id BIGINT);\nCOMMIT;")),
                )
            }
        }

        assertEquals("ROLLBACK;", database.calls.last())
    }

    @Test
    fun nontransactional_failure_leaves_executing_row_and_is_not_retried() = runSuspend {
        val database = FakeMigrationDatabase(failApplied = true)
        val migration = artifact("20261101090000_index.sql", "CREATE INDEX CONCURRENTLY users_id ON users(id);")

        assertFailsWith<IllegalStateException> { runSuspend { MigrationExecutor(database).apply(listOf(migration)) } }
        assertEquals(MigrationHistoryStatus.Executing, database.history.single().status)
        assertFailsWith<IllegalArgumentException> { MigrationExecutor(database).dryRun(listOf(migration)) }
    }

    @Test
    fun applied_prefix_resumes_from_first_pending_step() = runSuspend {
        val first = artifact("20261101090000_init.sql", "CREATE TABLE users (id BIGINT);")
        val second = artifact("20261101100000_index.sql", "CREATE INDEX users_id ON users(id);")
        val database = FakeMigrationDatabase().also {
            it.history +=
                MigrationHistoryEntry(
                    first.id,
                    0,
                    first.artifact.steps.single().sha256,
                    MigrationHistoryStatus.Applied,
                )
        }

        val preflight = MigrationExecutor(database).dryRun(listOf(first, second))

        assertEquals(listOf("20261101100000_index"), preflight.pending.map(PendingMigrationStep::migrationId))
        assertEquals(emptyList(), database.calls)
    }

    @Test
    fun resumes_within_a_file_before_running_a_later_migration() = runSuspend {
        val migration = artifact(
            "20261101090000_init.sql",
            "BEGIN; CREATE TABLE users (id BIGINT); COMMIT;\nBEGIN; INSERT INTO users VALUES (1); COMMIT;",
        )
        val later = artifact("20261101100000_later.sql", "SELECT 2;")
        val database = FakeMigrationDatabase().also {
            it.history += MigrationHistoryEntry(
                migration.id,
                0,
                migration.artifact.steps.first().sha256,
                MigrationHistoryStatus.Applied,
            )
        }

        val result = MigrationExecutor(database).apply(listOf(later, migration))

        assertEquals(
            listOf(migration.id to 1, later.id to 0),
            result.pending.map { it.migrationId to it.metadata.index },
        )
        assertEquals(
            listOf(
                "lock",
                "ensure",
                "BEGIN;",
                "INSERT INTO users VALUES (1);",
                "applied:${migration.id}:1",
                "COMMIT;",
                "executing:${later.id}:0",
                "SELECT 2;",
                "applied:${later.id}:0",
            ),
            database.calls,
        )
    }

    @Test
    fun history_gap_is_divergence() = runSuspend {
        val first = artifact("20261101090000_init.sql", "CREATE TABLE users (id BIGINT);")
        val second = artifact("20261101100000_index.sql", "CREATE INDEX users_id ON users(id);")
        val database = FakeMigrationDatabase().also {
            it.history +=
                MigrationHistoryEntry(
                    second.id,
                    0,
                    second.artifact.steps.single().sha256,
                    MigrationHistoryStatus.Applied,
                )
        }

        assertFailsWith<IllegalArgumentException> { MigrationExecutor(database).dryRun(listOf(first, second)) }
    }

    @Test
    fun repair_previews_and_removes_executing_history_without_running_sql() = runSuspend {
        val migration = artifact("20261101090000_init.sql", "CREATE TABLE users (id BIGINT);")
        val database = FakeMigrationDatabase().also {
            it.history += MigrationHistoryEntry(
                migration.id,
                0,
                migration.artifact.steps.single().sha256,
                MigrationHistoryStatus.Executing,
            )
        }
        val executor = MigrationExecutor(database)

        assertEquals(1, executor.repair(listOf(migration), dryRun = true).changes.size)
        assertEquals(MigrationHistoryStatus.Executing, database.history.single().status)
        assertEquals(1, executor.repair(listOf(migration)).changes.size)
        assertEquals(emptyList(), database.history)
        assertEquals(listOf("lock", "ensure", "lock", "ensure"), database.calls)
        assertEquals(1, executor.dryRun(listOf(migration)).pending.size)
    }

    @Test
    fun repair_updates_checksums_from_the_artifact() = runSuspend {
        val migration = artifact("20261101090000_init.sql", "CREATE TABLE users (id BIGINT);")
        val database = FakeMigrationDatabase().also {
            it.history += MigrationHistoryEntry(migration.id, 0, "a".repeat(64), MigrationHistoryStatus.Applied)
        }
        val executor = MigrationExecutor(database)
        val preview = executor.repair(listOf(migration), dryRun = true)
        assertEquals("a".repeat(64), database.history.single().stepSha256)
        assertEquals(migration.artifact.steps.single().sha256, preview.changes.single().newSha256)
        executor.repair(listOf(migration))
        assertEquals(migration.artifact.steps.single().sha256, database.history.single().stepSha256)
    }

    @Test
    fun repair_rejects_unknown_history_and_gaps_before_mutation() = runSuspend {
        val first = artifact("20261101090000_init.sql", "CREATE TABLE users (id BIGINT);")
        val second = artifact("20261101100000_index.sql", "CREATE INDEX users_id ON users(id);")
        val database = FakeMigrationDatabase().also {
            it.history +=
                MigrationHistoryEntry(
                    second.id,
                    0,
                    second.artifact.steps.single().sha256,
                    MigrationHistoryStatus.Applied,
                )
        }
        assertFailsWith<IllegalArgumentException> {
            runSuspend { MigrationExecutor(database).repair(listOf(first, second)) }
        }
        assertEquals(1, database.history.size)
        assertFailsWith<IllegalArgumentException> { runSuspend { MigrationExecutor(database).repair(listOf(first)) } }
        assertEquals(1, database.history.size)
    }

    @Test
    fun repair_updates_checksums_and_removes_executing_history_together() = runSuspend {
        val first = artifact("20261101090000_init.sql", "CREATE TABLE users (id BIGINT);")
        val second = artifact("20261101100000_index.sql", "CREATE INDEX users_id ON users(id);")
        val database = FakeMigrationDatabase().also {
            it.history += MigrationHistoryEntry(first.id, 0, "a".repeat(64), MigrationHistoryStatus.Applied)
            it.history += MigrationHistoryEntry(
                second.id,
                0,
                second.artifact.steps.single().sha256,
                MigrationHistoryStatus.Executing,
            )
        }
        val executor = MigrationExecutor(database)
        assertEquals(2, executor.repair(listOf(first, second)).changes.size)
        assertEquals(listOf(first.id), database.history.map(MigrationHistoryEntry::migrationId))
        assertEquals(first.artifact.steps.single().sha256, database.history.single().stepSha256)
    }

    private fun artifact(filename: String, body: String): NamedMigrationArtifact {
        val dialect = MigrationDialect.PostgreSql
        return NamedMigrationArtifact(
            filename,
            MigrationArtifact(MigrationArtifactMetadata(dialect = dialect.id), body),
        )
    }

    private class FakeMigrationDatabase(private val failApplied: Boolean = false) : MigrationDatabase {
        val calls = mutableListOf<String>()
        val history = mutableListOf<MigrationHistoryEntry>()

        override suspend fun <T> withMigrationLock(namespace: String, action: suspend () -> T): T {
            calls += "lock"
            return action()
        }

        override suspend fun ensureHistoryTable() {
            calls += "ensure"
        }

        override suspend fun readHistory(): List<MigrationHistoryEntry> = history.toList()

        override suspend fun executeRaw(sql: String) {
            calls += sql
        }

        override suspend fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String) {
            calls += "executing:$migrationId:$stepIndex"
            history += MigrationHistoryEntry(migrationId, stepIndex, sha256, MigrationHistoryStatus.Executing)
        }

        override suspend fun markApplied(migrationId: String, stepIndex: Int, sha256: String) {
            calls += "applied:$migrationId:$stepIndex"
            if (failApplied) throw IllegalStateException("history write failed")
            history.removeAll { it.migrationId == migrationId && it.stepIndex == stepIndex }
            history += MigrationHistoryEntry(migrationId, stepIndex, sha256, MigrationHistoryStatus.Applied)
        }

        override suspend fun deleteHistory(migrationId: String, stepIndex: Int) {
            history.removeAll { it.migrationId == migrationId && it.stepIndex == stepIndex }
        }

        override suspend fun replaceHistoryHash(migrationId: String, stepIndex: Int, sha256: String) {
            val current = history.single { it.migrationId == migrationId && it.stepIndex == stepIndex }
            history.remove(current)
            history += current.copy(stepSha256 = sha256)
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                completed = result
            }
        })
        return requireNotNull(completed).getOrThrow()
    }
}
