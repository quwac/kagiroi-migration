package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NullableTypeConversionTest {
    @Test
    fun replaysNullableEnumConversionsAndRejectsImplicitNullabilityChanges() {
        val (before, desired) = nullableEnumConversionFixture()
        val plan = diffSchema(before, desired)
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        assertTrue(script.body.contains("USING CAST(\"kind\" AS \"nullable_kind\")"))
        val artifact = createMigrationArtifact(script.body, MigrationDialect.PostgreSql, plan)
        val parsed = parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body))
        val deltas = listOf(
            SchemaDelta(diffSchema(emptyCanonicalSchema(), before).operations),
            parsed.metadata.schemaDelta,
        )
        val replayed = replaySchemaDeltas(deltas)
        assertEquals(MigrationPlan.NoChanges, diffSchema(replayed, desired))
        assertTrue(replayed.schema.tables.single().columns.single().nullable)
        val table = desired.schema.tables.single()
        val nonNullTable = table.copy(columns = table.columns.map { it.copy(nullable = false) })
        val nonNullTarget = desired.copy(schema = desired.schema.copy(tables = listOf(nonNullTable)))
        assertFailsWith<IllegalArgumentException> { diffSchema(before, nonNullTarget) }
    }
}

internal fun nullableEnumConversionFixture(
    namespace: String? = null,
): Pair<CanonicalDesiredSchema, CanonicalDesiredSchema> {
    val reference = CanonicalColumnReference(CanonicalTableReference(namespace, "nullable_punches"), "kind")
    val text = CanonicalColumnType("kagiroi.string")
    val table = CanonicalTable(namespace, "nullable_punches", columns = listOf(CanonicalColumn("kind", text, true)))
    val before = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(table)))))
    val arguments = listOfNotNull(
        namespace?.let { CanonicalTypeArgument("schemaName", it) },
        CanonicalTypeArgument("name", "nullable_kind"),
    )
    val enumType = CanonicalColumnType("kagiroi.postgres.enum", arguments)
    val operations = listOf(
        CreatePostgresEnum(CanonicalPostgresEnum("nullable_kind", listOf("In", "Out"), namespace)),
        CreateTable(table.copy(columns = listOf(CanonicalColumn("kind", enumType, true)))),
    )
    val desired = replaySchemaDeltas(listOf(SchemaDelta(operations)))
    val conversion = CanonicalColumnTypeConversion(
        reference,
        text,
        CanonicalCastExpression(CanonicalColumnExpression(reference), enumType),
    )
    return before to desired.copy(evolution = CanonicalEvolution(typeConversions = listOf(conversion)))
}
