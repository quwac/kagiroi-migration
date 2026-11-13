package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrossDialectCommentsTest {
    @Test
    fun mysqlAndMariaDbCreateCommentsInlineAndPreserveColumnAttributesOnChanges() {
        for (dialect in listOf(MigrationDialect.MySql, MigrationDialect.MariaDb)) {
            val initial = commentFixture(comment = "Owner's \\ 組織", objectComments = true)
            val creation = diffSchema(emptyCanonicalSchema(), initial)
            val statements = MigrationSqlGenerator.generate(creation, dialect).statements.map { it.sql }
            assertEquals(2, statements.size)
            assertTrue(statements[0].contains("DEFAULT 7 NOT NULL PRIMARY KEY COMMENT 'Owner''s \\\\ 組織'"))
            assertTrue(statements[0].contains("UNIQUE (`code`) COMMENT 'Unique comment'"))
            assertTrue(statements[0].endsWith("COMMENT = 'Owner''s \\\\ 組織'"))
            assertTrue(statements[1].endsWith("COMMENT 'Index comment'"))
            val after = commentFixture(comment = null, objectComments = true)
            val changes = diffSchema(initial, after)
            val column = assertIs<AlterColumnComment>(
                changes.operations.filterIsInstance<AlterColumnComment>().single(),
            )
            assertEquals(after.schema.tables.single().columns.single { it.name == "id" }, column.definition)
            assertTrue(
                MigrationSqlGenerator.generate(changes, dialect).statements.any {
                    it.sql == "ALTER TABLE `profiles` MODIFY COLUMN `id` INT DEFAULT 7 NOT NULL COMMENT ''"
                },
            )
            verifyCommentChain(initial, after, dialect)
        }
    }

    @Test
    fun mysqlRequiresCompleteColumnDefinitionAndReplayRejectsConflictingSnapshots() {
        val reference = CanonicalColumnReference(CanonicalTableReference(name = "profiles"), "id")
        assertFailsWith<MigrationSqlGenerationException> {
            MigrationSqlGenerator.generate(
                MigrationPlan.Changes(listOf(AlterColumnComment(reference, to = "new"))),
                MigrationDialect.MySql,
            )
        }
        val initial = commentFixture(comment = "old")
        val desired = commentFixture(comment = "new")
        val operation = diffSchema(initial, desired).operations.filterIsInstance<AlterColumnComment>().single()
        assertFailsWith<IllegalArgumentException> {
            replaySchemaDeltas(
                listOf(
                    SchemaDelta(diffSchema(emptyCanonicalSchema(), initial).operations),
                    SchemaDelta(listOf(operation.copy(definition = operation.definition!!.copy(nullable = true)))),
                ),
            )
        }
    }

    @Test
    fun sqlServerCommentsUseScopedBatchesAndReplayWithOwningTable() {
        val initial = commentFixture(comment = "Owner's 組織", objectComments = true)
        val plan = diffSchema(emptyCanonicalSchema(), initial)
        val script = generateMigrationScript(plan, MigrationDialect.SqlServer)
        assertTrue(script.body.startsWith("BEGIN TRANSACTION;"))
        val artifact = createMigrationArtifact(script.body, MigrationDialect.SqlServer, plan)
        assertEquals(
            8,
            MigrationScriptParser.parse(artifact.body, MigrationDialect.SqlServer).steps.single().executionUnits.size,
        )
        assertTrue(script.body.contains("EXEC sys.sp_executesql N'DECLARE @kagiroi_schema"))
        assertTrue(script.body.contains("sys.sp_updateextendedproperty"))
        assertTrue(script.body.contains("sys.sp_addextendedproperty"))
        val after = commentFixture(comment = null)
        val changes = diffSchema(initial, after)
        assertEquals(
            CanonicalTableReference(name = "profiles"),
            changes.operations.filterIsInstance<AlterIndexComment>().single().table,
        )
        assertTrue(
            MigrationSqlGenerator.generate(changes, MigrationDialect.SqlServer).statements.all {
                it.sql.contains("sys.sp_dropextendedproperty")
            },
        )
        verifyCommentChain(initial, after, MigrationDialect.SqlServer)
    }

    @Test
    fun oracleRemovesTableAndColumnCommentsUsingEmptyStrings() {
        val initial = commentFixture(comment = "old")
        val after = commentFixture(comment = null)
        val plan = diffSchema(initial, after)
        assertTrue(
            MigrationSqlGenerator.generate(plan, MigrationDialect.Oracle).statements.all { it.sql.endsWith(" IS ''") },
        )
        verifyCommentChain(initial, after, MigrationDialect.Oracle)
    }

    private fun verifyCommentChain(
        before: CanonicalDesiredSchema,
        after: CanonicalDesiredSchema,
        dialect: MigrationDialect,
    ) {
        var previous = emptyCanonicalSchema()
        val chain = listOf(before, after).mapIndexed { index, desired ->
            val plan = diffSchema(previous, desired)
            previous = desired
            val artifact = createMigrationArtifact(generateMigrationScript(plan, dialect).body, dialect, plan)
            NamedMigrationArtifact(
                "2026111309000$index.sql",
                parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)),
            )
        }
        assertEquals(after.schema, verifyMigrationArtifacts(chain, after, dialect).reconstructedSchema.schema)
    }
}

/** Integer keys keep the same native fixture valid across all comment-capable dialects. */
internal fun commentFixture(
    table: CanonicalTableReference = CanonicalTableReference(name = "profiles"),
    comment: String?,
    objectComments: Boolean = false,
): CanonicalDesiredSchema {
    val physical = CanonicalPhysicalSchema(
        tables = listOf(
            CanonicalTable(
                schemaName = table.schemaName,
                name = table.name,
                comment = comment,
                columns = listOf(
                    CanonicalColumn(
                        "id",
                        CanonicalColumnType("kagiroi.int32"),
                        false,
                        primaryKey = true,
                        default = CanonicalLiteralExpression(
                            CanonicalIntegerValue("7"),
                            CanonicalColumnType("kagiroi.int32"),
                        ),
                        comment = comment,
                    ),
                    CanonicalColumn("code", CanonicalColumnType("kagiroi.int32"), true),
                ),
                uniqueConstraints = listOf(
                    CanonicalUniqueConstraint(
                        "profiles_code_unique",
                        listOf("code"),
                        if (objectComments) "Unique comment" else null,
                    ),
                ),
            ),
        ),
        indexes = listOf(
            CanonicalIndex(
                name = "profiles_code_lookup",
                table = table,
                unique = false,
                keys = listOf(CanonicalColumnIndexKey(CanonicalColumnReference(table, "code"))),
                comment = if (objectComments) "Index comment" else null,
            ),
        ),
    )
    return CanonicalDesiredSchema(
        CANONICAL_SCHEMA_FORMAT_VERSION,
        physical,
        metadata = CanonicalSchemaMetadata(
            CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
        ),
    )
}
