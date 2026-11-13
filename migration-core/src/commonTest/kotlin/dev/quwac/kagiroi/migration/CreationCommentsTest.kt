package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CreationCommentsTest {
    @Test
    fun createsTableAndCommentsInOneArtifactWithoutChangingTheRecordedSchema() {
        for (dialect in listOf(MigrationDialect.PostgreSql, MigrationDialect.Oracle)) {
            val desired = schema(commentedTable())
            val plan = diffSchema(emptyCanonicalSchema(), desired)
            val generation = MigrationSqlGenerator.generate(plan, dialect)
            assertEquals(
                listOf(
                    "CREATE TABLE \"app\".\"profiles\"\"archive\" (\"id\" INTEGER NOT NULL, \"name\" TEXT)",
                    "COMMENT ON TABLE \"app\".\"profiles\"\"archive\" IS 'Customer''s profile'",
                    "COMMENT ON COLUMN \"app\".\"profiles\"\"archive\".\"id\" IS '組織ID'",
                    "COMMENT ON COLUMN \"app\".\"profiles\"\"archive\".\"name\" IS 'Employee''s name'",
                ).let { statements ->
                    if (dialect == MigrationDialect.Oracle) {
                        statements.map { it.replace("INTEGER", "NUMBER(10)").replace("TEXT", "VARCHAR2(4000)") }
                    } else {
                        statements
                    }
                },
                generation.statements.map { it.sql },
            )
            val script = generateMigrationScript(plan, dialect)
            val artifact = createMigrationArtifact(script.body, dialect, plan)
            val parsed = parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body))
            assertEquals(commentedTable(), assertIs<CreateTable>(parsed.metadata.schemaDelta.operations.single()).table)
            assertEquals(desired.schema, replaySchemaDeltas(listOf(parsed.metadata.schemaDelta)).schema)
            verifyMigrationArtifacts(
                listOf(NamedMigrationArtifact("20261113090000_initial.sql", parsed)),
                desired,
                dialect,
            )
        }
    }

    @Test
    fun emitsCommentsForAddedColumnsAfterTheirAddition() {
        val initial = schema(
            CanonicalTable(
                name = "profiles",
                columns = listOf(
                    CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), false),
                ),
            ),
        )
        val nickname = CanonicalColumn(
            "nickname",
            CanonicalColumnType("kagiroi.string"),
            true,
            comment = "User's nickname",
        )
        val existing = initial.schema.tables.single()
        val desired = schema(existing.copy(columns = existing.columns + nickname))
        val plan = diffSchema(initial, desired)
        assertEquals(
            listOf(
                "ALTER TABLE \"profiles\" ADD COLUMN \"nickname\" TEXT",
                "COMMENT ON COLUMN \"profiles\".\"nickname\" IS 'User''s nickname'",
            ),
            MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.map { it.sql },
        )
        assertEquals(nickname, assertIs<AddColumn>(plan.operations.single()).column)
        assertEquals(
            desired.schema,
            replaySchemaDeltas(
                listOf(SchemaDelta(listOf(CreateTable(initial.schema.tables.single()))), SchemaDelta(plan.operations)),
            ).schema,
        )
    }

    @Test
    fun rejectsUnsupportedCommentDialects() {
        val plan = diffSchema(emptyCanonicalSchema(), schema(commentedTable()))
        val addition = MigrationPlan.Changes(
            listOf(
                AddColumn(
                    CanonicalTableReference(name = "profiles"),
                    commentedTable().columns.last(),
                ),
            ),
        )
        for (dialect in listOf(MigrationDialect.Sqlite, MigrationDialect.D1)) {
            assertFailsWith<MigrationSqlGenerationException> { MigrationSqlGenerator.generate(plan, dialect) }
            assertFailsWith<MigrationSqlGenerationException> { MigrationSqlGenerator.generate(addition, dialect) }
        }
        val unique = CanonicalUniqueConstraint("name_unique", listOf("name"), comment = "constraint comment")
        val unsupported = diffSchema(
            emptyCanonicalSchema(),
            schema(commentedTable().copy(uniqueConstraints = listOf(unique))),
        )
        for (dialect in listOf(MigrationDialect.Oracle, MigrationDialect.Sqlite, MigrationDialect.D1)) {
            assertFailsWith<MigrationSqlGenerationException> { MigrationSqlGenerator.generate(unsupported, dialect) }
        }
    }

    private fun commentedTable() = CanonicalTable(
        schemaName = "app",
        name = "profiles\"archive",
        comment = "Customer's profile",
        columns = listOf(
            CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), false, comment = "組織ID"),
            CanonicalColumn("name", CanonicalColumnType("kagiroi.string"), true, comment = "Employee's name"),
        ),
    )

    private fun schema(table: CanonicalTable) = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(table)))))
}
