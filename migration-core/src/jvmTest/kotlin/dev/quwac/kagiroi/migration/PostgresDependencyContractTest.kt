package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresDependencyContractTest {
    @Test
    fun appliesParentChildChangesCyclicReferencesAndReverseOrderDrops() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val namespace = "dependencies_${UUID.randomUUID().toString().replace("-", "") }"
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
        fun table(name: String) = CanonicalTable(
            schemaName = namespace,
            name = name,
            columns = listOf(
                CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), nullable = false, primaryKey = true),
            ),
        )
        fun key(name: String, target: String, source: String = "id") = CanonicalForeignKey(
            name,
            listOf(source),
            CanonicalTableReference(namespace, target),
            listOf("id"),
        )
        fun schema(vararg tables: CanonicalTable): CanonicalDesiredSchema {
            val physical = CanonicalPhysicalSchema(tables = tables.toList())
            return CanonicalDesiredSchema(
                CANONICAL_SCHEMA_FORMAT_VERSION,
                physical,
                metadata = CanonicalSchemaMetadata(
                    CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
                ),
            )
        }
        fun apply(previous: CanonicalDesiredSchema, desired: CanonicalDesiredSchema) {
            sql(generateMigrationScript(diffSchema(previous, desired), MigrationDialect.PostgreSql).body)
        }

        sql("CREATE SCHEMA \"$namespace\";")
        try {
            val child = table("a_child")
            val previous = schema(child)
            apply(emptyCanonicalSchema(), previous)
            sql("INSERT INTO \"$namespace\".a_child VALUES (1);")
            val parent = table("z_parent")
            val linkedChild = child.copy(
                columns = child.columns + CanonicalColumn(
                    "parent_id",
                    CanonicalColumnType("kagiroi.int32"),
                    nullable = true,
                ),
                foreignKeys = listOf(key("child_parent", "z_parent", "parent_id")),
            )
            val linked = schema(linkedChild, parent)
            apply(previous, linked)
            assertEquals("1", sql("SELECT count(*) FROM \"$namespace\".a_child;"))

            val first = table("a_cycle").copy(foreignKeys = listOf(key("a_b", "b_cycle")))
            val second = table("b_cycle").copy(foreignKeys = listOf(key("b_a", "a_cycle")))
            val cyclic = schema(linkedChild, parent, first, second)
            apply(linked, cyclic)
            assertEquals(
                "3",
                sql(
                    "SELECT count(*) FROM pg_constraint WHERE connamespace = '$namespace'::regnamespace AND contype = 'f';",
                ),
            )
            apply(cyclic, emptyCanonicalSchema())
            assertEquals("0", sql("SELECT count(*) FROM pg_tables WHERE schemaname = '$namespace';"))
        } finally {
            sql("DROP SCHEMA \"$namespace\" CASCADE;")
        }
    }
}
