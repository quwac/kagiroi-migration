package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class LocalDateMigrationTest {
    @Test
    fun createsAndAddsLocalDatesWithNativePhysicalTypesAcrossAllDialects() {
        val date = CanonicalColumn("work_date", CanonicalColumnType("kagiroi.kotlinx.localDate"), false)
        val table = CanonicalTable(name = "work_days", columns = listOf(date))
        for (dialect in MigrationDialect.entries) {
            val physicalType = when (dialect) {
                MigrationDialect.Sqlite, MigrationDialect.D1 -> "TEXT"
                else -> "DATE"
            }
            val created = generateMigrationScript(MigrationPlan.Changes(listOf(CreateTable(table))), dialect)
            assertContains(created.body, physicalType)
            val added = generateMigrationScript(
                MigrationPlan.Changes(
                    listOf(AddColumn(CanonicalTableReference(null, table.name), date.copy(nullable = true))),
                ),
                dialect,
            )
            assertContains(added.body, physicalType)
        }
    }

    @Test
    fun preservesLocalDateMetadataAndReplaysTheGeneratedArtifactWithoutAnotherDiff() {
        val table = CanonicalTable(
            name = "work_days",
            columns = listOf(CanonicalColumn("work_date", CanonicalColumnType("kagiroi.kotlinx.localDate"), false)),
        )
        val desired = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(table)))))
        assertEquals(desired, decodeCanonicalSchema(encodeCanonicalSchema(desired)))
        for (dialect in MigrationDialect.entries) {
            val plan = diffSchema(emptyCanonicalSchema(), desired)
            val script = generateMigrationScript(plan, dialect)
            val artifact = createMigrationArtifact(script.body, dialect, plan)
            val parsed = parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body))
            assertEquals(artifact, parsed)
            val replayed = replaySchemaDeltas(listOf(parsed.metadata.schemaDelta))
            assertEquals(MigrationPlan.NoChanges, diffSchema(replayed, desired))
        }
    }
}
