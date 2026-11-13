package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ColumnTypeConversionTest {
    @Test
    fun requiresVersionFourForUsingAndPreservesNativeLocalDateTimePrecision() {
        val (_, desired) = typeConversionFixture()
        val encoded = encodeCanonicalSchema(desired)
        assertEquals(desired, canonicalizeSchemaProjection(encoded))
        val legacy = encoded.replace("\"formatVersion\":4", "\"formatVersion\":3")
        assertFailsWith<IllegalArgumentException> { decodeCanonicalSchema(legacy) }
        assertFailsWith<IllegalArgumentException> { canonicalizeSchemaProjection(legacy) }
        val table = CanonicalTable(
            name = "local_events",
            columns = listOf(
                CanonicalColumn("created_at", CanonicalColumnType("kagiroi.kotlinx.localDateTime"), false),
            ),
        )
        val types = mapOf(
            MigrationDialect.PostgreSql to "TIMESTAMP WITHOUT TIME ZONE",
            MigrationDialect.MySql to "DATETIME(6)",
            MigrationDialect.MariaDb to "DATETIME(6)",
            MigrationDialect.Sqlite to "TEXT",
            MigrationDialect.D1 to "TEXT",
            MigrationDialect.SqlServer to "DATETIME2(7)",
            MigrationDialect.Oracle to "TIMESTAMP(9)",
        )
        types.forEach { (dialect, type) ->
            assertTrue(
                generateMigrationScript(MigrationPlan.Changes(listOf(CreateTable(table))), dialect).body.contains(type),
            )
        }
    }

    @Test
    fun ordersDefaultsAroundUsingAndReplaysOnlyTheFinalShape() {
        val (before, desired) = typeConversionFixture()
        val plan = diffSchema(before, desired)
        assertIs<DropDefault>(plan.operations[0])
        assertTrue(assertIs<AlterColumnType>(plan.operations[1]).using != null)
        assertIs<SetDefault>(plan.operations[2])
        val body = generateMigrationScript(plan, MigrationDialect.PostgreSql).body
        assertTrue(body.contains("TYPE TIMESTAMP WITHOUT TIME ZONE USING (\"created_at\" AT TIME ZONE 'UTC')"))
        assertTrue(body.contains("SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')"))
        val artifact = createMigrationArtifact(body, MigrationDialect.PostgreSql, plan)
        assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
        val replayed = replaySchemaDeltas(
            listOf(SchemaDelta(diffSchema(emptyCanonicalSchema(), before).operations), SchemaDelta(plan.operations)),
        )
        assertEquals(desired.schema, replayed.schema)
        assertEquals(MigrationPlan.NoChanges, diffSchema(replayed, desired))
        assertEquals(desired, decodeCanonicalSchema(encodeCanonicalSchema(desired)))
        assertEquals(physicalSchemaHash(desired), physicalSchemaHash(desired.copy(evolution = CanonicalEvolution())))
        assertTrue(diffSchema(emptyCanonicalSchema(), desired).operations.none { it is AlterColumnType })
    }

    @Test
    fun rejectsOtherDialectsWrongSourceTypesAndCrossTableReferences() {
        val (before, desired) = typeConversionFixture()
        for (dialect in MigrationDialect.entries - MigrationDialect.PostgreSql) {
            assertFailsWith<IllegalArgumentException> {
                generateMigrationScript(diffSchema(before, desired), dialect)
            }
        }
        val conversion = desired.evolution.typeConversions.single()
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                before,
                desired.copy(
                    evolution = CanonicalEvolution(
                        typeConversions = listOf(
                            conversion.copy(from = CanonicalColumnType("kagiroi.string")),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                before,
                desired.copy(
                    evolution = CanonicalEvolution(
                        typeConversions = listOf(
                            conversion.copy(
                                using = CanonicalColumnExpression(
                                    CanonicalColumnReference(CanonicalTableReference(null, "other"), "created_at"),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }
}

/** Shared by the real PostgreSQL contract; UTC conversion must not inherit the session time zone. */
internal fun typeConversionFixture(namespace: String? = null): Pair<CanonicalDesiredSchema, CanonicalDesiredSchema> {
    val table = CanonicalTableReference(namespace, "events")
    val reference = CanonicalColumnReference(table, "created_at")
    val instant = CanonicalColumnType("kagiroi.instant")
    val local = CanonicalColumnType("kagiroi.kotlinx.localDateTime")
    val now = CanonicalFunctionExpression("currentTimestamp")
    fun utc(expression: CanonicalSchemaExpression) = CanonicalFunctionExpression(
        "postgresAtTimeZone",
        listOf(
            expression,
            CanonicalLiteralExpression(CanonicalStringValue("UTC"), CanonicalColumnType("kagiroi.string")),
        ),
    )
    val original = CanonicalTable(
        namespace,
        "events",
        columns = listOf(
            CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), false, primaryKey = true),
            CanonicalColumn("created_at", instant, false, default = now),
        ),
    )
    val before = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(original)))))
    val physical = CanonicalPhysicalSchema(
        tables = listOf(
            original.copy(
                columns = listOf(
                    original.columns[0],
                    CanonicalColumn("created_at", local, false, default = utc(now)),
                ),
            ),
        ),
    )
    val desired = CanonicalDesiredSchema(
        TYPE_CONVERSION_CANONICAL_SCHEMA_FORMAT_VERSION,
        physical,
        CanonicalEvolution(
            typeConversions = listOf(
                CanonicalColumnTypeConversion(
                    reference,
                    instant,
                    utc(CanonicalColumnExpression(reference)),
                ),
            ),
        ),
        CanonicalSchemaMetadata(CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical))),
    )
    return before to desired
}
