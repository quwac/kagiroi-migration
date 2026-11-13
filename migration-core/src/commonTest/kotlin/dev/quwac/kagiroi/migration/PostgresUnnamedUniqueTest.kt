package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresUnnamedUniqueTest {
    @Test
    fun removalReplaysColumnDeclaredUniqueWithoutGeneratingItAgain() {
        val column = CanonicalColumn("employee_number", CanonicalColumnType("kagiroi.string"), false, unique = true)
        val table = CanonicalTable(name = "profiles", columns = listOf(column))
        val initial = SchemaDelta(listOf(CreateTable(table)))
        val previous = replaySchemaDeltas(listOf(initial))
        val desired = replaySchemaDeltas(
            listOf(
                SchemaDelta(
                    listOf(
                        CreateTable(
                            table.copy(
                                columns = listOf(column.copy(unique = false)),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val plan = diffSchema(previous, desired)
        val replayed = replaySchemaDeltas(listOf(initial, SchemaDelta(plan.operations)))
        assertEquals(desired.schema, replayed.schema)
        assertEquals(MigrationPlan.NoChanges, diffSchema(replayed, desired))
    }

    @Test
    fun resolvesUnnamedUniqueByRelationAndOrderedColumnsAtExecutionTime() {
        val plan = dropUnique(null)
        val sql = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.single().sql
        assertTrue(sql.contains("LOCK TABLE \"app\".\"profiles\" IN ACCESS EXCLUSIVE MODE"))
        assertTrue(sql.contains("c.conrelid = '\"app\".\"profiles\"'::pg_catalog.regclass"))
        assertTrue(sql.contains("c.contype = 'u'"))
        assertTrue(sql.contains("ARRAY['organization_id', 'employee_number']::text[]"))
        assertTrue(sql.contains("coalesce(pg_catalog.cardinality(constraint_names), 0) <> 1"))
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        val artifact = createMigrationArtifact(script.body, MigrationDialect.PostgreSql, plan)
        assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
        assertEquals(1, MigrationScriptParser.parse(script.body, MigrationDialect.PostgreSql).steps.size)
    }

    @Test
    fun keepsExplicitNamesAndOtherDialectsUnchanged() {
        assertEquals(
            "ALTER TABLE \"app\".\"profiles\" DROP CONSTRAINT \"profile_unique\"",
            MigrationSqlGenerator.generate(
                dropUnique("profile_unique"),
                MigrationDialect.PostgreSql,
            ).statements.single().sql,
        )
        for (dialect in MigrationDialect.entries.filter { it != MigrationDialect.PostgreSql }) {
            assertFailsWith<IllegalArgumentException> { MigrationSqlGenerator.generate(dropUnique(null), dialect) }
        }
    }

    @Test
    fun escapesNamesAndAvoidsDollarDelimiterCollisions() {
        val plan = MigrationPlan.Changes(
            listOf(
                DropUnique(
                    CanonicalTableReference("odd'schema", "profiles\"\$kagiroi_unique\$"),
                    CanonicalUniqueConstraint(columns = listOf("employee'number")),
                ),
            ),
        )
        val sql = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.single().sql
        assertTrue(sql.startsWith("DO \$kagiroi_unique_\$"))
        assertTrue(sql.contains("'employee''number'"))
        assertTrue(sql.contains("\"odd'schema\".\"profiles\"\"\$kagiroi_unique\$\""))
        assertEquals(sql, MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.single().sql)
    }

    private fun dropUnique(name: String?): MigrationPlan = MigrationPlan.Changes(
        listOf(
            DropUnique(
                CanonicalTableReference("app", "profiles"),
                CanonicalUniqueConstraint(name, listOf("organization_id", "employee_number")),
            ),
        ),
    )
}
