package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ColumnOrderVerificationTest {
    private val id = CanonicalColumn("id", CanonicalColumnType("kagiroi.int64"), nullable = false)
    private val createdAt = CanonicalColumn("created_at", CanonicalColumnType("kagiroi.instant"), nullable = false)
    private val authUserId = CanonicalColumn("auth_user_id", CanonicalColumnType("kagiroi.uuid"), nullable = true)
    private val original = CanonicalTable(name = "users", columns = listOf(id, createdAt))

    @Test
    fun verifiesAnAddedColumnDeclaredBetweenExistingColumnsWithoutChangingHistoryHashes() {
        val desired = schema(original.copy(columns = listOf(id, authUserId, createdAt)))
        val initial = artifact("20261101090000.sql", MigrationPlan.Changes(listOf(CreateTable(original))))
        val addition = artifact("20261101100000.sql", diffSchema(schema(original), desired))
        val encoded = writeMigrationArtifact(initial.artifact.metadata, initial.artifact.body)
        val result = verifyMigrationArtifacts(listOf(initial, addition), desired, MigrationDialect.PostgreSql)
        assertEquals(
            listOf("id", "created_at", "auth_user_id"),
            result.reconstructedSchema.schema.tables.single().columns.map { it.name },
        )
        assertEquals(initial.artifact, parseMigrationArtifact(encoded))
        assertEquals(desired, decodeCanonicalSchema(encodeCanonicalSchema(desired)))
        assertNotEquals(physicalSchemaHash(desired), physicalSchemaHash(result.reconstructedSchema))
        assertEquals(MigrationPlan.NoChanges, diffSchema(result.reconstructedSchema, desired))
    }

    @Test
    fun verifiesColumnReorderingWithoutGeneratingADatabaseChange() {
        val reordered = schema(original.copy(columns = original.columns.reversed()))
        val initial = artifact("20261101090000.sql", MigrationPlan.Changes(listOf(CreateTable(original))))
        assertEquals(MigrationPlan.NoChanges, diffSchema(schema(original), reordered))
        verifyMigrationArtifacts(listOf(initial), reordered)
    }

    @Test
    fun retainsDeclaredColumnOrderForNewTables() {
        val table = original.copy(columns = listOf(id, authUserId, createdAt))
        val body = generateMigrationScript(
            MigrationPlan.Changes(listOf(CreateTable(table))),
            MigrationDialect.PostgreSql,
        ).body
        assertTrue(body.indexOf("\"id\"") < body.indexOf("\"auth_user_id\""))
        assertTrue(body.indexOf("\"auth_user_id\"") < body.indexOf("\"created_at\""))
    }

    @Test
    fun stillRejectsDifferentColumnsTypesNullabilityAndConstraints() {
        val initial = artifact("20261101090000.sql", MigrationPlan.Changes(listOf(CreateTable(original))))
        val different = listOf(
            original.copy(columns = listOf(createdAt, id.copy(type = CanonicalColumnType("kagiroi.string")))),
            original.copy(columns = listOf(createdAt, id.copy(nullable = true))),
            original.copy(columns = listOf(createdAt, id.copy(name = "other_id"))),
            original.copy(columns = listOf(createdAt)),
            original.copy(columns = listOf(createdAt, id), primaryKey = CanonicalPrimaryKey(columns = listOf("id"))),
        )
        different.forEach { table ->
            assertFailsWith<IllegalArgumentException> { verifyMigrationArtifacts(listOf(initial), schema(table)) }
        }
    }

    @Test
    fun stillRejectsReorderingInsideACompositeKey() {
        val table = original.copy(primaryKey = CanonicalPrimaryKey(columns = listOf("id", "created_at")))
        val initial = artifact("20261101090000.sql", MigrationPlan.Changes(listOf(CreateTable(table))))
        val desired = table.copy(
            columns = table.columns.reversed(),
            primaryKey = CanonicalPrimaryKey(columns = listOf("created_at", "id")),
        )
        assertFailsWith<IllegalArgumentException> { verifyMigrationArtifacts(listOf(initial), schema(desired)) }
    }

    private fun artifact(filename: String, plan: MigrationPlan): NamedMigrationArtifact {
        val body = generateMigrationScript(plan, MigrationDialect.PostgreSql).body
        return NamedMigrationArtifact(filename, createMigrationArtifact(body, MigrationDialect.PostgreSql, plan))
    }

    private fun schema(table: CanonicalTable): CanonicalDesiredSchema {
        val physical = CanonicalPhysicalSchema(tables = listOf(table))
        return CanonicalDesiredSchema(
            formatVersion = CANONICAL_SCHEMA_FORMAT_VERSION,
            schema = physical,
            metadata = CanonicalSchemaMetadata(
                CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
            ),
        )
    }
}
