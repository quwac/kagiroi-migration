package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresJsonbContractTest {
    @Test
    fun createsAddsAndConvertsJsonColumnsAndRoundTripsValues() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val namespace = "jsonb_${UUID.randomUUID().toString().replace("-", "")}"
        fun sql(source: String) = executeSql(container, source)
        val reference = CanonicalTableReference(namespace, "documents")
        val jsonb = declared("JSONB")
        val text = CanonicalColumnType("kagiroi.string")
        val payload = CanonicalColumnReference(reference, "payload")
        val create = createJsonbTable(reference, text, jsonb)
        sql("CREATE SCHEMA \"$namespace\";")
        try {
            sql(generateMigrationScript(create, MigrationDialect.PostgreSql).body)
            sql(
                "INSERT INTO \"$namespace\".documents VALUES " +
                    "('{\"time\":\"09:30\"}', '{\"reason\":\"修正\"}', ARRAY['{\"version\":1}']::JSONB[], '$.time'), " +
                    "(NULL, NULL, NULL, NULL);",
            )
            val alter = MigrationPlan.Changes(
                listOf(
                    AddColumn(reference, CanonicalColumn("source", declared("JSON"), true)),
                    AlterColumnType(
                        payload,
                        text,
                        jsonb,
                        CanonicalCastExpression(CanonicalColumnExpression(payload), jsonb),
                    ),
                ),
            )
            sql(generateMigrationScript(alter, MigrationDialect.PostgreSql).body)
            assertEquals(
                "09:30|修正|1",
                sql(
                    "SELECT payload->>'time', snapshot->>'reason', history[1]->>'version' FROM \"$namespace\".documents WHERE payload IS NOT NULL;",
                ),
            )
            assertEquals(
                "1",
                sql("SELECT count(*) FROM \"$namespace\".documents WHERE payload IS NULL AND snapshot IS NULL;"),
            )
            assertEquals(
                "jsonb",
                sql(
                    "SELECT data_type FROM information_schema.columns WHERE table_schema='$namespace' AND table_name='documents' AND column_name='payload';",
                ),
            )
        } finally {
            sql("DROP SCHEMA \"$namespace\" CASCADE;")
        }
    }

    private fun declared(declaration: String) = CanonicalColumnType(
        "kagiroi.postgres.declared",
        listOf(CanonicalTypeArgument("declaration", declaration)),
    )

    private fun createJsonbTable(
        reference: CanonicalTableReference,
        text: CanonicalColumnType,
        jsonb: CanonicalColumnType,
    ) = MigrationPlan.Changes(
        listOf(
            CreateTable(
                CanonicalTable(
                    schemaName = reference.schemaName,
                    name = reference.name,
                    columns = listOf(
                        CanonicalColumn("payload", text, true),
                        CanonicalColumn("snapshot", jsonb, true),
                        CanonicalColumn(
                            "history",
                            declared("JSONB[]"),
                            true,
                        ),
                        CanonicalColumn(
                            "json_path",
                            declared("JSONPATH"),
                            true,
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun executeSql(container: String, source: String): String {
        val process = ProcessBuilder(
            "docker", "exec", "-i", container, "psql", "-X", "-q", "-U", "postgres", "-d", "postgres",
            "-v", "ON_ERROR_STOP=1", "-At",
        ).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(source) }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.waitFor(), output)
        return output
    }
}
