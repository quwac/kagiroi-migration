package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresUnnamedConstraintTest {
    @Test
    fun retainsForeignKeyIdentityUntilRenderingAndUsesAllOrderedColumnsAndActions() {
        val table = CanonicalTableReference("tenant", "child")
        val key = CanonicalForeignKey(
            sourceColumns = listOf("organization_id", "user_id"),
            targetTable = CanonicalTableReference("tenant", "memberships"),
            targetColumns = listOf("organization_id", "user_id"),
            onDelete = CanonicalForeignKeyAction.Cascade,
        )
        val plan = MigrationPlan.Changes(listOf(DropForeignKey(table, key)))
        assertEquals(key, (plan.toDdlStatements().single() as MigrationDdlStatement.DropForeignKey).foreignKey)
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        assertTrue(script.body.contains("c.confrelid = '\"tenant\".\"memberships\"'::pg_catalog.regclass"))
        assertTrue(script.body.contains("c.confdeltype = 'c'"))
        assertTrue(script.body.contains("c.confupdtype = 'a'"))
        assertTrue(script.body.contains("ARRAY['organization_id', 'user_id']::text[]"))
        assertTrue(script.body.contains("ORDER BY k.position"))
        assertTrue(script.body.contains("LOCK TABLE \"tenant\".\"child\" IN ACCESS EXCLUSIVE MODE"))
        assertTrue(script.body.contains("<> 1 THEN"))
        assertFailsWith<IllegalArgumentException> { generateMigrationScript(plan, MigrationDialect.MySql) }
    }

    @Test
    fun resolvesPrimaryKeysAtExecutionTimeAndKeepsExplicitNamesAsDirectDdl() {
        val table = CanonicalTableReference(name = "child")
        val unnamed = generateMigrationScript(
            MigrationPlan.Changes(listOf(DropPrimaryKey(table))),
            MigrationDialect.PostgreSql,
        )
        assertTrue(unnamed.body.contains("c.contype = 'p'"))
        assertTrue(unnamed.body.contains("Expected exactly one PRIMARY KEY"))
        val named = generateMigrationScript(
            MigrationPlan.Changes(listOf(DropPrimaryKey(table, "custom_pk"))),
            MigrationDialect.PostgreSql,
        )
        assertTrue(named.body.contains("ALTER TABLE \"child\" DROP CONSTRAINT \"custom_pk\";"))
    }

    @Test
    fun verificationIgnoresForeignKeyDeclarationOrderButRejectsDifferentTargets() {
        val parent = CanonicalTable(
            name = "parent",
            columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), false)),
        )
        val first = CanonicalForeignKey(
            sourceColumns = listOf("first"),
            targetTable = CanonicalTableReference(name = "parent"),
            targetColumns = listOf("id"),
        )
        val second = first.copy(sourceColumns = listOf("second"))
        val child = CanonicalTable(
            name = "child",
            columns = listOf(
                "first",
                "second",
            ).map { CanonicalColumn(it, CanonicalColumnType("kagiroi.int32"), false) },
            foreignKeys = listOf(first, second),
        )
        val plan = MigrationPlan.Changes(listOf(CreateTable(parent), CreateTable(child)))
        val artifact = createMigrationArtifact(
            generateMigrationScript(plan, MigrationDialect.PostgreSql).body,
            MigrationDialect.PostgreSql,
            plan,
        )
        val named = NamedMigrationArtifact("20261115000000.sql", artifact)
        fun desired(keys: List<CanonicalForeignKey>) = replaySchemaDeltas(
            listOf(SchemaDelta(listOf(CreateTable(parent), CreateTable(child.copy(foreignKeys = keys))))),
        )
        verifyMigrationArtifacts(listOf(named), desired(listOf(second, first)))
        assertFailsWith<IllegalArgumentException> {
            verifyMigrationArtifacts(
                listOf(named),
                desired(listOf(second, first.copy(targetColumns = listOf("other")))),
            )
        }
    }
}
