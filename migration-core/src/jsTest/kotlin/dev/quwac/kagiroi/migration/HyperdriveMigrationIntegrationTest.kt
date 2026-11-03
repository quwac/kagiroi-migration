@file:Suppress("UnsafeCastFromDynamic")

package dev.quwac.kagiroi.migration

import dev.quwac.kagiroi.mysql.hyperdrive.HyperdriveMySqlDatabase
import dev.quwac.kagiroi.postgres.hyperdrive.HyperdrivePostgresDatabase
import dev.quwac.kagiroi.sql.ConstraintViolation
import dev.quwac.kagiroi.sql.SqlDatabase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import dev.quwac.kagiroi.mysql.hyperdrive.CloudflareHyperdriveBinding as MySqlBinding
import dev.quwac.kagiroi.postgres.hyperdrive.CloudflareHyperdriveBinding as PostgresBinding

/** Uses a disposable test database supplied by the test container runner. */
class HyperdriveMigrationIntegrationTest {
    @Test
    fun postgres() = runTest {
        val url = environment("KAGIROI_MIGRATION_TEST_POSTGRES_URL") ?: return@runTest
        contract(
            { HyperdrivePostgresDatabase(postgresBinding(url)) },
            MigrationHistoryDialect.PostgreSql,
            MigrationDialect.PostgreSql,
        )
    }

    @Test
    fun mysql() = runTest {
        val url = environment("KAGIROI_MIGRATION_TEST_MYSQL_URL") ?: return@runTest
        contract(
            { HyperdriveMySqlDatabase(mysqlBinding(url)) },
            MigrationHistoryDialect.MySql,
            MigrationDialect.MySql,
        )
    }

    private suspend fun contract(
        open: () -> SqlDatabase,
        historyDialect: MigrationHistoryDialect,
        dialect: MigrationDialect,
    ) {
        val first = HyperdriveMigrationDatabase(open(), historyDialect) { "first" }
        val second = HyperdriveMigrationDatabase(open(), historyDialect) { "second" }
        try {
            first.withMigrationLock("test") {
                assertFailsWith<IllegalStateException> { second.withMigrationLock("test") {} }
            }
            val body = "CREATE TABLE kagiroi_migration_contract (id INTEGER);\n" +
                "BEGIN;\nINSERT INTO kagiroi_migration_contract VALUES (1);\nCOMMIT;"
            val parsed = MigrationScriptParser.parse(body, dialect)
            val steps = parsed.steps.mapIndexed { index, step ->
                MigrationArtifactStep(index, step.kind, stepFingerprint(step), SchemaDelta())
            }
            val artifacts = listOf(
                NamedMigrationArtifact(
                    "20261101090000_contract.sql",
                    MigrationArtifact(MigrationArtifactMetadata(dialect = dialect.id, steps = steps), body),
                ),
            )
            val executor = MigrationExecutor(first)
            assertEquals(2, executor.dryRun(artifacts).pending.size)
            assertEquals(2, executor.apply(artifacts).pending.size)
            assertEquals(0, executor.dryRun(artifacts).pending.size)

            first.executeRaw(
                "ALTER TABLE kagiroi_migrations ADD CONSTRAINT kagiroi_reject_history " +
                    "CHECK (migration_id <> '20261101093000_failure')",
            )
            val failureBody = "BEGIN;\nINSERT INTO kagiroi_migration_contract VALUES (2);\nCOMMIT;"
            val failureSteps = MigrationScriptParser.parse(failureBody, dialect).steps.mapIndexed { index, step ->
                MigrationArtifactStep(index, step.kind, stepFingerprint(step), SchemaDelta())
            }
            val failureArtifact = NamedMigrationArtifact(
                "20261101093000_failure.sql",
                MigrationArtifact(MigrationArtifactMetadata(dialect = dialect.id, steps = failureSteps), failureBody),
            )
            assertFailsWith<ConstraintViolation> { executor.apply(artifacts + failureArtifact) }
            val reader = open()
            try {
                val rows = reader.queryRaw("SELECT COUNT(*) AS count FROM kagiroi_migration_contract").toList()
                assertEquals("1", rows.single()[0].toString())
            } finally {
                reader.close()
            }
        } finally {
            try {
                first.close()
            } finally {
                second.close()
            }
        }
    }
}

@Suppress("UnusedParameter")
private fun environment(name: String): String? = js("process.env[name]") as? String

@Suppress("UnusedParameter")
private fun postgresBinding(url: String): PostgresBinding =
    js("({ connectionString: url })").unsafeCast<PostgresBinding>()

@Suppress("UnusedParameter")
private fun mysqlBinding(url: String): MySqlBinding = js(
    "(() => { const u = new URL(url); return { host: u.hostname, port: Number(u.port || 3306), " +
        "user: decodeURIComponent(u.username), password: decodeURIComponent(u.password), database: u.pathname.slice(1) }; })()",
).unsafeCast<MySqlBinding>()
