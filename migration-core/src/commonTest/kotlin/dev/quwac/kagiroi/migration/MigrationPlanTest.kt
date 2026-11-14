package dev.quwac.kagiroi.migration

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Suppress("LargeClass") // One shared fixture keeps focused plan assertions readable.
class MigrationPlanTest {
    @Test
    fun emitsNoChangesForEqualPhysicalSchemasEvenWhenDesiredAddsRenameHistory() {
        val previous = document(tables = listOf(table("users", column("display_name"))))
        val desired = document(
            tables = listOf(table("users", column("display_name"))),
            evolution = CanonicalEvolution(
                columnRenames = listOf(
                    CanonicalColumnRename(tableReference("users"), "display_name", listOf("name")),
                ),
            ),
        )

        assertEquals(MigrationPlan.NoChanges, diffSchema(previous, desired))
    }

    @Test
    fun createsDropsAndRenamesTablesWithoutInferringNames() {
        val users = table("users", column("id"))
        val accounts = table("accounts", column("id"))

        val renamed = diffSchema(
            document(tables = listOf(users)),
            document(
                tables = listOf(accounts),
                evolution = CanonicalEvolution(
                    tableRenames = listOf(CanonicalTableRename(tableReference("accounts"), listOf("users"))),
                ),
            ),
        )
        assertEquals(
            listOf(RenameTable(tableReference("users"), tableReference("accounts"))),
            renamed.operations,
        )

        val addedAndRemoved = diffSchema(
            document(tables = listOf(users)),
            document(tables = listOf(table("orders", column("id")))),
        )
        assertIs<CreateTable>(addedAndRemoved.operations.first())
        assertIs<DropTable>(addedAndRemoved.operations.last())
    }

    @Test
    fun treatsAStillPresentCurrentTableAsAlreadyRenamed() {
        val accounts = table("accounts", column("id"))
        val plan = diffSchema(
            document(tables = listOf(accounts)),
            document(
                tables = listOf(accounts),
                evolution = CanonicalEvolution(
                    tableRenames = listOf(CanonicalTableRename(tableReference("accounts"), listOf("users"))),
                ),
            ),
        )

        assertEquals(MigrationPlan.NoChanges, plan)
    }

    @Test
    fun rejectsAmbiguousTableRenameMetadataAndLetsExplicitClaimsReuseNames() {
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                document(tables = listOf(table("users", column("id")), table("customers", column("id")))),
                document(
                    tables = listOf(table("accounts", column("id"))),
                    evolution = CanonicalEvolution(
                        tableRenames = listOf(
                            CanonicalTableRename(tableReference("accounts"), listOf("users", "customers")),
                        ),
                    ),
                ),
            )
        }
        val reusedName = diffSchema(
            document(tables = listOf(table("members", column("id")))),
            document(
                tables = listOf(table("members", column("id")), table("zz_accounts", column("id"))),
                evolution = CanonicalEvolution(
                    tableRenames = listOf(
                        CanonicalTableRename(tableReference("zz_accounts"), listOf("members")),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                RenameTable(tableReference("members"), tableReference("zz_accounts")),
                CreateTable(table("members", column("id"))),
            ),
            reusedName.operations,
        )
    }

    @Test
    fun rejectsCrossSchemaRenameAndUnsupportedFormatVersions() {
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                document(tables = listOf(table("users", column("id"), schemaName = "public"))),
                document(
                    tables = listOf(table("accounts", column("id"), schemaName = "app")),
                    evolution = CanonicalEvolution(
                        tableRenames = listOf(
                            CanonicalTableRename(CanonicalTableReference("app", "accounts"), listOf("users")),
                        ),
                    ),
                ),
            )
        }
        val valid = document(tables = listOf(table("users", column("id"))))
        assertFailsWith<IllegalArgumentException> { diffSchema(valid.copy(formatVersion = 2), valid) }
    }

    @Test
    fun resolvesColumnRenameThenDiffsItsPropertiesAgainstTheRenamedColumn() {
        val previous = document(tables = listOf(table("users", column("name", type = stringType))))
        val desired = document(
            tables = listOf(table("users", column("display_name", type = longType, nullable = true))),
            evolution = CanonicalEvolution(
                columnRenames = listOf(
                    CanonicalColumnRename(tableReference("users"), "display_name", listOf("name")),
                ),
            ),
        )

        val operations = diffSchema(previous, desired).operations
        assertEquals(
            RenameColumn(tableReference("users"), "name", "display_name"),
            operations[0],
        )
        assertEquals(
            AlterColumnType(columnReference("users", "display_name"), stringType, longType),
            operations[1],
        )
        assertEquals(DropNotNull(columnReference("users", "display_name")), operations[2])
    }

    @Test
    fun carriesTableAndColumnRenameIdentityThroughIndexesAndExpressions() {
        val previous = document(
            tables = listOf(table("users", column("id", primaryKey = true), column("name"))),
            indexes = listOf(
                CanonicalIndex(
                    name = "users_name_idx",
                    table = tableReference("users"),
                    unique = false,
                    keys = listOf(
                        CanonicalExpressionIndexKey(
                            CanonicalFunctionExpression("lower", listOf(columnExpression("name"))),
                        ),
                    ),
                ),
            ),
        )
        val desired = document(
            tables = listOf(table("accounts", column("id", primaryKey = true), column("display_name"))),
            indexes = listOf(
                CanonicalIndex(
                    name = "users_name_idx",
                    table = tableReference("accounts"),
                    unique = false,
                    keys = listOf(
                        CanonicalExpressionIndexKey(
                            CanonicalFunctionExpression(
                                "lower",
                                listOf(CanonicalColumnExpression(columnReference("accounts", "display_name"))),
                            ),
                        ),
                    ),
                ),
            ),
            evolution = CanonicalEvolution(
                tableRenames = listOf(CanonicalTableRename(tableReference("accounts"), listOf("users"))),
                columnRenames = listOf(
                    CanonicalColumnRename(tableReference("accounts"), "display_name", listOf("name")),
                ),
            ),
        )

        assertEquals(
            listOf(
                RenameTable(tableReference("users"), tableReference("accounts")),
                RenameColumn(tableReference("accounts"), "name", "display_name"),
            ),
            diffSchema(previous, desired).operations,
        )
    }

    @Test
    fun resolvesTheOnlyPresentNameFromOrderedColumnHistoryAndRejectsAmbiguity() {
        val previous = document(tables = listOf(table("users", column("display_name"))))
        val desired = document(
            tables = listOf(table("users", column("public_name"))),
            evolution = CanonicalEvolution(
                columnRenames = listOf(
                    CanonicalColumnRename(tableReference("users"), "public_name", listOf("display_name", "name")),
                ),
            ),
        )
        assertEquals(
            RenameColumn(tableReference("users"), "display_name", "public_name"),
            diffSchema(previous, desired).operations.single(),
        )

        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                document(tables = listOf(table("users", column("display_name"), column("name")))),
                desired,
            )
        }
    }

    @Test
    fun letsAnExplicitColumnRenameReuseItsPreviousNameForANewColumn() {
        val previous = document(tables = listOf(table("users", column("name"))))
        val desired = document(
            tables = listOf(table("users", column("name"), column("display_name"))),
            evolution = CanonicalEvolution(
                columnRenames = listOf(
                    CanonicalColumnRename(tableReference("users"), "display_name", listOf("name")),
                ),
            ),
        )

        assertEquals(
            listOf(
                RenameColumn(tableReference("users"), "name", "display_name"),
                AddColumn(tableReference("users"), column("name")),
            ),
            diffSchema(previous, desired).operations,
        )
    }

    @Test
    fun rejectsEvolutionMetadataWhenTwoCurrentColumnsClaimOnePreviousName() {
        val previous = document(tables = listOf(table("users", column("name"))))
        val desired = document(
            tables = listOf(table("users", column("display_name"), column("public_name"))),
            evolution = CanonicalEvolution(
                columnRenames = listOf(
                    CanonicalColumnRename(tableReference("users"), "display_name", listOf("name")),
                    CanonicalColumnRename(tableReference("users"), "public_name", listOf("name")),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> { diffSchema(previous, desired) }
    }

    @Test
    fun rejectsEvolutionMetadataWhenTwoCurrentTablesClaimOnePreviousName() {
        val previous = document(tables = listOf(table("users", column("id"))))
        val desired = document(
            tables = listOf(table("accounts", column("id")), table("members", column("id"))),
            evolution = CanonicalEvolution(
                tableRenames = listOf(
                    CanonicalTableRename(tableReference("accounts"), listOf("users")),
                    CanonicalTableRename(tableReference("members"), listOf("users")),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> { diffSchema(previous, desired) }
    }

    @Test
    fun diffsAllColumnPropertiesStructurally() {
        val previous = document(
            tables = listOf(
                table(
                    "users",
                    column(
                        "name",
                        type = stringType,
                        nullable = true,
                        comment = "old",
                        default = literal("old"),
                        identity = CanonicalIdentity(),
                        generated = generated("old"),
                    ),
                    comment = "old table",
                ),
            ),
        )
        val desired = document(
            tables = listOf(
                table(
                    "users",
                    column(
                        "name",
                        type = longType,
                        comment = "new",
                        default = literal("new"),
                        generated = generated("new"),
                    ),
                    comment = "new table",
                ),
            ),
        )

        val operations = diffSchema(previous, desired).operations
        assertTrue(operations.any { it is AlterColumnType })
        assertTrue(operations.any { it is SetNotNull })
        assertTrue(operations.any { it is AlterDefault })
        assertTrue(operations.any { it is AlterIdentity })
        assertTrue(operations.any { it is AlterGeneratedColumn })
        assertTrue(operations.any { it is AlterColumnComment })
        assertTrue(operations.any { it is AlterTableComment })
    }

    @Test
    fun distinguishesDefaultAdditionRemovalAndChange() {
        val emptyDefault = document(tables = listOf(table("users", column("name"))))
        val oldDefault = document(tables = listOf(table("users", column("name", default = literal("old")))))
        val newDefault = document(tables = listOf(table("users", column("name", default = literal("new")))))

        assertIs<SetDefault>(diffSchema(emptyDefault, oldDefault).operations.single())
        assertIs<DropDefault>(diffSchema(oldDefault, emptyDefault).operations.single())
        assertIs<AlterDefault>(diffSchema(oldDefault, newDefault).operations.single())
    }

    @Test
    fun diffsPrimaryUniqueForeignAndCheckConstraints() {
        val previous = document(
            tables = listOf(
                table("parents", column("id", primaryKey = true)),
                table(
                    "children",
                    column("id"),
                    column("parent_id"),
                    primaryKey = CanonicalPrimaryKey(columns = listOf("id")),
                    uniques = listOf(CanonicalUniqueConstraint("children_parent_unique", listOf("parent_id"))),
                    foreignKeys = listOf(
                        CanonicalForeignKey(
                            "children_parent_fk",
                            listOf("parent_id"),
                            tableReference("parents"),
                            listOf("id"),
                        ),
                    ),
                    checks = listOf(CanonicalCheckConstraint("children_id_positive", greaterThan("id", 0))),
                ),
            ),
        )
        val desired = document(
            tables = listOf(
                table("parents", column("id")),
                table(
                    "children",
                    column("id"),
                    column("parent_id"),
                    primaryKey = CanonicalPrimaryKey("children_pk", listOf("id", "parent_id")),
                    uniques = listOf(CanonicalUniqueConstraint("children_parent_unique", listOf("id"))),
                    foreignKeys = listOf(
                        CanonicalForeignKey(
                            "children_parent_fk",
                            listOf("id"),
                            tableReference("parents"),
                            listOf("id"),
                            onDelete = CanonicalForeignKeyAction.Cascade,
                        ),
                    ),
                    checks = listOf(CanonicalCheckConstraint("children_id_positive", greaterThan("id", 1))),
                ),
            ),
        )

        val operations = diffSchema(previous, desired).operations
        assertTrue(operations.any { it is DropPrimaryKey })
        assertTrue(operations.any { it is AddPrimaryKey })
        assertTrue(operations.any { it is DropUnique })
        assertTrue(operations.any { it is AddUnique })
        assertTrue(operations.any { it is DropForeignKey })
        assertTrue(operations.any { it is AddForeignKey })
        assertTrue(operations.any { it is DropCheck })
        assertTrue(operations.any { it is AddCheck })
    }

    @Test
    fun recreatesChangedIndexesAndKeepsIndexExpressionsStructured() {
        val oldIndex = CanonicalIndex(
            name = "users_name_idx",
            table = tableReference("users"),
            unique = false,
            keys = listOf(CanonicalColumnIndexKey(columnReference("users", "name"), CanonicalIndexDirection.Asc)),
            predicate = greaterThan("name", 0),
        )
        val changedIndex = oldIndex.copy(
            unique = true,
            keys = listOf(
                CanonicalExpressionIndexKey(
                    CanonicalFunctionExpression("lower", listOf(columnExpression("name"))),
                    CanonicalIndexDirection.Desc,
                ),
            ),
            predicate = greaterThan("name", 1),
            includedColumns = listOf(columnReference("users", "id")),
            comment = "new index",
        )
        val previous = document(
            tables = listOf(table("users", column("id"), column("name"))),
            indexes = listOf(oldIndex),
        )
        val desired = document(
            tables = listOf(table("users", column("id"), column("name"))),
            indexes = listOf(changedIndex),
        )

        val recreate = diffSchema(previous, desired).operations.single()
        assertIs<RecreateIndex>(recreate)
        assertEquals(changedIndex, recreate.replacement)
    }

    @Test
    fun createsAndDropsIndexesWhoseNamesDoNotMatch() {
        val previous = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(index("users_name_idx", "users", "name")),
        )
        val desired = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(index("users_name_replacement_idx", "users", "name")),
        )

        assertEquals(
            listOf(
                DropIndex(index("users_name_idx", "users", "name")),
                CreateIndex(index("users_name_replacement_idx", "users", "name")),
            ),
            diffSchema(previous, desired).operations,
        )
    }

    @Test
    fun resolvesExplicitIndexRenameWithoutRebuildingTheIndex() {
        val previousIndex = index("users_name_idx", "users", "name")
        val desiredIndex = index("users_display_name_idx", "users", "name")
        val previous = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(previousIndex),
        )
        val desired = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(desiredIndex),
            evolution = CanonicalEvolution(
                indexRenames = listOf(
                    CanonicalIndexRename(CanonicalIndexReference(name = desiredIndex.name), listOf("users_name_idx")),
                ),
            ),
        )

        val plan = diffSchema(previous, desired)

        assertEquals(listOf(RenameIndex(previousIndex, desiredIndex)), plan.operations)
        assertEquals(1, plan.summary.renamed)
        assertEquals(
            plan,
            planJson.decodeFromString(
                MigrationPlan.serializer(),
                planJson.encodeToString(MigrationPlan.serializer(), plan),
            ),
        )
    }

    @Test
    fun treatsAlreadyAppliedIndexRenameHistoryAsNoChange() {
        val currentIndex = index("users_display_name_idx", "users", "name")
        val previous = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(currentIndex),
        )
        val desired = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(currentIndex),
            evolution = CanonicalEvolution(
                indexRenames = listOf(
                    CanonicalIndexRename(
                        CanonicalIndexReference(name = currentIndex.name),
                        listOf("users_name_idx"),
                    ),
                ),
            ),
        )

        assertEquals(MigrationPlan.NoChanges, diffSchema(previous, desired))
    }

    @Test
    fun letsAnExplicitIndexRenameReuseItsPreviousNameForANewIndex() {
        val previousIndex = index("users_name_idx", "users", "name")
        val renamedIndex = index("users_display_name_idx", "users", "name")
        val previous = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(previousIndex),
        )
        val desired = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(previousIndex, renamedIndex),
            evolution = CanonicalEvolution(
                indexRenames = listOf(
                    CanonicalIndexRename(CanonicalIndexReference(name = renamedIndex.name), listOf("users_name_idx")),
                ),
            ),
        )

        assertEquals(
            listOf(
                RenameIndex(previousIndex, renamedIndex),
                CreateIndex(previousIndex),
            ),
            diffSchema(previous, desired).operations,
        )
    }

    @Test
    fun recreatesAnExplicitlyRenamedIndexWhenItsStructureChanges() {
        val previousIndex = index("users_name_idx", "users", "name")
        val desiredIndex = index("users_display_name_idx", "users", "name").copy(unique = true)
        val previous = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(previousIndex),
        )
        val desired = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(desiredIndex),
            evolution = CanonicalEvolution(
                indexRenames = listOf(
                    CanonicalIndexRename(CanonicalIndexReference(name = desiredIndex.name), listOf("users_name_idx")),
                ),
            ),
        )

        assertEquals(
            listOf(RecreateIndex(previousIndex, desiredIndex)),
            diffSchema(previous, desired).operations,
        )
    }

    @Test
    fun rejectsAmbiguousAndConflictingIndexRenameMetadata() {
        val first = index("users_first_idx", "users", "name")
        val second = index("users_second_idx", "users", "name")
        val desired = index("users_display_name_idx", "users", "name")
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                document(
                    tables = listOf(table("users", column("name"))),
                    indexes = listOf(first, second),
                ),
                document(
                    tables = listOf(table("users", column("name"))),
                    indexes = listOf(desired),
                    evolution = CanonicalEvolution(
                        indexRenames = listOf(
                            CanonicalIndexRename(
                                CanonicalIndexReference(name = desired.name),
                                listOf(first.name, second.name),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                document(tables = listOf(table("users", column("name"))), indexes = listOf(first)),
                document(
                    tables = listOf(table("users", column("name"))),
                    indexes = listOf(first, second),
                    evolution = CanonicalEvolution(
                        indexRenames = listOf(
                            CanonicalIndexRename(CanonicalIndexReference(name = first.name), listOf("users_name_idx")),
                            CanonicalIndexRename(CanonicalIndexReference(name = second.name), listOf("users_name_idx")),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun carriesTableAndColumnRenameIdentityThroughAnExplicitIndexRename() {
        val previousIndex = index("users_name_idx", "users", "name")
        val desiredIndex = index("accounts_display_name_idx", "accounts", "display_name")
        val previous = document(
            tables = listOf(table("users", column("name"))),
            indexes = listOf(previousIndex),
        )
        val desired = document(
            tables = listOf(table("accounts", column("display_name"))),
            indexes = listOf(desiredIndex),
            evolution = CanonicalEvolution(
                tableRenames = listOf(CanonicalTableRename(tableReference("accounts"), listOf("users"))),
                columnRenames = listOf(
                    CanonicalColumnRename(tableReference("accounts"), "display_name", listOf("name")),
                ),
                indexRenames = listOf(
                    CanonicalIndexRename(CanonicalIndexReference(name = desiredIndex.name), listOf("users_name_idx")),
                ),
            ),
        )

        assertEquals(
            listOf(
                RenameTable(tableReference("users"), tableReference("accounts")),
                RenameColumn(tableReference("accounts"), "name", "display_name"),
                RenameIndex(
                    previousIndex.copy(
                        table = tableReference("accounts"),
                        keys = listOf(CanonicalColumnIndexKey(columnReference("accounts", "display_name"))),
                    ),
                    desiredIndex,
                ),
            ),
            diffSchema(previous, desired).operations,
        )
    }

    @Test
    fun createsDropsAndOrdersSequencesAndDependentTables() {
        val parent = table("parents", column("id", primaryKey = true))
        val child = table(
            "children",
            column("id"),
            column("parent_id"),
            foreignKeys = listOf(
                CanonicalForeignKey(
                    sourceColumns = listOf("parent_id"),
                    targetTable = tableReference("parents"),
                    targetColumns = listOf("id"),
                ),
            ),
        )
        val plan = diffSchema(
            document(sequences = listOf(CanonicalSequence(name = "old_seq"))),
            document(
                tables = listOf(child, parent),
                sequences = listOf(CanonicalSequence(name = "new_seq")),
            ),
        )

        val operations = plan.operations
        assertIs<CreateSequence>(operations.first())
        val creates = operations.filterIsInstance<CreateTable>()
        assertEquals(listOf("parents", "children"), creates.map { it.table.name })
        assertIs<DropSequence>(operations.last())
    }

    @Test
    fun ordersDependentDropsBeforeColumnAndTableDrops() {
        val previous = document(
            tables = listOf(
                table("parents", column("id", primaryKey = true)),
                table(
                    "children",
                    column("id"),
                    column("parent_id"),
                    foreignKeys = listOf(
                        CanonicalForeignKey(
                            sourceColumns = listOf("parent_id"),
                            targetTable = tableReference("parents"),
                            targetColumns = listOf("id"),
                        ),
                    ),
                ),
            ),
            indexes = listOf(
                CanonicalIndex(
                    name = "children_parent_idx",
                    table = tableReference("children"),
                    unique = false,
                    keys = listOf(CanonicalColumnIndexKey(columnReference("children", "parent_id"))),
                ),
            ),
        )
        val desired = document(tables = listOf(table("children", column("id"))))

        val operations = diffSchema(previous, desired).operations
        assertTrue(operations.indexOfFirst { it is DropForeignKey } < operations.indexOfFirst { it is DropTable })
        assertTrue(operations.indexOfFirst { it is DropIndex } < operations.indexOfFirst { it is DropColumn })
    }

    @Test
    fun summarizesOperationCountsAndIsDeterministicAndSerializable() {
        val previous = document(tables = listOf(table("users", column("legacy"), column("email", nullable = true))))
        val desired = document(tables = listOf(table("users", column("email"), column("created_at", nullable = true))))

        val first = diffSchema(previous, desired)
        val second = diffSchema(previous, desired)
        assertEquals(first, second)
        assertEquals(MigrationPlanSummary(added = 1, removed = 1, changed = 1), first.summary)
        val json = planJson.encodeToString(MigrationPlan.serializer(), first)
        assertFalse(json.contains("\"risk\""))
        assertFalse(json.contains("highestRisk"))
        assertEquals(first, planJson.decodeFromString(MigrationPlan.serializer(), json))
    }

    @Test
    fun producesTheExpectedComplexColumnPlan() {
        val previous = document(
            tables = listOf(
                table(
                    "users",
                    column("id", type = longType, primaryKey = true, identity = CanonicalIdentity()),
                    column("name"),
                    column("legacy_code"),
                    column("email", nullable = true),
                ),
            ),
        )
        val desired = document(
            tables = listOf(
                table(
                    "users",
                    column("id", type = longType, primaryKey = true, identity = CanonicalIdentity()),
                    column("display_name"),
                    column("email"),
                    column(
                        "created_at",
                        type = instantType,
                        default = CanonicalFunctionExpression("currentTimestamp", emptyList()),
                    ),
                ),
            ),
            evolution = CanonicalEvolution(
                columnRenames = listOf(CanonicalColumnRename(tableReference("users"), "display_name", listOf("name"))),
            ),
        )

        assertEquals(
            listOf(
                RenameColumn(tableReference("users"), "name", "display_name"),
                AddColumn(
                    tableReference("users"),
                    column(
                        "created_at",
                        type = instantType,
                        default = CanonicalFunctionExpression("currentTimestamp", emptyList()),
                    ),
                ),
                SetNotNull(columnReference("users", "email")),
                DropColumn(columnReference("users", "legacy_code")),
            ),
            diffSchema(previous, desired).operations,
        )
    }

    private companion object {
        private val planJson = Json { classDiscriminator = "kind" }
        private val stringType = CanonicalColumnType("kagiroi.string")
        private val longType = CanonicalColumnType("kagiroi.int64")
        private val instantType = CanonicalColumnType("kagiroi.instant")

        private fun document(
            tables: List<CanonicalTable> = emptyList(),
            sequences: List<CanonicalSequence> = emptyList(),
            indexes: List<CanonicalIndex> = emptyList(),
            evolution: CanonicalEvolution = CanonicalEvolution(),
        ): CanonicalDesiredSchema {
            val physical = CanonicalPhysicalSchema(tables, sequences, indexes)
            return CanonicalDesiredSchema(
                formatVersion = CANONICAL_SCHEMA_FORMAT_VERSION,
                schema = physical,
                evolution = evolution,
                metadata = CanonicalSchemaMetadata(
                    CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, sha256Hex(encodePhysicalSchema(physical))),
                ),
            )
        }

        private fun table(
            name: String,
            vararg columns: CanonicalColumn,
            schemaName: String? = null,
            comment: String? = null,
            primaryKey: CanonicalPrimaryKey? = null,
            uniques: List<CanonicalUniqueConstraint> = emptyList(),
            foreignKeys: List<CanonicalForeignKey> = emptyList(),
            checks: List<CanonicalCheckConstraint> = emptyList(),
        ): CanonicalTable = CanonicalTable(
            schemaName = schemaName,
            name = name,
            comment = comment,
            columns = columns.toList(),
            primaryKey = primaryKey,
            uniqueConstraints = uniques,
            foreignKeys = foreignKeys,
            checks = checks,
        )

        private fun column(
            name: String,
            type: CanonicalColumnType = stringType,
            nullable: Boolean = false,
            comment: String? = null,
            primaryKey: Boolean = false,
            default: CanonicalSchemaExpression? = null,
            identity: CanonicalIdentity? = null,
            generated: CanonicalGeneratedColumn? = null,
        ): CanonicalColumn = CanonicalColumn(
            name = name,
            type = type,
            nullable = nullable,
            comment = comment,
            primaryKey = primaryKey,
            default = default,
            identity = identity,
            generated = generated,
        )

        private fun tableReference(name: String): CanonicalTableReference = CanonicalTableReference(name = name)

        private fun columnReference(table: String, name: String): CanonicalColumnReference =
            CanonicalColumnReference(tableReference(table), name)

        private fun columnExpression(name: String): CanonicalColumnExpression =
            CanonicalColumnExpression(columnReference("users", name))

        private fun literal(value: String): CanonicalLiteralExpression =
            CanonicalLiteralExpression(CanonicalStringValue(value), stringType)

        private fun generated(value: String): CanonicalGeneratedColumn = CanonicalGeneratedColumn(
            literal(value),
            CanonicalGeneratedColumnStorage.Stored,
        )

        private fun greaterThan(column: String, value: Int): CanonicalValueComparison = CanonicalValueComparison(
            columnExpression(column),
            CanonicalComparisonOperator.Gt,
            CanonicalIntegerValue(value.toString()),
            CanonicalColumnType("kagiroi.int32"),
        )

        private fun index(name: String, table: String, column: String): CanonicalIndex = CanonicalIndex(
            name = name,
            table = tableReference(table),
            unique = false,
            keys = listOf(CanonicalColumnIndexKey(columnReference(table, column))),
        )
    }
}
