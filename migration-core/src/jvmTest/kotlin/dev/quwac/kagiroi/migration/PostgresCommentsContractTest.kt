package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresCommentsContractTest {
    @Test
    fun persistsTableAndColumnCommentsThroughTheGeneratedScript() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val schema = "comments_${UUID.randomUUID().toString().replace("-", "") }"
        fun sql(source: String): String {
            val process = ProcessBuilder(
                "docker", "exec", "-i", container, "psql", "-X", "-U", "postgres", "-d", "postgres",
                "-v", "ON_ERROR_STOP=1", "-At",
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(source) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
            return output.trim()
        }
        val table = CanonicalTable(
            schemaName = schema,
            name = "profiles",
            comment = "Customer's profile",
            columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), false, comment = "組織ID")),
        )
        sql("CREATE SCHEMA \"$schema\";")
        try {
            val plan = MigrationPlan.Changes(listOf(CreateTable(table)))
            sql(generateMigrationScript(plan, MigrationDialect.PostgreSql).body)
            assertEquals(
                "Customer's profile|組織ID",
                sql(
                    """
                SELECT obj_description('$schema.profiles'::regclass, 'pg_class') || '|' || col_description('$schema.profiles'::regclass, 1);
                    """.trimIndent(),
                ),
            )
            val column = CanonicalColumn(
                "nickname",
                CanonicalColumnType("kagiroi.string"),
                true,
                comment = "User's nickname",
            )
            val addition = MigrationPlan.Changes(listOf(AddColumn(CanonicalTableReference(schema, table.name), column)))
            sql(generateMigrationScript(addition, MigrationDialect.PostgreSql).body)
            assertEquals("User's nickname", sql("SELECT col_description('$schema.profiles'::regclass, 2);"))
        } finally {
            sql("DROP SCHEMA \"$schema\" CASCADE;")
        }
    }
}
