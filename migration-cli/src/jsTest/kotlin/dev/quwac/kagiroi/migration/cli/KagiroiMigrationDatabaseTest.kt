@file:Suppress("UNCHECKED_CAST")

package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.DedicatedSqlSession
import dev.quwac.kagiroi.sql.DedicatedSqlSessionCapability
import dev.quwac.kagiroi.sql.ExecuteResult
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.SqlTransaction
import dev.quwac.kagiroi.sql.rawSqlRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KagiroiMigrationDatabaseTest {
    @Test
    fun keepsLockHistoryAndExplicitTransactionOnOneDriverSession() = runSuspend {
        val driver = FakeSqlDatabase()
        val database = KagiroiMigrationDatabase(driver, MigrationHistorySql.Postgres, RecordingLock)

        database.withMigrationLock("kagiroi_migrations") {
            database.ensureHistoryTable()
            database.executeRaw("BEGIN;")
            database.markApplied("20261101090000_init", 0, "a".repeat(64))
            database.executeRaw("COMMIT;")
        }

        assertEquals(1, driver.leaseCount)
        assertEquals(
            listOf(
                "LOCK kagiroi_migrations",
                MigrationHistorySql.Postgres.createTable,
                "BEGIN;",
                "INSERT INTO kagiroi_migrations (migration_id, step_index, step_sha256, status, applied_at) VALUES " +
                    "('20261101090000_init', 0, '${"a".repeat(64)}', 'applied', CURRENT_TIMESTAMP) " +
                    "ON CONFLICT (migration_id, step_index) DO UPDATE SET " +
                    "step_sha256 = EXCLUDED.step_sha256, status = 'applied', applied_at = CURRENT_TIMESTAMP",
                "COMMIT;",
                "UNLOCK kagiroi_migrations",
            ),
            driver.session.statements,
        )
    }

    @Test
    fun closesTheDriverWhenTheCliCompletes() = runSuspend {
        val driver = FakeSqlDatabase()
        val database = KagiroiMigrationDatabase(driver, MigrationHistorySql.MySql, RecordingLock)

        database.close()

        assertTrue(driver.closed)
    }

    @Test
    fun reservesTheSingleSQLiteMigrationLockRow() = runSuspend {
        val driver = FakeSqlDatabase()
        val database = KagiroiMigrationDatabase(
            driver,
            MigrationHistorySql.Sqlite,
            SqliteMigrationLock("owner"),
        )

        database.withMigrationLock("kagiroi_migrations") { }

        assertEquals(
            listOf(
                MigrationHistorySql.Sqlite.createTable,
                "INSERT INTO kagiroi_migrations (migration_id, step_index, step_sha256, status, applied_at) VALUES " +
                    "('__kagiroi_lock__', -1, 'owner', 'executing', CURRENT_TIMESTAMP)",
                "DELETE FROM kagiroi_migrations WHERE migration_id = '__kagiroi_lock__' AND step_index = -1 " +
                    "AND step_sha256 = 'owner'",
            ),
            driver.session.statements,
        )
    }

    @Test
    fun acceptsSqlServerApplicationLockSuccessReturnedAsAKotlinLong() = runSuspend {
        val session = FakeDedicatedSqlSession().apply {
            rawRows = listOf(rawSqlRow(listOf("result"), listOf(0L)))
        }

        SqlServerMigrationLock.acquire(session, "kagiroi_migrations")

        assertTrue(session.queries.single().contains("sp_getapplock"))
    }
}

private object RecordingLock : MigrationLock {
    override suspend fun acquire(session: DedicatedSqlSession, namespace: String) {
        session.executeRaw("LOCK $namespace")
    }

    override suspend fun release(session: DedicatedSqlSession, namespace: String) {
        session.executeRaw("UNLOCK $namespace")
    }
}

private class FakeSqlDatabase : SqlDatabase {
    val session = FakeDedicatedSqlSession()
    var leaseCount = 0
    var closed = false

    private val dedicatedSession = object : DedicatedSqlSessionCapability {
        override suspend fun <T> use(block: suspend DedicatedSqlSession.() -> T): T {
            leaseCount++
            return block(session)
        }
    }

    override val dialect: SqlDialect
        get() = error("No typed SQL is used by migration execution")

    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? = when (key) {
        dev.quwac.kagiroi.sql.SqlCapabilities.DedicatedSession -> dedicatedSession as C
        else -> null
    }

    override suspend fun execute(statement: RenderedSql): ExecuteResult = error("Not used")

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = emptyFlow()

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult = error("Not used")

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = emptyFlow()

    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T = error("Not used")

    override suspend fun close() {
        closed = true
    }
}

private class FakeDedicatedSqlSession : DedicatedSqlSession {
    val statements = mutableListOf<String>()
    val queries = mutableListOf<String>()
    var rawRows = emptyList<RawSqlRow>()

    override val dialect: SqlDialect
        get() = error("No typed SQL is used by migration execution")

    override suspend fun execute(statement: RenderedSql): ExecuteResult = error("Not used")

    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = emptyFlow()

    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult {
        check(parameters.isEmpty())
        statements += sql
        return ExecuteResult(0)
    }

    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> {
        check(parameters.isEmpty())
        queries += sql
        return flowOf(*rawRows.toTypedArray())
    }
}

fun runSuspend(block: suspend () -> Unit) {
    var failure: Throwable? = null
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<Unit>) {
            failure = result.exceptionOrNull()
        }
    })
    failure?.let { throw it }
}
