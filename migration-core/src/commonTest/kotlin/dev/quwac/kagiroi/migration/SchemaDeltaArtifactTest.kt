package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchemaDeltaArtifactTest {
    @Test
    fun typedDefaultsRoundTripThroughArtifactsWithoutChangingCanonicalSchema() {
        val type = CanonicalColumnType("kagiroi.uuid")
        val organization = CanonicalColumn(
            "organization_id",
            type,
            nullable = false,
            default = CanonicalLiteralExpression(
                CanonicalStringValue("01a1d000-0000-7000-8000-000000000001"),
                type,
            ),
        )
        val previous = schemaOf(usersTable())
        val desired = schemaOf(usersTable().copy(columns = usersTable().columns + organization))
        val plan = diffSchema(previous, desired)
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        val artifact = createMigrationArtifact(script.body, MigrationDialect.PostgreSql, plan)
        val parsed = parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body))
        assertEquals(artifact, parsed)
        assertEquals(
            desired.schema,
            replaySchemaDeltas(
                listOf(SchemaDelta(listOf(CreateTable(usersTable()))), parsed.metadata.schemaDelta),
            ).schema,
        )
        assertEquals(desired, decodeCanonicalSchema(encodeCanonicalSchema(desired)))
    }

    @Test
    fun acceptsLegacyTypeDiscriminatorsForDefaultExpressions() {
        val table = usersTable().copy(
            columns = listOf(
                usersTable().columns.single().copy(default = CanonicalFunctionExpression("currentTimestamp")),
            ),
        )
        val artifact = createMigrationArtifact(
            "SELECT 1;",
            MigrationDialect.PostgreSql,
            MigrationPlan.Changes(listOf(CreateTable(table))),
        )
        val legacy = legacyJsonArtifact(artifact.metadata, artifact.body)
            .replace("\"kind\": \"function\"", "\"type\": \"function\"")
        assertEquals(artifact, parseMigrationArtifact(legacy))
        assertEquals(true, MigrationId.isValidFilename("20261113090000.sql"))
        assertEquals(true, MigrationId.isValidFilename("20261113090000_legacy.sql"))
    }

    @Test
    fun d1_scripts_keep_the_transaction_boundary_that_the_cli_submits_as_an_atomic_batch() {
        val script = generateMigrationScript(
            MigrationPlan.Changes(listOf(CreateTable(usersTable()))),
            MigrationDialect.D1,
        )

        assertEquals(true, script.body.startsWith("BEGIN;\n\n"))
        assertEquals(true, script.body.endsWith("\n\nCOMMIT;\n"))
    }

    @Test
    fun generated_file_delta_replays_to_the_current_physical_schema() {
        val users = usersTable()
        val desired = schemaOf(users)
        val plan = MigrationPlan.Changes(listOf(CreateTable(users)))
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        val artifact = createMigrationArtifact(script.body, MigrationDialect.PostgreSql, plan)
        val named = NamedMigrationArtifact("20261101090000_init.sql", artifact)

        val result = verifyMigrationArtifacts(listOf(named), desired, MigrationDialect.PostgreSql)

        assertEquals(1, result.migrationCount)
        assertEquals(1, result.stepCount)
        assertEquals(plan.operations, artifact.metadata.schemaDelta.operations)
        assertEquals(false, script.body.contains("kagiroi:operation"))
        assertEquals(desired.schema, result.reconstructedSchema.schema)
    }

    @Test
    fun handwritten_seed_sql_does_not_change_the_reconstructed_kotlin_schema() {
        val users = usersTable()
        val plan = MigrationPlan.Changes(listOf(CreateTable(users)))
        val generated = createMigrationArtifact(
            generateMigrationScript(plan, MigrationDialect.PostgreSql).body,
            MigrationDialect.PostgreSql,
            plan,
        )
        val raw = "INSERT INTO users (id) VALUES (1);\n"
        val result = verifyMigrationArtifacts(
            listOf(
                NamedMigrationArtifact("20261101090000_init.sql", generated),
                NamedMigrationArtifact(
                    "20261101100000_seed.sql",
                    parseMigrationArtifact(raw, MigrationDialect.PostgreSql),
                ),
            ),
            schemaOf(users),
            MigrationDialect.PostgreSql,
        )

        assertEquals(2, result.migrationCount)
        assertEquals(2, result.stepCount)
        assertEquals(schemaOf(users).schema, result.reconstructedSchema.schema)
    }

    @Test
    fun multiple_transactions_and_manual_dml_keep_one_file_delta_without_markers() {
        val table = CanonicalTableReference(name = "users")
        val add = AddColumn(table, CanonicalColumn("email", CanonicalColumnType("kagiroi.string"), true))
        val required = SetNotNull(CanonicalColumnReference(table, "email"))
        val plan = MigrationPlan.Changes(listOf(CreateTable(usersTable()), add, required))
        val body = """
            BEGIN;
            CREATE TABLE "users" ("id" BIGINT NOT NULL);
            ALTER TABLE "users" ADD COLUMN "email" TEXT;
            COMMIT;

            UPDATE "users" SET "email" = 'unknown' WHERE "email" IS NULL;

            BEGIN;
            ALTER TABLE "users" ALTER COLUMN "email" SET NOT NULL;
            COMMIT;
        """.trimIndent() + "\n"

        val artifact = createMigrationArtifact(body, MigrationDialect.PostgreSql, plan)

        assertEquals(3, artifact.steps.size)
        assertEquals(plan.operations, artifact.metadata.schemaDelta.operations)
        assertEquals(body, artifact.body)
        val desired = replaySchemaDeltas(listOf(SchemaDelta(plan.operations)))
        val result = verifyMigrationArtifacts(
            listOf(NamedMigrationArtifact("20261101090000_init.sql", artifact)),
            desired,
        )
        assertEquals(desired.schema, result.reconstructedSchema.schema)
    }

    @Test
    fun unclosed_comments_and_transactions_are_rejected() {
        val plan = MigrationPlan.NoChanges
        assertFailsWith<IllegalArgumentException> {
            createMigrationArtifact(
                "/*\nkagiroi:\n{broken\nBEGIN; SELECT 1; COMMIT;",
                MigrationDialect.PostgreSql,
                plan,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            createMigrationArtifact("BEGIN; SELECT 1;", MigrationDialect.PostgreSql, plan)
        }
    }

    @Test
    fun rename_column_replays_constraints_foreign_keys_and_indexes_with_the_new_physical_name() {
        val users = CanonicalTable(
            name = "users",
            columns = listOf(
                CanonicalColumn("name", CanonicalColumnType("kagiroi.string"), nullable = false),
                CanonicalColumn("manager_name", CanonicalColumnType("kagiroi.string"), nullable = true),
            ),
            primaryKey = CanonicalPrimaryKey(columns = listOf("name")),
            uniqueConstraints = listOf(CanonicalUniqueConstraint(columns = listOf("name"))),
            foreignKeys = listOf(
                CanonicalForeignKey(
                    sourceColumns = listOf("manager_name"),
                    targetTable = CanonicalTableReference(name = "users"),
                    targetColumns = listOf("name"),
                ),
            ),
            checks = listOf(
                CanonicalCheckConstraint(
                    expression = CanonicalNullPredicate(
                        CanonicalColumnExpression(
                            CanonicalColumnReference(CanonicalTableReference(name = "users"), "name"),
                        ),
                        CanonicalNullOperator.IsNotNull,
                    ),
                ),
            ),
        )
        val index = CanonicalIndex(
            name = "users_name",
            table = CanonicalTableReference(name = "users"),
            unique = false,
            keys = listOf(
                CanonicalColumnIndexKey(CanonicalColumnReference(CanonicalTableReference(name = "users"), "name")),
            ),
            includedColumns = listOf(CanonicalColumnReference(CanonicalTableReference(name = "users"), "name")),
        )

        val replayed = replaySchemaDeltas(
            listOf(
                SchemaDelta(listOf(CreateTable(users), CreateIndex(index))),
                SchemaDelta(listOf(RenameColumn(CanonicalTableReference(name = "users"), "name", "display_name"))),
            ),
        )

        val table = replayed.schema.tables.single()
        assertEquals(listOf("display_name"), table.primaryKey?.columns)
        assertEquals(listOf("display_name"), table.uniqueConstraints.single().columns)
        assertEquals(listOf("display_name"), table.foreignKeys.single().targetColumns)
        assertEquals(
            "display_name",
            (replayed.schema.indexes.single().keys.single() as CanonicalColumnIndexKey).column.name,
        )
        assertEquals("display_name", replayed.schema.indexes.single().includedColumns.single().name)
    }

    private fun usersTable() = CanonicalTable(
        name = "users",
        columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int64"), nullable = false)),
    )

    private fun schemaOf(table: CanonicalTable): CanonicalDesiredSchema {
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
