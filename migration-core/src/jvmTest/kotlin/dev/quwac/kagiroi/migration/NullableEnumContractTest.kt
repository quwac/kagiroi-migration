package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NullableEnumContractTest {
    @Test
    fun preservesNullsAndExistingLabelsAndRollsBackInvalidConversions() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val namespace = "nullable_${UUID.randomUUID().toString().replace("-", "") }"
        fun sql(source: String) = containerCommentSql(
            container,
            listOf("psql", "-X", "-q", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At"),
            source,
        )
        sql("CREATE SCHEMA \"$namespace\";")
        try {
            val (before, desired) = nullableEnumConversionFixture(namespace)
            sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), before), MigrationDialect.PostgreSql).body)
            sql("INSERT INTO \"$namespace\".nullable_punches VALUES (NULL),('In'),('bad');")
            val conversion = generateMigrationScript(diffSchema(before, desired), MigrationDialect.PostgreSql).body
            assertFailsWith<AssertionError> { sql(conversion) }
            assertEquals("3", sql("SELECT count(*) FROM \"$namespace\".nullable_punches;"))
            assertEquals(
                "text",
                sql(
                    "SELECT data_type FROM information_schema.columns WHERE table_schema='$namespace' AND table_name='nullable_punches';",
                ),
            )
            sql("DELETE FROM \"$namespace\".nullable_punches WHERE kind='bad';")
            sql(conversion)
            assertEquals(
                "In\n<NULL>",
                sql(
                    "SELECT coalesce(kind::text,'<NULL>') FROM \"$namespace\".nullable_punches ORDER BY kind NULLS LAST;",
                ),
            )
        } finally {
            sql("DROP SCHEMA \"$namespace\" CASCADE;")
        }
    }
}
