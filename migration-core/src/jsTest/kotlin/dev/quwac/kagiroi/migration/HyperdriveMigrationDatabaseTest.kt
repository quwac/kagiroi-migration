package dev.quwac.kagiroi.migration

import dev.quwac.kagiroi.core.CapabilityKey
import dev.quwac.kagiroi.core.DatabaseCapability
import dev.quwac.kagiroi.schema.SqlExpression
import dev.quwac.kagiroi.sql.ConstraintKind
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.ExecuteResult
import dev.quwac.kagiroi.sql.RawSqlRow
import dev.quwac.kagiroi.sql.RenderedSql
import dev.quwac.kagiroi.sql.SqlDatabase
import dev.quwac.kagiroi.sql.SqlDialect
import dev.quwac.kagiroi.sql.SqlRow
import dev.quwac.kagiroi.sql.SqlTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HyperdriveMigrationDatabaseTest {
    @Test
    fun reservation_blocks_another_owner_and_is_released_after_failure() = runSuspend {
        val sql = FakeHyperdriveSql()
        val first = HyperdriveMigrationDatabase(sql, MigrationHistoryDialect.PostgreSql) { "first" }
        val second = HyperdriveMigrationDatabase(sql, MigrationHistoryDialect.PostgreSql) { "second" }

        assertFailsWith<IllegalStateException> {
            runSuspend {
                first.withMigrationLock("test") {
                    assertFailsWith<IllegalStateException> { runSuspend { second.withMigrationLock("test") {} } }
                    error("operation failed")
                }
            }
        }
        second.withMigrationLock("test") {}
        assertEquals(null, sql.owner)
        assertTrue(sql.commands.any { it.contains("'first'") })
        assertTrue(sql.commands.any { it.contains("'second'") })
    }

    @Test
    fun both_dialects_use_a_reserved_row_excluded_from_history() {
        MigrationHistoryDialect.entries.forEach { dialect ->
            val statements = MigrationHistoryStatements(dialect)
            assertTrue(statements.readHistory.contains("step_index >= 0"))
            assertTrue(statements.reserveLock("owner").contains("'__kagiroi_lock__', -1, 'owner'"))
            assertTrue(statements.releaseLock("owner").contains("step_sha256 = 'owner'"))
        }
    }

    @Test
    fun target_rejects_other_sql_dialects() {
        val database = HyperdriveMigrationDatabase(FakeHyperdriveSql(), MigrationHistoryDialect.PostgreSql) { "owner" }
        database.validateDialect(MigrationDialect.PostgreSql)
        assertFailsWith<IllegalArgumentException> { database.validateDialect(MigrationDialect.MySql) }
    }
}

private class FakeHyperdriveSql : SqlDatabase {
    var owner: String? = null
    val commands = mutableListOf<String>()
    override val dialect: SqlDialect get() = error("not used")
    override fun <C : DatabaseCapability> capability(key: CapabilityKey<C>): C? = null
    override suspend fun execute(statement: RenderedSql): ExecuteResult = error("not used")
    override fun query(statement: RenderedSql, columns: List<SqlExpression<*>>): Flow<SqlRow> = emptyFlow()
    override fun queryRaw(sql: String, parameters: List<Any?>): Flow<RawSqlRow> = emptyFlow()
    override suspend fun executeRaw(sql: String, parameters: List<Any?>): ExecuteResult {
        commands += sql
        if (sql.startsWith("INSERT") && sql.contains("'__kagiroi_lock__'")) {
            if (owner != null) throw ConstraintViolation("duplicate", kind = ConstraintKind.UNIQUE)
            owner = if (sql.contains("'first'")) "first" else "second"
        }
        if (sql.startsWith("DELETE") && sql.contains("'__kagiroi_lock__'")) owner = null
        return ExecuteResult(1)
    }
    override suspend fun <T> transaction(block: suspend SqlTransaction.() -> T): T = error("not used")
    override suspend fun close() = Unit
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return requireNotNull(outcome).getOrThrow()
}
