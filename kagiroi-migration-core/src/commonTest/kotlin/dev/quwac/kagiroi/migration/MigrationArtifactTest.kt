package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MigrationArtifactTest {
    private val users = CanonicalTableReference(name = "users")
    private val name = CanonicalColumnReference(users, "name")
    private val displayName = CanonicalColumnReference(users, "display_name")

    @Test
    fun generatesPostgresRenameBeforeNotNullAsSeparateExecutableStatements() {
        val plan = MigrationPlan.Changes(
            listOf(
                RenameColumn(users, "name", "display_name"),
                SetNotNull(displayName),
            ),
        )

        val generated = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql)

        assertEquals(
            listOf(
                "ALTER TABLE \"users\" RENAME COLUMN \"name\" TO \"display_name\"",
                "ALTER TABLE \"users\" ALTER COLUMN \"display_name\" SET NOT NULL",
            ),
            generated.statements.map(MigrationSqlStatement::sql),
        )
    }

    @Test
    fun rejectsUnsupportedSqliteOperationWithoutRenderingAnyPartialArtifact() {
        val plan = MigrationPlan.Changes(listOf(SetNotNull(displayName)))

        val error = assertFailsWith<MigrationSqlGenerationException> {
            MigrationSqlGenerator.generate(plan, MigrationDialect.Sqlite)
        }

        assertEquals("sqlite", error.dialect)
        assertContains(error.message.orEmpty(), "table rebuild")
    }

    @Test
    fun rejectsSqliteAndD1ConstraintAlterationsInsteadOfSynthesizingATableRebuild() {
        val plan = MigrationPlan.Changes(
            listOf(
                CreateTable(
                    CanonicalTable(
                        name = "users",
                        columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int64"), false)),
                    ),
                ),
                AddPrimaryKey(users, CanonicalPrimaryKey("pk_users", listOf("id"))),
            ),
        )

        listOf(MigrationDialect.Sqlite, MigrationDialect.D1).forEach { dialect ->
            val error = assertFailsWith<MigrationSqlGenerationException> {
                MigrationSqlGenerator.generate(plan, dialect)
            }
            assertContains(error.message.orEmpty(), "table rebuild")
        }
    }

    @Test
    @Suppress("LongMethod")
    fun postgresRendersEverySupportedCanonicalDdlOperationAsRawSql() {
        val users = CanonicalTableReference(name = "users")
        val accounts = CanonicalTableReference(name = "accounts")
        val id = CanonicalColumnReference(users, "id")
        val name = CanonicalColumnReference(users, "name")
        val string = CanonicalColumnType("kagiroi.string")
        val int64 = CanonicalColumnType("kagiroi.int64")
        val primaryKey = CanonicalPrimaryKey("pk_users", listOf("id"))
        val unique = CanonicalUniqueConstraint("uq_users_name", listOf("name"))
        val foreignKey = CanonicalForeignKey("fk_users_account", listOf("id"), accounts, listOf("id"))
        val check = CanonicalCheckConstraint(
            "ck_users_id",
            CanonicalValueComparison(
                CanonicalColumnExpression(id),
                CanonicalComparisonOperator.Gte,
                CanonicalIntegerValue("0"),
                int64,
            ),
        )
        val index = CanonicalIndex(
            name = "users_name_idx",
            table = users,
            unique = false,
            keys = listOf(CanonicalColumnIndexKey(name)),
        )
        val renamedIndex = index.copy(name = "users_display_name_idx")
        val default = CanonicalLiteralExpression(CanonicalStringValue("guest"), string)
        val plan = MigrationPlan.Changes(
            listOf(
                CreateTable(
                    CanonicalTable(
                        name = "new_users",
                        columns = listOf(CanonicalColumn("id", int64, nullable = false)),
                    ),
                ),
                DropTable(CanonicalTableReference(name = "old_users")),
                RenameTable(
                    CanonicalTableReference(name = "old_name"),
                    CanonicalTableReference(name = "new_name"),
                ),
                AddColumn(users, CanonicalColumn("nickname", string, nullable = true)),
                DropColumn(name),
                RenameColumn(users, "name", "display_name"),
                AlterColumnType(id, int64, string),
                SetNotNull(id),
                DropNotNull(id),
                SetDefault(name, default),
                DropDefault(name, default),
                AddPrimaryKey(users, primaryKey),
                DropPrimaryKey(users, primaryKey),
                AddUnique(users, unique),
                DropUnique(users, unique),
                AddForeignKey(users, foreignKey),
                DropForeignKey(users, foreignKey),
                AddCheck(users, check),
                DropCheck(users, check),
                CreateIndex(index),
                DropIndex(index),
                RenameIndex(index, renamedIndex),
                CreateSequence(CanonicalSequence(name = "user_numbers")),
                DropSequence(CanonicalSequence(name = "old_numbers")),
            ),
        )

        val sql = MigrationSqlGenerator.generate(
            plan,
            MigrationDialect.PostgreSql,
        ).statements.joinToString("\n") { it.sql }

        listOf(
            "CREATE TABLE \"new_users\"",
            "DROP TABLE \"old_users\"",
            "RENAME TO \"new_name\"",
            "ADD COLUMN \"nickname\" TEXT",
            "DROP COLUMN \"name\"",
            "RENAME COLUMN \"name\" TO \"display_name\"",
            "ALTER COLUMN \"id\" TYPE TEXT",
            "SET NOT NULL",
            "DROP NOT NULL",
            "SET DEFAULT 'guest'",
            "DROP DEFAULT",
            "ADD CONSTRAINT \"pk_users\" PRIMARY KEY",
            "DROP CONSTRAINT \"pk_users\"",
            "ADD CONSTRAINT \"uq_users_name\" UNIQUE",
            "DROP CONSTRAINT \"uq_users_name\"",
            "ADD CONSTRAINT \"fk_users_account\" FOREIGN KEY",
            "DROP CONSTRAINT \"fk_users_account\"",
            "ADD CONSTRAINT \"ck_users_id\" CHECK",
            "DROP CONSTRAINT \"ck_users_id\"",
            "CREATE INDEX \"users_name_idx\"",
            "DROP INDEX \"users_name_idx\"",
            "ALTER INDEX \"users_name_idx\" RENAME TO \"users_display_name_idx\"",
            "CREATE SEQUENCE \"user_numbers\"",
            "DROP SEQUENCE \"old_numbers\"",
        ).forEach { expected -> assertContains(sql, expected) }
    }

    @Test
    fun everyDialectRendersNativeCreateTableAndMySqlRejectsSequences() {
        val plan = MigrationPlan.Changes(
            listOf(
                CreateTable(
                    CanonicalTable(
                        name = "users",
                        columns = listOf(CanonicalColumn("name", CanonicalColumnType("kagiroi.string"), true)),
                    ),
                ),
            ),
        )
        MigrationDialect.entries.forEach { dialect ->
            assertContains(MigrationSqlGenerator.generate(plan, dialect).statements.single().sql, "CREATE TABLE")
        }
        assertFailsWith<MigrationSqlGenerationException> {
            MigrationSqlGenerator.generate(
                MigrationPlan.Changes(listOf(CreateSequence(CanonicalSequence(name = "numbers")))),
                MigrationDialect.MySql,
            )
        }
    }

    @Test
    fun artifactRoundTripPreservesRawSqlBodyAndEscapesCommentTerminator() {
        val schema = schema(comment = "safe */ comment")
        val body = "ALTER TABLE \"users\" ADD COLUMN \"display_name\" TEXT;\n"
        val draft = MigrationArtifact(
            MigrationArtifactMetadata(
                id = "20261031101500_add_display_name",
                dialect = "postgresql",
                status = MigrationArtifactStatus.Draft,
                desiredSchemaHashAtGeneration = physicalSchemaHash(schema),
                risk = MigrationArtifactRisk(MigrationRisk.Review, reviewRequired = true),
            ),
            body,
        )

        val finalized = finalizeMigrationArtifact(draft, schema)
        val text = finalized.rewrite(finalized.metadata)
        val parsed = parseMigrationArtifact(text)

        assertContains(text, "*\\/")
        assertEquals(finalized.metadata, parsed.metadata)
        assertEquals(body, parsed.body)
        assertEquals(text, parsed.rewrite(parsed.metadata))
    }

    @Test
    fun finalizationAcceptsManualSqlButNeverParsesItAndDetectsLaterEdits() {
        val current = schema()
        val draft = MigrationArtifact(
            MigrationArtifactMetadata(
                id = "20261031101500_add_display_name",
                dialect = "postgresql",
                status = MigrationArtifactStatus.Draft,
                desiredSchemaHashAtGeneration = physicalSchemaHash(current),
                risk = MigrationArtifactRisk(MigrationRisk.Review, reviewRequired = true),
            ),
            "ALTER TABLE \"users\" ADD COLUMN \"display_name\" TEXT;\n\nUPDATE \"users\" SET \"display_name\" = \"name\";\n",
        )

        val finalized = finalizeMigrationArtifact(draft, current)

        assertEquals(MigrationArtifactStatus.Final, finalized.metadata.status)
        assertEquals(physicalSchemaHash(current), finalized.metadata.resultSchemaHash)
        assertEquals(current.schema, finalized.metadata.resultSchema)
        assertEquals(MigrationChecksumValidation.Valid, finalized.validateChecksum())
        assertIs<MigrationChecksumValidation.Mismatch>(
            finalized.copy(body = finalized.body + "-- edited\n").validateChecksum(),
        )
    }

    @Test
    fun finalizationRejectsAChangedSchema() {
        val generated = schema()
        val changed = schema(columnName = "other_name")
        val draft = MigrationArtifact(
            MigrationArtifactMetadata(
                id = "20261031101500_add_display_name",
                dialect = "postgresql",
                status = MigrationArtifactStatus.Draft,
                desiredSchemaHashAtGeneration = physicalSchemaHash(generated),
                risk = MigrationArtifactRisk(MigrationRisk.Safe, reviewRequired = false),
            ),
            "CREATE TABLE \"users\" (\"name\" TEXT);\n",
        )

        assertFailsWith<IllegalArgumentException> { finalizeMigrationArtifact(draft, changed) }
    }

    @Test
    fun validatesParentChainAndUsesFinalResultSchemaAsPreviousDocument() {
        val firstSchema = schema()
        val first = finalArtifact("20261030090000_init", null, firstSchema)
        val second = MigrationArtifact(
            MigrationArtifactMetadata(
                id = "20261031101500_add_display_name",
                parent = first.metadata.id,
                dialect = "postgresql",
                status = MigrationArtifactStatus.Draft,
                desiredSchemaHashAtGeneration = physicalSchemaHash(firstSchema),
                risk = MigrationArtifactRisk(MigrationRisk.Safe, reviewRequired = false),
            ),
            "ALTER TABLE \"users\" ADD COLUMN \"display_name\" TEXT;\n",
        )

        val chain = MigrationChain.discover(
            listOf(
                NamedMigrationArtifact("${second.metadata.id}.sql", second),
                NamedMigrationArtifact("${first.metadata.id}.sql", first),
            ),
        )

        assertEquals(listOf(first.metadata.id, second.metadata.id), chain.artifacts.map { it.artifact.metadata.id })
        assertEquals(firstSchema.schema, chain.latestFinalized?.artifact?.metadata?.resultSchemaDocument()?.schema)
    }

    private fun finalArtifact(id: String, parent: String?, current: CanonicalDesiredSchema): MigrationArtifact {
        val draft = MigrationArtifact(
            MigrationArtifactMetadata(
                id = id,
                parent = parent,
                dialect = "postgresql",
                status = MigrationArtifactStatus.Draft,
                desiredSchemaHashAtGeneration = physicalSchemaHash(current),
                risk = MigrationArtifactRisk(MigrationRisk.Safe, reviewRequired = false),
            ),
            "CREATE TABLE \"users\" (\"name\" TEXT);\n",
        )
        return finalizeMigrationArtifact(draft, current)
    }

    private fun schema(columnName: String = "name", comment: String? = null): CanonicalDesiredSchema {
        val physical = CanonicalPhysicalSchema(
            tables = listOf(
                CanonicalTable(
                    name = "users",
                    comment = comment,
                    columns = listOf(
                        CanonicalColumn(
                            name = columnName,
                            type = CanonicalColumnType("kagiroi.string"),
                            nullable = true,
                        ),
                    ),
                ),
            ),
        )
        return CanonicalDesiredSchema(
            formatVersion = CANONICAL_SCHEMA_FORMAT_VERSION,
            schema = physical,
            metadata = CanonicalSchemaMetadata(
                CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
            ),
        )
    }
}
