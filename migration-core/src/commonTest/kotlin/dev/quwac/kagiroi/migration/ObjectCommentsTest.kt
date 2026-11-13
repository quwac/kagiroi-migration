package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObjectCommentsTest {
    @Test
    fun createsNamedUniqueAndIndexCommentsInTheirCreationArtifact() {
        val desired = schema("Owner's unique", "Owner's index", indexSchema = null)
        val plan = diffSchema(emptyCanonicalSchema(), desired)
        val statements = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.map { it.sql }
        assertEquals(4, statements.size)
        assertEquals(
            "COMMENT ON CONSTRAINT \"uq\"\"email\" ON \"app\".\"profiles\" IS 'Owner''s unique'",
            statements[1],
        )
        assertEquals("COMMENT ON INDEX \"app\".\"email\"\"lookup\" IS 'Owner''s index'", statements[3])
        verifyChain(emptyCanonicalSchema(), desired, plan)
    }

    @Test
    fun changesAndRemovesCommentsWithoutDroppingConstraintsOrIndexes() {
        val before = schema("Old unique", "Old index")
        for (after in listOf(schema("New unique", "New index"), schema())) {
            val plan = diffSchema(before, after)
            assertEquals(listOf(AlterUniqueComment::class, AlterIndexComment::class), plan.operations.map { it::class })
            assertTrue(plan.operations.all { it.risk.level == MigrationRisk.Safe })
            val statements = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.map { it.sql }
            assertTrue(statements.all { it.startsWith("COMMENT ON ") })
            if (after.schema.indexes.single().comment == null) assertTrue(statements.all { it.endsWith(" IS NULL") })
            verifyChain(before, after, plan)
        }
    }

    @Test
    fun changesAnIndexCommentAfterRenamingWithoutRebuildingIt() {
        val before = schema(indexComment = "Old index")
        val after = schema(indexComment = "New index", indexName = "renamed").copy(
            evolution = CanonicalEvolution(
                indexRenames = listOf(
                    CanonicalIndexRename(CanonicalIndexReference("app", "renamed"), listOf("email\"lookup")),
                ),
            ),
        )
        val plan = diffSchema(before, after)
        assertIs<RenameIndex>(plan.operations[0])
        assertEquals(CanonicalIndexReference("app", "renamed"), assertIs<AlterIndexComment>(plan.operations[1]).index)
        val statements = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.map { it.sql }
        assertEquals("COMMENT ON INDEX \"app\".\"renamed\" IS 'New index'", statements.last())
        verifyChain(before, after, plan)
    }

    @Test
    fun preservesCommentsWhenStructuralIndexChangesRequireRecreation() {
        val before = schema(indexComment = "Old index")
        val physical = schema(indexComment = "New index").schema.let { schema ->
            schema.copy(indexes = listOf(schema.indexes.single().copy(unique = true)))
        }
        val after = document(physical)
        val plan = diffSchema(before, after)
        assertIs<RecreateIndex>(plan.operations.single())
        val statements = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.map { it.sql }
        assertEquals(3, statements.size)
        assertTrue(statements.last().startsWith("COMMENT ON INDEX"))
        verifyChain(before, after, plan)
    }

    @Test
    fun readsLegacyColumnAndExpressionIndexKeys() {
        val initial = schema("Unique", "Index")
        val index = initial.schema.indexes.single()
        val physical = initial.schema.copy(
            indexes = listOf(
                index.copy(
                    keys = index.keys + CanonicalExpressionIndexKey(
                        CanonicalColumnExpression(CanonicalColumnReference(index.table, "email")),
                    ),
                ),
            ),
        )
        val plan = diffSchema(emptyCanonicalSchema(), document(physical))
        val artifact = createMigrationArtifact(
            generateMigrationScript(plan, MigrationDialect.PostgreSql).body,
            MigrationDialect.PostgreSql,
            plan,
        )
        val legacy = writeMigrationArtifact(artifact.metadata, artifact.body)
            .replace("\"kind\": \"column\"", "\"type\": \"column\"")
            .replace("\"kind\": \"expression\"", "\"type\": \"expression\"")
        assertEquals(artifact, parseMigrationArtifact(legacy))
    }

    @Test
    fun addsACommentedUniqueConstraintToAnExistingTable() {
        val after = schema("New unique")
        val before = document(
            after.schema.copy(tables = after.schema.tables.map { it.copy(uniqueConstraints = emptyList()) }),
        )
        val plan = diffSchema(before, after)
        assertIs<AddUnique>(plan.operations.single())
        val sql = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.map { it.sql }
        assertEquals(2, sql.size)
        assertTrue(sql.last().startsWith("COMMENT ON CONSTRAINT"))
        verifyChain(before, after, plan)
    }

    @Test
    fun rejectsAnIndexSchemaDifferentFromItsOwningPostgresTable() {
        val index = schema(indexSchema = "other").schema.indexes.single()
        assertFailsWith<MigrationSqlGenerationException> {
            MigrationSqlGenerator.generate(
                MigrationPlan.Changes(listOf(CreateIndex(index))),
                MigrationDialect.PostgreSql,
            )
        }
    }

    @Test
    fun rejectsUnsupportedNativeCommentStatements() {
        val operations = diffSchema(schema(), schema("Unique", "Index")).operations
        for (dialect in MigrationDialect.entries - setOf(MigrationDialect.PostgreSql, MigrationDialect.SqlServer)) {
            for (operation in operations) {
                assertFailsWith<MigrationSqlGenerationException> {
                    MigrationSqlGenerator.generate(MigrationPlan.Changes(listOf(operation)), dialect)
                }
            }
        }
    }

    private fun verifyChain(before: CanonicalDesiredSchema, after: CanonicalDesiredSchema, plan: MigrationPlan) {
        fun artifact(plan: MigrationPlan): MigrationArtifact {
            val artifact = createMigrationArtifact(
                generateMigrationScript(plan, MigrationDialect.PostgreSql).body,
                MigrationDialect.PostgreSql,
                plan,
            )
            return parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body))
        }
        val initial = diffSchema(emptyCanonicalSchema(), before)
        val chain = buildList {
            if (initial.operations.isNotEmpty()) add(NamedMigrationArtifact("20261113090000.sql", artifact(initial)))
            add(NamedMigrationArtifact("20261113090001.sql", artifact(plan)))
        }
        val verified = verifyMigrationArtifacts(chain, after, MigrationDialect.PostgreSql)
        assertEquals(physicalSchemaHash(after), physicalSchemaHash(verified.reconstructedSchema))
    }

    private fun schema(
        uniqueComment: String? = null,
        indexComment: String? = null,
        indexName: String = "email\"lookup",
        indexSchema: String? = "app",
    ): CanonicalDesiredSchema {
        val reference = CanonicalTableReference("app", "profiles")
        val table = CanonicalTable(
            schemaName = reference.schemaName,
            name = reference.name,
            columns = listOf(CanonicalColumn("email", CanonicalColumnType("kagiroi.string"), nullable = false)),
            uniqueConstraints = listOf(CanonicalUniqueConstraint("uq\"email", listOf("email"), uniqueComment)),
        )
        val index = CanonicalIndex(
            schemaName = indexSchema,
            name = indexName,
            table = reference,
            unique = false,
            keys = listOf(CanonicalColumnIndexKey(CanonicalColumnReference(reference, "email"))),
            comment = indexComment,
        )
        return document(CanonicalPhysicalSchema(tables = listOf(table), indexes = listOf(index)))
    }

    private fun document(physical: CanonicalPhysicalSchema) = CanonicalDesiredSchema(
        CANONICAL_SCHEMA_FORMAT_VERSION,
        physical,
        metadata = CanonicalSchemaMetadata(
            CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
        ),
    )
}
