package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConstraintDeclarationVerificationTest {
    private val id = CanonicalColumn("id", CanonicalColumnType("kagiroi.int64"), nullable = false)
    private val other = id.copy(name = "other")
    private val tableKey = CanonicalTable(
        name = "records",
        columns = listOf(id, other),
        primaryKey = CanonicalPrimaryKey(columns = listOf("id")),
    )

    @Test
    fun acceptsEitherPrimaryKeyDeclarationAcrossAllDialectsWithoutChangingArtifacts() {
        for (dialect in MigrationDialect.entries) {
            for (unique in listOf(false, true)) {
                val inline = tableKey.copy(
                    columns = listOf(id.copy(primaryKey = true, unique = unique), other),
                    primaryKey = null,
                )
                for ((previous, desired) in listOf(tableKey to inline, inline to tableKey)) {
                    val initial = artifact(
                        "20261118000000.sql",
                        MigrationPlan.Changes(listOf(CreateTable(previous))),
                        dialect,
                    )
                    val encoded = writeMigrationArtifact(initial.artifact.metadata, initial.artifact.body)
                    val schema = schema(desired)
                    val canonical = encodeCanonicalSchema(schema)
                    assertEquals(MigrationPlan.NoChanges, diffSchema(schema(previous), schema))
                    val verified = verifyMigrationArtifacts(listOf(initial), schema, dialect)
                    assertEquals(previous, verified.reconstructedSchema.schema.tables.single())
                    assertEquals(encoded, writeMigrationArtifact(initial.artifact.metadata, initial.artifact.body))
                    assertEquals(canonical, encodeCanonicalSchema(schema))
                    assertEquals(initial.artifact, parseMigrationArtifact(encoded))
                    assertNotEquals(physicalSchemaHash(schema(previous)), physicalSchemaHash(schema))
                }
            }
        }
    }

    @Test
    fun ignoresTheImplicitUniqueFlagOfAnInlinePrimaryKey() {
        val previous = tableKey.copy(columns = listOf(id.copy(primaryKey = true), other), primaryKey = null)
        val desired = previous.copy(columns = listOf(id.copy(primaryKey = true, unique = true), other))
        assertEquals(MigrationPlan.NoChanges, diffSchema(schema(previous), schema(desired)))
        verifyMigrationArtifacts(
            listOf(artifact("20261118000000.sql", MigrationPlan.Changes(listOf(CreateTable(previous))))),
            schema(desired),
        )
    }

    @Test
    fun preservesExplicitUniqueConstraintsEvenOnPrimaryKeyColumns() {
        val previous = tableKey.copy(
            columns = listOf(id.copy(primaryKey = true, unique = true), other),
            primaryKey = null,
        )
        val unique = CanonicalUniqueConstraint(columns = listOf("id"))
        val desired = previous.copy(uniqueConstraints = listOf(unique))
        val plan = diffSchema(schema(previous), schema(desired))
        assertEquals(listOf(AddUnique(CanonicalTableReference(name = "records"), unique)), plan.operations)
        val initial = artifact("20261118000000.sql", MigrationPlan.Changes(listOf(CreateTable(previous))))
        assertFailsWith<IllegalArgumentException> { verifyMigrationArtifacts(listOf(initial), schema(desired)) }
        verifyMigrationArtifacts(listOf(initial, artifact("20261118010000.sql", plan)), schema(desired))
    }

    @Test
    fun preservesUniqueRestrictionsOnMembersOfCompositePrimaryKeys() {
        val previous = tableKey.copy(primaryKey = CanonicalPrimaryKey(columns = listOf("id", "other")))
        val desired = previous.copy(columns = listOf(id.copy(unique = true), other))
        assertTrue(diffSchema(schema(previous), schema(desired)).operations.single() is AddUnique)
        val initial = artifact("20261118000000.sql", MigrationPlan.Changes(listOf(CreateTable(previous))))
        assertFailsWith<IllegalArgumentException> { verifyMigrationArtifacts(listOf(initial), schema(desired)) }
    }

    @Test
    fun stillRejectsDifferentNamesColumnOrderNullabilityAndTypes() {
        val initial = artifact("20261118000000.sql", MigrationPlan.Changes(listOf(CreateTable(tableKey))))
        val variants = listOf(
            tableKey.copy(primaryKey = CanonicalPrimaryKey("records_key", listOf("id"))),
            tableKey.copy(primaryKey = CanonicalPrimaryKey(columns = listOf("other"))),
            tableKey.copy(primaryKey = null),
            tableKey.copy(columns = listOf(id.copy(nullable = true), other)),
            tableKey.copy(columns = listOf(id.copy(type = CanonicalColumnType("kagiroi.string")), other)),
        )
        variants.forEach { desired ->
            assertFailsWith<IllegalArgumentException> { verifyMigrationArtifacts(listOf(initial), schema(desired)) }
            assertTrue(diffSchema(schema(tableKey), schema(desired)).operations.isNotEmpty())
        }
        val composite = tableKey.copy(primaryKey = CanonicalPrimaryKey(columns = listOf("id", "other")))
        val reversed = composite.copy(primaryKey = CanonicalPrimaryKey(columns = listOf("other", "id")))
        assertFailsWith<IllegalArgumentException> {
            verifyMigrationArtifacts(
                listOf(artifact("20261118000000.sql", MigrationPlan.Changes(listOf(CreateTable(composite))))),
                schema(reversed),
            )
        }
    }

    @Test
    fun verifiesAddingAPrimaryKeyWithoutGeneratingAnAdditionalUniqueConstraint() {
        val previous = tableKey.copy(primaryKey = null)
        val desired = previous.copy(columns = listOf(id.copy(primaryKey = true, unique = true), other))
        val plan = diffSchema(schema(previous), schema(desired))
        assertEquals(
            listOf(
                AddPrimaryKey(CanonicalTableReference(name = "records"), CanonicalPrimaryKey(columns = listOf("id"))),
            ),
            plan.operations,
        )
        verifyMigrationArtifacts(
            listOf(
                artifact("20261118000000.sql", MigrationPlan.Changes(listOf(CreateTable(previous)))),
                artifact("20261118010000.sql", plan),
            ),
            schema(desired),
        )
    }

    @Test
    fun acceptsEquivalentUniqueDeclarationsButPreservesTheirNamesAndComments() {
        val previous = tableKey.copy(columns = listOf(id, other.copy(unique = true)))
        val unique = CanonicalUniqueConstraint(columns = listOf("other"))
        val desired = tableKey.copy(uniqueConstraints = listOf(unique))
        val initial = artifact("20261118000000.sql", MigrationPlan.Changes(listOf(CreateTable(previous))))
        assertEquals(MigrationPlan.NoChanges, diffSchema(schema(previous), schema(desired)))
        verifyMigrationArtifacts(listOf(initial), schema(desired))
        for (changed in listOf(unique.copy(name = "other_key"), unique.copy(comment = "Explicit comment"))) {
            assertFailsWith<IllegalArgumentException> {
                verifyMigrationArtifacts(listOf(initial), schema(desired.copy(uniqueConstraints = listOf(changed))))
            }
        }
    }

    private fun artifact(
        filename: String,
        plan: MigrationPlan,
        dialect: MigrationDialect = MigrationDialect.PostgreSql,
    ): NamedMigrationArtifact {
        val body = generateMigrationScript(plan, dialect).body
        return NamedMigrationArtifact(filename, createMigrationArtifact(body, dialect, plan))
    }

    private fun schema(table: CanonicalTable): CanonicalDesiredSchema =
        replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(table)))))
}
