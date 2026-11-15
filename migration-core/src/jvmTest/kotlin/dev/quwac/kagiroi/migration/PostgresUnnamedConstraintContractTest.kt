package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in SQL contract; every run owns and removes a separate schema. */
class PostgresUnnamedConstraintContractTest {
    @Test
    fun removesRenamedKeysAndRejectsMissingOrAmbiguousForeignKeys() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val schema = "keys_'\"\$kagiroi_constraint\$_${UUID.randomUUID().toString().take(16)}"
        val schemaSql = identifier(schema)
        fun sql(source: String, success: Boolean = true): String {
            val process = ProcessBuilder(
                "docker", "exec", "-i", container, "psql", "-X", "-U", "postgres", "-d", "postgres",
                "-v", "ON_ERROR_STOP=1", "-At",
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(source) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (success) assertEquals(0, process.waitFor(), output) else assertTrue(process.waitFor() != 0, output)
            return output
        }
        fun removal(operation: MigrationOperation): String = MigrationSqlGenerator.generate(
            MigrationPlan.Changes(listOf(operation)),
            MigrationDialect.PostgreSql,
        ).statements.single().sql + ";"
        val parent = CanonicalTableReference(schema, "parent")
        val child = CanonicalTableReference(schema, "child")
        val key = CanonicalForeignKey(
            sourceColumns = listOf("organization_id", "user_id"),
            targetTable = parent,
            targetColumns = listOf("organization_id", "id"),
        )
        val referenceLiteral = "'${"$schemaSql.child".replace("'", "''")}'::regclass"
        sql("CREATE SCHEMA $schemaSql;")
        try {
            sql(
                """
                CREATE TABLE $schemaSql.parent (organization_id int, id int, PRIMARY KEY (organization_id, id));
                CREATE TABLE $schemaSql.child (
                    organization_id int, user_id int, PRIMARY KEY (organization_id, user_id),
                    CONSTRAINT arbitrary_fk FOREIGN KEY (organization_id, user_id) REFERENCES $schemaSql.parent,
                    CONSTRAINT cascade_fk FOREIGN KEY (organization_id, user_id) REFERENCES $schemaSql.parent ON DELETE CASCADE,
                    CONSTRAINT reversed_fk FOREIGN KEY (user_id, organization_id) REFERENCES $schemaSql.parent
                );
                INSERT INTO $schemaSql.parent VALUES (1, 1);
                INSERT INTO $schemaSql.child VALUES (1, 1);
                ALTER TABLE $schemaSql.child RENAME CONSTRAINT child_pkey TO "arbitrary-primary-key";
                """.trimIndent(),
            )
            sql(removal(DropForeignKey(child, key)))
            assertEquals(
                "2",
                sql("SELECT count(*) FROM pg_constraint WHERE conrelid = $referenceLiteral AND contype = 'f';").trim(),
            )
            assertTrue(sql(removal(DropForeignKey(child, key)), false).contains("found 0"))
            sql(removal(DropForeignKey(child, key.copy(onDelete = CanonicalForeignKeyAction.Cascade))))
            sql(removal(DropPrimaryKey(child)))
            sql("INSERT INTO $schemaSql.child VALUES (1, 1);")
            assertTrue(sql(removal(DropPrimaryKey(child)), false).contains("found 0"))
            sql(
                """
                ALTER TABLE $schemaSql.child ADD CONSTRAINT first_fk FOREIGN KEY (organization_id, user_id) REFERENCES $schemaSql.parent;
                ALTER TABLE $schemaSql.child ADD CONSTRAINT second_fk FOREIGN KEY (organization_id, user_id) REFERENCES $schemaSql.parent;
                """.trimIndent(),
            )
            assertTrue(sql(removal(DropForeignKey(child, key)), false).contains("found 2"))
            assertEquals(
                "3",
                sql("SELECT count(*) FROM pg_constraint WHERE conrelid = $referenceLiteral AND contype = 'f';").trim(),
            )
            assertEquals("2", sql("SELECT count(*) FROM $schemaSql.child;").trim())
        } finally {
            sql("DROP SCHEMA $schemaSql CASCADE;")
        }
    }

    private fun identifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
