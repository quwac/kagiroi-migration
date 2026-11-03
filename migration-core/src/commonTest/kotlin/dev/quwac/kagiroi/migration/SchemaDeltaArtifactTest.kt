package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchemaDeltaArtifactTest {
    @Test
    fun d1_drafts_keep_the_transaction_boundary_that_the_cli_submits_as_an_atomic_batch() {
        val script = generateMigrationScript(
            MigrationPlan.Changes(listOf(CreateTable(usersTable()))),
            MigrationDialect.D1,
        )

        assertEquals(true, script.body.startsWith("BEGIN;\n\n"))
        assertEquals(true, script.body.endsWith("\n\nCOMMIT;\n"))
    }

    @Test
    fun finalized_step_deltas_replay_to_the_current_physical_schema() {
        val users = usersTable()
        val desired = schemaOf(users)
        val plan = MigrationPlan.Changes(listOf(CreateTable(users)))
        val finalized = finalizeMigrationArtifact(
            MigrationArtifact(
                MigrationArtifactMetadata(dialect = "postgresql"),
                generateMigrationScript(plan, MigrationDialect.PostgreSql).body,
            ),
            plan,
        )
        val named = NamedMigrationArtifact("20261101090000_init.sql", finalized)

        val result = verifyMigrationArtifacts(listOf(named), desired, MigrationDialect.PostgreSql)

        assertEquals(1, result.migrationCount)
        assertEquals(1, result.stepCount)
        assertEquals(desired.schema, result.reconstructedSchema.schema)
    }

    @Test
    fun operation_markers_follow_transaction_splitting_while_manual_dml_stays_unmanaged() {
        val table = CanonicalTableReference(name = "users")
        val add = AddColumn(table, CanonicalColumn("email", CanonicalColumnType("kagiroi.string"), true))
        val required = SetNotNull(CanonicalColumnReference(table, "email"))
        val plan = MigrationPlan.Changes(listOf(add, required))
        val first = OperationMarker(0, operationFingerprint(add))
        val second = OperationMarker(1, operationFingerprint(required))
        val body = """
            BEGIN;
            -- kagiroi:operation=${first.index}:${first.fingerprint}
            ALTER TABLE "users" ADD COLUMN "email" TEXT;
            COMMIT;

            BEGIN;
            UPDATE "users" SET "email" = 'unknown' WHERE "email" IS NULL;
            -- kagiroi:operation=${second.index}:${second.fingerprint}
            ALTER TABLE "users" ALTER COLUMN "email" SET NOT NULL;
            COMMIT;
        """.trimIndent() + "\n"

        val finalized = finalizeMigrationArtifact(
            MigrationArtifact(MigrationArtifactMetadata(dialect = "postgresql"), body),
            plan,
        )

        assertEquals(2, finalized.metadata.steps.size)
        assertEquals(listOf(add), finalized.metadata.steps[0].schemaDelta.operations)
        assertEquals(listOf(required), finalized.metadata.steps[1].schemaDelta.operations)
    }

    @Test
    fun deleted_operation_marker_is_rejected() {
        val users = usersTable()
        val plan = MigrationPlan.Changes(listOf(CreateTable(users)))
        assertFailsWith<IllegalArgumentException> {
            finalizeMigrationArtifact(
                MigrationArtifact(MigrationArtifactMetadata(dialect = "postgresql"), "CREATE TABLE users (id BIGINT);"),
                plan,
            )
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
