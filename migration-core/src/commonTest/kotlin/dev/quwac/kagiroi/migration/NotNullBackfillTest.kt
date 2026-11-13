package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotNullBackfillTest {
    @Test
    fun generatesOneArtifactWithTheUserOperationsBetweenNullableAdditionAndConstraints() {
        val fixture = notNullBackfillFixture()
        val plan = diffSchema(fixture.previous, fixture.desired)
        assertIs<CreateTable>(plan.operations.first())
        val addition = assertIs<AddColumnWithBackfill>(plan.operations[1])
        assertEquals(MigrationRisk.Review, addition.risk.level)
        for (dialect in MigrationDialect.entries - setOf(MigrationDialect.Sqlite, MigrationDialect.D1)) {
            val script = generateMigrationScript(plan, dialect)
            val ddl = MigrationSqlGenerator.generate(plan, dialect).ddl
            val nullableAddition = ddl.filterIsInstance<MigrationDdlStatement.AddColumn>().single()
            assertTrue(nullableAddition.column.nullable)
            assertEquals(null, nullableAddition.column.default)
            val update = ddl.indexOfFirst {
                it is MigrationDdlStatement.Backfill &&
                    it.statement is CanonicalBackfillUpdate
            }
            val notNull = ddl.indexOfFirst { it is MigrationDdlStatement.SetNotNull }
            val unique = ddl.indexOfFirst { it is MigrationDdlStatement.AddUnique }
            val foreignKey = ddl.indexOfFirst { it is MigrationDdlStatement.AddForeignKey }
            assertTrue(update < notNull && notNull < unique && notNull < foreignKey)
            val artifact = createMigrationArtifact(script.body, dialect, plan)
            val parsed = parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body))
            assertEquals(artifact, parsed)
            val initial = createMigrationArtifact(
                generateMigrationScript(diffSchema(emptyCanonicalSchema(), fixture.previous), dialect).body,
                dialect,
                diffSchema(emptyCanonicalSchema(), fixture.previous),
            )
            verifyMigrationArtifacts(
                listOf(
                    NamedMigrationArtifact("20261113090000.sql", initial),
                    NamedMigrationArtifact("20261113090001.sql", parsed),
                ),
                fixture.desired,
                dialect,
            )
        }
    }

    @Test
    fun keepsBackfillOutOfPhysicalHashesAndDoesNotRepeatItOrUseItForNewTables() {
        val fixture = notNullBackfillFixture()
        assertEquals(
            physicalSchemaHash(fixture.desired),
            physicalSchemaHash(fixture.desired.copy(evolution = CanonicalEvolution())),
        )
        val initial = diffSchema(emptyCanonicalSchema(), fixture.desired)
        assertTrue(initial.operations.none { it is AddColumnWithBackfill })
        assertTrue(
            MigrationSqlGenerator.generate(
                initial,
                MigrationDialect.PostgreSql,
            ).statements.none { it.sql.startsWith("INSERT") },
        )
        val replayed = replaySchemaDeltas(
            listOf(
                SchemaDelta(diffSchema(emptyCanonicalSchema(), fixture.previous).operations),
                SchemaDelta(diffSchema(fixture.previous, fixture.desired).operations),
            ),
        )
        assertEquals(fixture.desired.schema, replayed.schema)
        assertEquals(MigrationPlan.NoChanges, diffSchema(replayed, fixture.desired))
        val encoded = encodeCanonicalSchema(fixture.desired)
        assertEquals(fixture.desired, decodeCanonicalSchema(encoded))
    }

    @Test
    fun setsDefaultsAfterBackfillAndPreservesThemWhenSettingNotNull() {
        val fixture = notNullBackfillFixture(default = backfillInteger(9))
        for (dialect in MigrationDialect.entries - setOf(MigrationDialect.Sqlite, MigrationDialect.D1)) {
            val generation = MigrationSqlGenerator.generate(diffSchema(fixture.previous, fixture.desired), dialect)
            val ddl = generation.ddl
            val update = ddl.indexOfFirst {
                it is MigrationDdlStatement.Backfill &&
                    it.statement is CanonicalBackfillUpdate
            }
            val default = ddl.indexOfFirst { it is MigrationDdlStatement.SetDefault }
            val notNull = ddl.indexOfFirst { it is MigrationDdlStatement.SetNotNull }
            assertTrue(update < default && default < notNull)
            if (dialect in setOf(MigrationDialect.MySql, MigrationDialect.MariaDb)) {
                assertTrue(
                    generation.statements.any { it.sql.contains("MODIFY COLUMN `parent_id` INT DEFAULT 9 NOT NULL") },
                )
            }
        }
    }

    @Test
    fun rejectsUnsupportedDialectsAndInvalidBackfillDeclarations() {
        val fixture = notNullBackfillFixture()
        val plan = diffSchema(fixture.previous, fixture.desired)
        for (dialect in listOf(MigrationDialect.Sqlite, MigrationDialect.D1)) {
            assertFailsWith<MigrationSqlGenerationException> { generateMigrationScript(plan, dialect) }
        }
        val hint = fixture.desired.evolution.notNullBackfills.single()
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                fixture.previous,
                fixture.desired.copy(evolution = CanonicalEvolution(notNullBackfills = listOf(hint, hint))),
            )
        }
        val invalid = hint.copy(
            statements = listOf(
                CanonicalBackfillInsert(
                    CanonicalTableReference(name = "missing"),
                    listOf(listOf(CanonicalBackfillAssignment("id", backfillInteger(7)))),
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                fixture.previous,
                fixture.desired.copy(evolution = CanonicalEvolution(notNullBackfills = listOf(invalid))),
            )
        }
    }

    @Test
    fun rejectsOlderProjectionVersionsAndOutOfScopeUpdateReferences() {
        val fixture = notNullBackfillFixture()
        assertFailsWith<IllegalArgumentException> {
            decodeCanonicalSchema(encodeCanonicalSchema(fixture.desired.copy(formatVersion = 2)))
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalBackfillUpdate(
                CanonicalTableReference(name = "a_children"),
                listOf(
                    CanonicalBackfillAssignment(
                        "parent_id",
                        CanonicalColumnExpression(
                            CanonicalColumnReference(CanonicalTableReference(name = "z_parents"), "id"),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalBackfillInsert(CanonicalTableReference(name = "parents"), emptyList())
        }
    }

    @Test
    fun defersInlinePrimaryAndUniqueConstraintsWithoutDuplicatingTheirMetadata() {
        val integer = CanonicalColumnType("kagiroi.int32")
        val table = CanonicalTableReference(name = "keys")
        val original = CanonicalTable(name = table.name, columns = listOf(CanonicalColumn("id", integer, false)))
        val previous = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(original)))))
        for (primary in listOf(true, false)) {
            val column = CanonicalColumn("value", integer, false, primaryKey = primary, unique = !primary)
            val reference = CanonicalColumnReference(table, column.name)
            val statement = CanonicalBackfillUpdate(
                table,
                listOf(CanonicalBackfillAssignment("value", backfillInteger(7))),
            )
            val physical = previous.schema.copy(tables = listOf(original.copy(columns = original.columns + column)))
            val desired = CanonicalDesiredSchema(
                BACKFILL_CANONICAL_SCHEMA_FORMAT_VERSION,
                physical,
                CanonicalEvolution(notNullBackfills = listOf(CanonicalNotNullBackfill(reference, listOf(statement)))),
                CanonicalSchemaMetadata(
                    CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
                ),
            )
            val plan = diffSchema(previous, desired)
            val ddl = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).ddl
            assertEquals(
                1,
                ddl.count { it is MigrationDdlStatement.AddPrimaryKey || it is MigrationDdlStatement.AddUnique },
            )
            val replayed = replaySchemaDeltas(
                listOf(SchemaDelta(listOf(CreateTable(original))), SchemaDelta(plan.operations)),
            )
            assertEquals(physicalSchemaHash(desired), physicalSchemaHash(replayed))
        }
    }

    @Test
    fun ordersAnExplicitBackfillTargetBeforeItsOwnerWithoutRequiringAForeignKey() {
        val fixture = notNullBackfillFixture()
        val physical = fixture.desired.schema.copy(
            tables = fixture.desired.schema.tables.map { it.copy(foreignKeys = emptyList()) },
        )
        val desired = fixture.desired.copy(
            schema = physical,
            metadata = CanonicalSchemaMetadata(
                CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
            ),
        )
        assertEquals(
            "z_parents",
            assertIs<CreateTable>(diffSchema(fixture.previous, desired).operations.first()).table.name,
        )
    }
}

internal data class NotNullBackfillFixture(val previous: CanonicalDesiredSchema, val desired: CanonicalDesiredSchema)

internal fun backfillInteger(value: Int): CanonicalLiteralExpression =
    CanonicalLiteralExpression(CanonicalIntegerValue(value.toString()), CanonicalColumnType("kagiroi.int32"))

/** Existing child rows need an explicitly inserted parent before their new NOT NULL foreign key can be enforced. */
internal fun notNullBackfillFixture(
    namespace: String? = null,
    default: CanonicalSchemaExpression? = null,
): NotNullBackfillFixture {
    val integer = CanonicalColumnType("kagiroi.int32")
    val child = CanonicalTableReference(namespace, "a_children")
    val parent = CanonicalTableReference(namespace, "z_parents")
    val old = CanonicalTable(
        schemaName = namespace,
        name = child.name,
        columns = listOf(CanonicalColumn("id", integer, false, primaryKey = true)),
    )
    val before = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(old)))))
    val target = CanonicalColumnReference(child, "parent_id")
    val statements = listOf(
        CanonicalBackfillInsert(parent, listOf(listOf(CanonicalBackfillAssignment("id", backfillInteger(7))))),
        CanonicalBackfillUpdate(
            child,
            listOf(CanonicalBackfillAssignment("parent_id", backfillInteger(7))),
            CanonicalNullPredicate(CanonicalColumnExpression(target), CanonicalNullOperator.IsNull),
        ),
    )
    val physical = before.schema.copy(
        tables = listOf(
            old.copy(
                columns = old.columns + CanonicalColumn(
                    "parent_id",
                    integer,
                    false,
                    default = default,
                    comment = "Parent ID",
                ),
                foreignKeys = listOf(CanonicalForeignKey("child_parent_fk", listOf("parent_id"), parent, listOf("id"))),
                uniqueConstraints = listOf(CanonicalUniqueConstraint("child_parent_unique", listOf("id", "parent_id"))),
            ),
            CanonicalTable(
                schemaName = namespace,
                name = parent.name,
                columns = listOf(CanonicalColumn("id", integer, false, primaryKey = true)),
            ),
        ),
    )
    val desired = CanonicalDesiredSchema(
        BACKFILL_CANONICAL_SCHEMA_FORMAT_VERSION,
        physical,
        CanonicalEvolution(notNullBackfills = listOf(CanonicalNotNullBackfill(target, statements))),
        CanonicalSchemaMetadata(CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical))),
    )
    return NotNullBackfillFixture(before, desired)
}
