package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in native SQL contract against a disposable PostgreSQL container. */
class PostgresUniqueContractTest {
    @Test
    fun removesOnlyOneMatchingConstraintAndRejectsMissingOrAmbiguousMatches() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val schema = "unique_'\"\$kagiroi_unique\$_${UUID.randomUUID()}"
        val schemaSql = identifier(schema)
        fun sql(source: String, success: Boolean = true): String {
            val process = ProcessBuilder(
                "docker", "exec", "-i", container, "psql", "-X", "-U", "postgres", "-d", "postgres",
                "-v", "ON_ERROR_STOP=1", "-At",
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(source) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (success) assertEquals(0, exit, output) else assertTrue(exit != 0, output)
            return output
        }
        fun removal(table: String, columns: List<String>): String = MigrationSqlGenerator.generate(
            MigrationPlan.Changes(
                listOf(
                    DropUnique(
                        CanonicalTableReference(schema, table),
                        CanonicalUniqueConstraint(columns = columns),
                    ),
                ),
            ),
            MigrationDialect.PostgreSql,
        ).statements.single().sql + ";"

        sql("CREATE SCHEMA $schemaSql;")
        try {
            sql(
                """
                CREATE TABLE $schemaSql.profiles (organization_id integer, employee_number text UNIQUE);
                INSERT INTO $schemaSql.profiles VALUES (1, 'same');
                ALTER TABLE $schemaSql.profiles RENAME CONSTRAINT profiles_employee_number_key TO "arbitrary-name";
                CREATE TABLE $schemaSql.untouched (employee_number text UNIQUE);
                """.trimIndent(),
            )
            sql(removal("profiles", listOf("employee_number")))
            sql(
                """
                INSERT INTO $schemaSql.profiles VALUES (2, 'same');
                ALTER TABLE $schemaSql.profiles ADD UNIQUE (organization_id, employee_number);
                """.trimIndent(),
            )
            sql(removal("profiles", listOf("organization_id", "employee_number")))
            assertEquals("2", sql("SELECT count(*) FROM $schemaSql.profiles;").trim())
            assertTrue(sql(removal("profiles", listOf("employee_number")), false).contains("found 0"))
            sql(
                """
                CREATE TABLE $schemaSql.ambiguous (
                    employee_number text,
                    CONSTRAINT first_unique UNIQUE (employee_number)
                );
                ALTER TABLE $schemaSql.ambiguous ADD CONSTRAINT second_unique UNIQUE (employee_number);
                """.trimIndent(),
            )
            assertTrue(sql(removal("ambiguous", listOf("employee_number")), false).contains("found 2"))
            assertEquals(
                "2",
                sql(
                    """
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = '${schemaSql.replace("'", "''")}.ambiguous'::regclass AND contype = 'u';
                    """.trimIndent(),
                ).trim(),
            )
            assertEquals(
                "1",
                sql(
                    """
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = '${schemaSql.replace("'", "''")}.untouched'::regclass AND contype = 'u';
                    """.trimIndent(),
                ).trim(),
            )
        } finally {
            sql("DROP SCHEMA $schemaSql CASCADE;")
        }
    }

    private fun identifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
