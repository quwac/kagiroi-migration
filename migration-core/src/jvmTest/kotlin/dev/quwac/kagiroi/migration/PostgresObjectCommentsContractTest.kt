package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresObjectCommentsContractTest {
    @Test
    fun persistsUniqueAndIndexCommentsWithoutRebuildingObjectsForCommentEdits() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val namespace = "object_comments_${UUID.randomUUID().toString().replace("-", "") }"
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
        fun schema(uniqueComment: String?, indexComment: String?): CanonicalDesiredSchema {
            val reference = CanonicalTableReference(namespace, "profiles")
            val physical = CanonicalPhysicalSchema(
                tables = listOf(
                    CanonicalTable(
                        schemaName = namespace,
                        name = reference.name,
                        columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), false)),
                        uniqueConstraints = listOf(
                            CanonicalUniqueConstraint("profiles_id_unique", listOf("id"), uniqueComment),
                        ),
                    ),
                ),
                indexes = listOf(
                    CanonicalIndex(
                        schemaName = namespace,
                        name = "profiles_id_lookup",
                        table = reference,
                        unique = false,
                        keys = listOf(CanonicalColumnIndexKey(CanonicalColumnReference(reference, "id"))),
                        comment = indexComment,
                    ),
                ),
            )
            return CanonicalDesiredSchema(
                CANONICAL_SCHEMA_FORMAT_VERSION,
                physical,
                metadata = CanonicalSchemaMetadata(
                    CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
                ),
            )
        }
        val constraint = "(SELECT oid FROM pg_constraint WHERE connamespace = '$namespace'::regnamespace " +
            "AND conname = 'profiles_id_unique')"
        val index = "'$namespace.profiles_id_lookup'::regclass"
        val comments = "SELECT coalesce(obj_description($constraint, 'pg_constraint'), '') || '|' || " +
            "coalesce(obj_description($index, 'pg_class'), '');"
        val identities = "SELECT $constraint::text || '|' || $index::oid::text;"
        sql("CREATE SCHEMA \"$namespace\";")
        try {
            val initial = schema("Owner's unique", "Owner's index")
            sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), initial), MigrationDialect.PostgreSql).body)
            sql("INSERT INTO \"$namespace\".profiles VALUES (1);")
            assertEquals("Owner's unique|Owner's index", sql(comments))
            val originalIdentities = sql(identities)
            var previous = initial
            for (desired in listOf(schema("Changed unique", "Changed index"), schema(null, null))) {
                val plan = diffSchema(previous, desired)
                sql(generateMigrationScript(plan, MigrationDialect.PostgreSql).body)
                assertEquals(originalIdentities, sql(identities))
                val uniqueComment = desired.schema.tables.single().uniqueConstraints.single().comment.orEmpty()
                val indexComment = desired.schema.indexes.single().comment.orEmpty()
                assertEquals("$uniqueComment|$indexComment", sql(comments))
                assertEquals("1", sql("SELECT count(*) FROM \"$namespace\".profiles;"))
                previous = desired
            }
            val additional = AddUnique(
                CanonicalTableReference(namespace, "profiles"),
                CanonicalUniqueConstraint("profiles_id_extra", listOf("id"), "Added unique"),
            )
            sql(generateMigrationScript(MigrationPlan.Changes(listOf(additional)), MigrationDialect.PostgreSql).body)
            assertEquals(
                "Added unique",
                sql(
                    "SELECT obj_description(oid, 'pg_constraint') FROM pg_constraint WHERE connamespace = '$namespace'::regnamespace AND conname = 'profiles_id_extra';",
                ),
            )
        } finally {
            sql("DROP SCHEMA \"$namespace\" CASCADE;")
        }
    }
}
