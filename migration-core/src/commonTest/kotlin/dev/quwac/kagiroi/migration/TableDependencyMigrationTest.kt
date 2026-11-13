package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TableDependencyMigrationTest {
    @Test
    fun createsANewParentBeforeChangingAnExistingChild() {
        val child = table("a_child")
        val parent = table("z_parent")
        val desiredChild = child.copy(
            columns = child.columns + CanonicalColumn("parent_id", integer, nullable = true),
            foreignKeys = listOf(key("child_parent", "z_parent")),
        )
        val previous = schema(child)
        val desired = schema(desiredChild, parent)
        val plan = diffSchema(previous, desired)
        assertEquals("z_parent", assertIs<CreateTable>(plan.operations[0]).table.name)
        assertEquals("a_child", assertIs<AddColumn>(plan.operations[1]).table.name)
        assertIs<AddForeignKey>(plan.operations[2])
        assertReplays(previous, desired, plan)
        assertEquals(plan, diffSchema(previous, schema(parent, desiredChild)))
    }

    @Test
    fun preparesAnExistingParentsReferencedKeyBeforeCreatingItsChild() {
        val parent = table("z_parent")
        val referenced = parent.copy(
            columns = parent.columns + CanonicalColumn("code", integer, nullable = true),
            uniqueConstraints = listOf(CanonicalUniqueConstraint("parent_code", listOf("code"))),
        )
        val child = table("a_child").copy(
            columns = table("a_child").columns + CanonicalColumn("parent_id", integer, nullable = true),
            foreignKeys = listOf(key("child_parent", "z_parent").copy(targetColumns = listOf("code"))),
        )
        val previous = schema(parent)
        val desired = schema(child, referenced)
        val plan = diffSchema(previous, desired)
        assertIs<AddColumn>(plan.operations[0])
        assertIs<AddUnique>(plan.operations[1])
        assertEquals("a_child", assertIs<CreateTable>(plan.operations[2]).table.name)
        assertReplays(previous, desired, plan)
    }

    @Test
    fun dropsChildrenBeforeParents() {
        val parent = table("a_parent")
        val child = table("z_child").copy(
            foreignKeys = listOf(key("child_parent", "a_parent", source = "id")),
        )
        val previous = schema(parent, child)
        val plan = diffSchema(previous, emptyCanonicalSchema())
        assertEquals(listOf("z_child", "a_parent"), plan.operations.filterIsInstance<DropTable>().map { it.table.name })
        assertReplays(previous, emptyCanonicalSchema(), plan)
    }

    @Test
    fun defersCyclicForeignKeysUntilTheirTargetsExistAndRemovesThemBeforeDrop() {
        val first = table("a").copy(foreignKeys = listOf(key("a_b", "b", source = "id")))
        val second = table("b").copy(foreignKeys = listOf(key("b_a", "a", source = "id")))
        val desired = schema(first, second)
        val plan = diffSchema(emptyCanonicalSchema(), desired)
        val creates = plan.operations.filterIsInstance<CreateTable>()
        assertEquals(listOf("b", "a"), creates.map { it.table.name })
        assertTrue(creates.first().table.foreignKeys.isEmpty())
        assertEquals("b_a", assertIs<AddForeignKey>(plan.operations.last()).foreignKey.name)
        assertReplays(emptyCanonicalSchema(), desired, plan)
        assertEquals(plan, diffSchema(emptyCanonicalSchema(), schema(second, first)))
        for (dialect in listOf(MigrationDialect.Sqlite, MigrationDialect.D1)) {
            val ddl = MigrationSqlGenerator.generate(plan, dialect).ddl
            assertEquals(2, ddl.size)
            assertTrue(ddl.all { it is MigrationDdlStatement.CreateTable && it.table.foreignKeys.size == 1 })
            val script = generateMigrationScript(plan, dialect)
            assertEquals(false, script.body.contains("ALTER TABLE"))
            val artifact = createMigrationArtifact(script.body, dialect, plan)
            verifyMigrationArtifacts(listOf(NamedMigrationArtifact("20261113090000.sql", artifact)), desired, dialect)
        }

        val drop = diffSchema(desired, emptyCanonicalSchema())
        assertEquals("b_a", assertIs<DropForeignKey>(drop.operations.first()).foreignKey.name)
        assertEquals(listOf("a", "b"), drop.operations.filterIsInstance<DropTable>().map { it.table.name })
        assertReplays(desired, emptyCanonicalSchema(), drop)
    }

    @Test
    fun keepsSelfReferencesInlineAndDistinguishesSchemas() {
        val parent = table("nodes").copy(schemaName = "z")
        val child = table("nodes").copy(
            schemaName = "a",
            foreignKeys = listOf(
                key("parent", "nodes", source = "id").copy(targetTable = CanonicalTableReference("z", "nodes")),
                key("self", "nodes", source = "id").copy(targetTable = CanonicalTableReference("a", "nodes")),
            ),
        )
        val plan = diffSchema(emptyCanonicalSchema(), schema(child, parent))
        assertEquals(listOf("z", "a"), plan.operations.filterIsInstance<CreateTable>().map { it.table.schemaName })
        assertEquals(2, assertIs<CreateTable>(plan.operations.last()).table.foreignKeys.size)
        assertReplays(emptyCanonicalSchema(), schema(child, parent), plan)
    }

    private fun assertReplays(previous: CanonicalDesiredSchema, desired: CanonicalDesiredSchema, plan: MigrationPlan) {
        val initial = diffSchema(emptyCanonicalSchema(), previous)
        assertEquals(
            encodePhysicalSchema(desired.schema),
            encodePhysicalSchema(
                replaySchemaDeltas(listOf(SchemaDelta(initial.operations), SchemaDelta(plan.operations))).schema,
            ),
        )
    }

    private fun table(name: String) = CanonicalTable(
        name = name,
        columns = listOf(CanonicalColumn("id", integer, nullable = false, primaryKey = true)),
    )

    private fun key(name: String, target: String, source: String = "parent_id") = CanonicalForeignKey(
        name = name,
        sourceColumns = listOf(source),
        targetTable = CanonicalTableReference(name = target),
        targetColumns = listOf("id"),
    )

    private fun schema(vararg tables: CanonicalTable): CanonicalDesiredSchema {
        val physical = CanonicalPhysicalSchema(tables = tables.toList())
        return CanonicalDesiredSchema(
            CANONICAL_SCHEMA_FORMAT_VERSION,
            physical,
            metadata = CanonicalSchemaMetadata(
                CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
            ),
        )
    }

    private val integer = CanonicalColumnType("kagiroi.int32")
}
