package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PostgresTypeConversionContractTest {
    @Test
    fun convertsExistingValuesAndDefaultsToUtcAcrossSessionTimeZonesAndRollsBackFailures() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val namespace = "type_conversion_${UUID.randomUUID().toString().replace("-", "")}"
        fun run(source: String): Pair<Int, String> {
            val process = ProcessBuilder(
                "docker", "exec", "-i", container, "psql", "-X", "-q", "-U", "postgres", "-d", "postgres",
                "-v", "ON_ERROR_STOP=1", "-At",
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
        sql("CREATE SCHEMA \"$namespace\";")
        try {
            val (before, desired) = typeConversionFixture(namespace)
            sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), before), MigrationDialect.PostgreSql).body)
            sql("INSERT INTO \"$namespace\".events VALUES (1, TIMESTAMPTZ '2026-01-02 12:04:05.123456+09');")
            val hint = desired.evolution.typeConversions.single()
            val invalid = desired.copy(
                evolution = CanonicalEvolution(
                    typeConversions = listOf(
                        hint.copy(
                            using = CanonicalLiteralExpression(
                                CanonicalStringValue("invalid-date-time"),
                                CanonicalColumnType("kagiroi.string"),
                            ),
                        ),
                    ),
                ),
            )
            val failed = run(generateMigrationScript(diffSchema(before, invalid), MigrationDialect.PostgreSql).body)
            assertNotEquals(0, failed.first, failed.second)
            assertEquals(
                "timestamp with time zone|CURRENT_TIMESTAMP",
                sql(
                    "SELECT data_type || '|' || column_default FROM information_schema.columns " +
                        "WHERE table_schema='$namespace' AND table_name='events' AND column_name='created_at';",
                ),
            )
            sql(
                "SET TIME ZONE 'Asia/Tokyo';\n" + generateMigrationScript(
                    diffSchema(before, desired),
                    MigrationDialect.PostgreSql,
                ).body,
            )
            assertEquals("2026-01-02 03:04:05.123456", sql("SELECT created_at FROM \"$namespace\".events WHERE id=1;"))
            assertEquals(
                "t",
                sql(
                    "SET TIME ZONE 'America/New_York'; INSERT INTO \"$namespace\".events(id) VALUES (2) " +
                        "RETURNING created_at = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');",
                ),
            )
        } finally {
            sql("DROP SCHEMA \"$namespace\" CASCADE;")
        }
    }
}
