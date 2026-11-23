package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals

class DropTableReplayTest {
    @Test
    fun tableDropRemovesOnlyIndexesOwnedByTheExactTable() {
        val removed = table("items", "business")
        val retained = table("items", "audit")
        val removedIndex = index(removed, "items_id")
        val retainedIndex = index(retained, "items_id")
        val replayed = replaySchemaDeltas(
            listOf(
                SchemaDelta(
                    listOf(
                        CreateTable(removed),
                        CreateIndex(removedIndex),
                        CreateTable(retained),
                        CreateIndex(retainedIndex),
                    ),
                ),
                SchemaDelta(listOf(DropTable(removed.reference()))),
            ),
        )
        assertEquals(listOf(retained), replayed.schema.tables)
        assertEquals(listOf(retainedIndex), replayed.schema.indexes)
    }

    @Test
    fun droppedIndexedTableDoesNotProduceAnotherDeltaForAnyDialect() {
        val table = table("items")
        val initial = SchemaDelta(listOf(CreateTable(table), CreateIndex(index(table, "items_id"))))
        val plan = MigrationPlan.Changes(listOf(DropTable(table.reference())))
        MigrationDialect.entries.forEach { dialect ->
            val sql = generateMigrationScript(plan, dialect)
            val artifact = createMigrationArtifact(sql.body, dialect, plan)
            val parsed = parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body))
            val replayed = replaySchemaDeltas(listOf(initial, parsed.metadata.schemaDelta))
            assertEquals(emptyList(), replayed.schema.indexes, dialect.id)
            assertEquals(MigrationPlan.NoChanges, diffSchema(replayed, emptyCanonicalSchema()), dialect.id)
        }
    }

    private fun CanonicalTable.reference() = CanonicalTableReference(schemaName, name)

    private fun table(name: String, schema: String? = null) = CanonicalTable(
        schemaName = schema,
        name = name,
        columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int64"), nullable = false)),
    )
    private fun index(table: CanonicalTable, name: String) = CanonicalIndex(
        name = name,
        table = table.reference(),
        unique = false,
        keys = listOf(CanonicalColumnIndexKey(CanonicalColumnReference(table.reference(), "id"))),
    )
}
