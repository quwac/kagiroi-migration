package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PostgresEnumMigrationTest {
    @Test
    fun createsEnumsBeforeColumnsAndReplaysTypeConversionWithoutChangingLegacyHashes() {
        val (before, desired) = postgresEnumFixture()
        val plan = diffSchema(before, desired)
        assertIs<CreatePostgresEnum>(plan.operations.first())
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        assertTrue(script.body.contains("CREATE TYPE \"app\".\"punch_kind\" AS ENUM ('ClockIn', 'ClockOut')"))
        assertTrue(script.body.contains("USING CAST(\"kind\" AS \"app\".\"punch_kind\")"))
        val artifact = createMigrationArtifact(script.body, MigrationDialect.PostgreSql, plan)
        assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
        val initial = SchemaDelta(before.schema.tables.map { CreateTable(it) })
        val replayed = replaySchemaDeltas(listOf(initial, artifact.metadata.schemaDelta))
        assertEquals(desired.schema, replayed.schema)
        assertEquals(MigrationPlan.NoChanges, diffSchema(replayed, desired))
        assertEquals(desired, decodeCanonicalSchema(encodeCanonicalSchema(desired)))
        assertTrue(!encodePhysicalSchema(before.schema).contains("enumTypes"))
        for (dialect in MigrationDialect.entries - MigrationDialect.PostgreSql) {
            assertFailsWith<IllegalArgumentException> { generateMigrationScript(plan, dialect) }
        }
    }

    @Test
    fun commitsAddedLabelsBeforeDefaultsAndRejectsRemovalOrReordering() {
        val (_, before) = postgresEnumFixture()
        val extended = before.schema.enumTypes.single().copy(labels = listOf("ClockIn", "ClockOut", "New"))
        val create = diffSchema(emptyCanonicalSchema(), before)
        val desired = replaySchemaDeltas(
            listOf(
                SchemaDelta(create.operations),
                SchemaDelta(listOf(AddPostgresEnumValue(extended.reference(), "New"))),
            ),
        )
        val plan = diffSchema(before, desired)
        assertIs<AddPostgresEnumValue>(plan.operations.single())
        val default = SetDefault(
            CanonicalColumnReference(CanonicalTableReference("app", "punches"), "kind"),
            CanonicalLiteralExpression(CanonicalStringValue("New"), CanonicalColumnType("kagiroi.string")),
        )
        val script = generateMigrationScript(
            MigrationPlan.Changes(plan.operations + default),
            MigrationDialect.PostgreSql,
        )
        val artifact = createMigrationArtifact(
            script.body,
            MigrationDialect.PostgreSql,
            MigrationPlan.Changes(plan.operations + default),
        )
        assertEquals(2, artifact.steps.size)
        assertEquals(MigrationStepKind.NonTransactional, artifact.steps.first().kind)
        assertTrue(script.body.startsWith("ALTER TYPE"))
        for (labels in listOf(listOf("ClockIn"), listOf("ClockOut", "ClockIn"), listOf("Renamed", "ClockOut"))) {
            val changed = before.schema.enumTypes.single().copy(labels = labels)
            val target = replaySchemaDeltas(
                listOf(SchemaDelta(listOf(CreatePostgresEnum(changed), CreateTable(before.schema.tables.single())))),
            )
            assertFailsWith<IllegalArgumentException> { diffSchema(before, target) }
        }
        val drop = diffSchema(before, emptyCanonicalSchema()).operations
        assertIs<DropPostgresEnum>(drop.last())
    }
}

internal fun postgresEnumFixture(schema: String = "app"): Pair<CanonicalDesiredSchema, CanonicalDesiredSchema> {
    val reference = CanonicalTableReference(schema, "punches")
    val text = CanonicalColumnType("kagiroi.string")
    val table = CanonicalTable(
        schemaName = schema,
        name = "punches",
        columns = listOf(CanonicalColumn("kind", text, false)),
    )
    val before = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(table)))))
    val type = CanonicalPostgresEnum("punch_kind", listOf("ClockIn", "ClockOut"), schema)
    val native = CanonicalColumnType(
        "kagiroi.postgres.enum",
        listOf(CanonicalTypeArgument("schemaName", schema), CanonicalTypeArgument("name", type.name)),
    )
    val column = CanonicalColumnReference(reference, "kind")
    val target = replaySchemaDeltas(
        listOf(
            SchemaDelta(
                listOf(
                    CreatePostgresEnum(type),
                    CreateTable(table.copy(columns = listOf(table.columns.single().copy(type = native)))),
                ),
            ),
        ),
    )
    val hint = CanonicalColumnTypeConversion(
        column,
        text,
        CanonicalCastExpression(CanonicalColumnExpression(column), native),
    )
    return before to target.copy(evolution = CanonicalEvolution(typeConversions = listOf(hint)))
}
