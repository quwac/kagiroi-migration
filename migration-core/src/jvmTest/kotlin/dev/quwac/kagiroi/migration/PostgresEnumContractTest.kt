package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PostgresEnumContractTest {
    @Test
    fun preservesTextRowsRejectsInvalidValuesAndCommitsAddedLabelsBeforeUse() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val schema = "enum_${UUID.randomUUID().toString().replace("-", "")}"
        fun run(source: String): Pair<Int, String> {
            val process = ProcessBuilder(
                "docker", "exec", "-i", container, "psql", "-X", "-q",
                "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At",
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(source) }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            return process.waitFor() to output
        }
        fun sql(source: String): String {
            val (code, output) = run(source)
            assertEquals(0, code, output)
            return output
        }
        sql("CREATE SCHEMA \"$schema\";")
        try {
            val (before, desired) = postgresEnumFixture(schema)
            sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), before), MigrationDialect.PostgreSql).body)
            sql("INSERT INTO \"$schema\".punches VALUES ('ClockIn'), ('ClockOut');")
            val conversion = generateMigrationScript(diffSchema(before, desired), MigrationDialect.PostgreSql).body
            sql("INSERT INTO \"$schema\".punches VALUES ('invalid');")
            assertNotEquals(0, run(conversion).first)
            assertEquals("3", sql("SELECT count(*) FROM \"$schema\".punches;"))
            assertEquals(
                "text",
                sql(
                    "SELECT data_type FROM information_schema.columns WHERE table_schema='$schema' AND table_name='punches';",
                ),
            )
            assertEquals(
                "",
                sql(
                    "SELECT t.typname FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='$schema' AND t.typname='punch_kind';",
                ),
            )
            sql("DELETE FROM \"$schema\".punches WHERE kind='invalid';")
            sql(conversion)
            assertEquals("ClockIn\nClockOut", sql("SELECT kind FROM \"$schema\".punches ORDER BY kind;"))
            assertNotEquals(0, run("INSERT INTO \"$schema\".punches VALUES ('invalid');").first)
            val reference = CanonicalColumnReference(CanonicalTableReference(schema, "punches"), "kind")
            val add = AddPostgresEnumValue(desired.schema.enumTypes.single().reference(), "New")
            val default = SetDefault(
                reference,
                CanonicalLiteralExpression(CanonicalStringValue("New"), CanonicalColumnType("kagiroi.string")),
            )
            sql(generateMigrationScript(MigrationPlan.Changes(listOf(add, default)), MigrationDialect.PostgreSql).body)
            assertEquals("New", sql("INSERT INTO \"$schema\".punches DEFAULT VALUES RETURNING kind;"))
            assertEquals("3", sql("SELECT count(*) FROM \"$schema\".punches;"))
            sql(generateMigrationScript(diffSchema(desired, emptyCanonicalSchema()), MigrationDialect.PostgreSql).body)
        } finally {
            sql("DROP SCHEMA \"$schema\" CASCADE;")
        }
    }
}
