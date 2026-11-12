@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "ReturnCount",
    "TooManyFunctions",
)

package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The review level assigned to one semantic migration operation. */
@Serializable
enum class MigrationRisk {
    @SerialName("safe")
    Safe,

    @SerialName("review")
    Review,

    @SerialName("destructive")
    Destructive,
}

/** A deterministic, machine-readable risk level plus the text suitable for a CLI. */
@Serializable
data class MigrationRiskAssessment(val level: MigrationRisk, val reason: String)

/**
 * A pure, dialect-neutral plan for changing one Canonical Schema into another.
 *
 * Operations deliberately express database intent rather than rendered SQL. [NoChanges] makes
 * an identical physical schema explicit, while [Changes] cannot contain an empty operation list.
 */
@Serializable
sealed interface MigrationPlan {
    val operations: List<MigrationOperation>

    val summary: MigrationPlanSummary

    @Serializable
    @SerialName("noChanges")
    data object NoChanges : MigrationPlan {
        override val operations: List<MigrationOperation> = emptyList()
        override val summary: MigrationPlanSummary = MigrationPlanSummary()
    }

    @Serializable
    @SerialName("changes")
    data class Changes(override val operations: List<MigrationOperation>) : MigrationPlan {
        init {
            require(operations.isNotEmpty()) { "A migration plan with changes must contain operations" }
        }

        override val summary: MigrationPlanSummary = migrationPlanSummary(operations)
    }
}

/** Counts the broad kinds of semantic changes and exposes the plan's highest risk. */
@Serializable
data class MigrationPlanSummary(
    val added: Int = 0,
    val removed: Int = 0,
    val renamed: Int = 0,
    val changed: Int = 0,
    val highestRisk: MigrationRisk? = null,
)

/** One semantic operation that a dialect SQL generator may later render. */
@Serializable
sealed interface MigrationOperation {
    val risk: MigrationRiskAssessment
}

/** Creates a table from a full, immutable Canonical Table snapshot. */
@Serializable
@SerialName("createTable")
data class CreateTable(
    val table: CanonicalTable,
    override val risk: MigrationRiskAssessment = safeRisk(
        "Creating table ${table.displayName()} does not remove stored data.",
    ),
) : MigrationOperation

/** Removes a managed table and all data it contains. */
@Serializable
@SerialName("dropTable")
data class DropTable(
    val table: CanonicalTableReference,
    override val risk: MigrationRiskAssessment = destructiveRisk(
        "Dropping table ${table.displayName()} permanently removes its stored data.",
    ),
) : MigrationOperation

/** Renames one table within its existing schema namespace. */
@Serializable
@SerialName("renameTable")
data class RenameTable(
    val from: CanonicalTableReference,
    val to: CanonicalTableReference,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Renaming table ${from.displayName()} to ${to.displayName()} requires deployment compatibility review.",
    ),
) : MigrationOperation

/** Adds a full Canonical Column definition to an existing table. */
@Serializable
@SerialName("addColumn")
data class AddColumn(
    val table: CanonicalTableReference,
    val column: CanonicalColumn,
    override val risk: MigrationRiskAssessment = addColumnRisk(table, column),
) : MigrationOperation

/** Removes one physical column. */
@Serializable
@SerialName("dropColumn")
data class DropColumn(
    val column: CanonicalColumnReference,
    override val risk: MigrationRiskAssessment = destructiveRisk(
        "Dropping column ${column.displayName()} can permanently lose stored data.",
    ),
) : MigrationOperation

/** Renames a column after any prerequisite constraint removals. */
@Serializable
@SerialName("renameColumn")
data class RenameColumn(
    val table: CanonicalTableReference,
    val from: String,
    val to: String,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Renaming column ${table.displayName()}.$from to $to requires deployment compatibility review.",
    ),
) : MigrationOperation

/** Changes the dialect-neutral storage type of an existing column. */
@Serializable
@SerialName("alterColumnType")
data class AlterColumnType(
    val column: CanonicalColumnReference,
    val from: CanonicalColumnType,
    val to: CanonicalColumnType,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Changing the type of ${column.displayName()} may require data conversion.",
    ),
) : MigrationOperation

@Serializable
@SerialName("setNotNull")
data class SetNotNull(
    val column: CanonicalColumnReference,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Making ${column.displayName()} NOT NULL requires existing rows to satisfy the constraint.",
    ),
) : MigrationOperation

@Serializable
@SerialName("dropNotNull")
data class DropNotNull(
    val column: CanonicalColumnReference,
    override val risk: MigrationRiskAssessment = safeRisk(
        "Allowing NULL in ${column.displayName()} does not remove data.",
    ),
) : MigrationOperation

@Serializable
@SerialName("setDefault")
data class SetDefault(
    val column: CanonicalColumnReference,
    val default: CanonicalSchemaExpression,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Adding a default for ${column.displayName()} changes values written by future inserts.",
    ),
) : MigrationOperation

@Serializable
@SerialName("dropDefault")
data class DropDefault(
    val column: CanonicalColumnReference,
    val previous: CanonicalSchemaExpression,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Removing the default for ${column.displayName()} changes future insert behavior.",
    ),
) : MigrationOperation

@Serializable
@SerialName("alterDefault")
data class AlterDefault(
    val column: CanonicalColumnReference,
    val from: CanonicalSchemaExpression,
    val to: CanonicalSchemaExpression,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Changing the default for ${column.displayName()} changes future insert behavior.",
    ),
) : MigrationOperation

@Serializable
@SerialName("alterIdentity")
data class AlterIdentity(
    val column: CanonicalColumnReference,
    val from: CanonicalIdentity? = null,
    val to: CanonicalIdentity? = null,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Changing identity behavior for ${column.displayName()} requires dialect capability review.",
    ),
) : MigrationOperation

@Serializable
@SerialName("alterGeneratedColumn")
data class AlterGeneratedColumn(
    val column: CanonicalColumnReference,
    val from: CanonicalGeneratedColumn? = null,
    val to: CanonicalGeneratedColumn? = null,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Changing generated-column behavior for ${column.displayName()} requires dialect capability review.",
    ),
) : MigrationOperation

@Serializable
@SerialName("alterColumnComment")
data class AlterColumnComment(
    val column: CanonicalColumnReference,
    val from: String? = null,
    val to: String? = null,
    override val risk: MigrationRiskAssessment = safeRisk(
        "Changing the comment on ${column.displayName()} does not alter data.",
    ),
) : MigrationOperation

@Serializable
@SerialName("alterTableComment")
data class AlterTableComment(
    val table: CanonicalTableReference,
    val from: String? = null,
    val to: String? = null,
    override val risk: MigrationRiskAssessment = safeRisk(
        "Changing the comment on ${table.displayName()} does not alter data.",
    ),
) : MigrationOperation

@Serializable
@SerialName("addPrimaryKey")
data class AddPrimaryKey(
    val table: CanonicalTableReference,
    val primaryKey: CanonicalPrimaryKey,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Adding a primary key to ${table.displayName()} requires existing rows to be unique and non-null.",
    ),
) : MigrationOperation

@Serializable
@SerialName("dropPrimaryKey")
data class DropPrimaryKey(
    val table: CanonicalTableReference,
    val primaryKey: CanonicalPrimaryKey,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Removing the primary key from ${table.displayName()} changes data integrity guarantees.",
    ),
) : MigrationOperation

@Serializable
@SerialName("addUnique")
data class AddUnique(
    val table: CanonicalTableReference,
    val unique: CanonicalUniqueConstraint,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Adding a UNIQUE constraint to ${table.displayName()} requires existing rows to be unique.",
    ),
) : MigrationOperation

@Serializable
@SerialName("dropUnique")
data class DropUnique(
    val table: CanonicalTableReference,
    val unique: CanonicalUniqueConstraint,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Removing a UNIQUE constraint from ${table.displayName()} changes data integrity guarantees.",
    ),
) : MigrationOperation

@Serializable
@SerialName("addForeignKey")
data class AddForeignKey(
    val table: CanonicalTableReference,
    val foreignKey: CanonicalForeignKey,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Adding a foreign key to ${table.displayName()} requires existing rows to satisfy the reference.",
    ),
) : MigrationOperation

@Serializable
@SerialName("dropForeignKey")
data class DropForeignKey(
    val table: CanonicalTableReference,
    val foreignKey: CanonicalForeignKey,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Removing a foreign key from ${table.displayName()} changes data integrity guarantees.",
    ),
) : MigrationOperation

@Serializable
@SerialName("addCheck")
data class AddCheck(
    val table: CanonicalTableReference,
    val check: CanonicalCheckConstraint,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Adding a CHECK constraint to ${table.displayName()} requires existing rows to satisfy it.",
    ),
) : MigrationOperation

@Serializable
@SerialName("dropCheck")
data class DropCheck(
    val table: CanonicalTableReference,
    val check: CanonicalCheckConstraint,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Removing a CHECK constraint from ${table.displayName()} changes data integrity guarantees.",
    ),
) : MigrationOperation

@Serializable
@SerialName("createIndex")
data class CreateIndex(
    val index: CanonicalIndex,
    override val risk: MigrationRiskAssessment = safeRisk(
        "Creating index ${index.displayName()} does not remove data.",
    ),
) : MigrationOperation

@Serializable
@SerialName("dropIndex")
data class DropIndex(
    val index: CanonicalIndex,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Dropping index ${index.displayName()} can change query performance and constraint support.",
    ),
) : MigrationOperation

/** Renames an unchanged index when explicit evolution metadata identifies its previous name. */
@Serializable
@SerialName("renameIndex")
data class RenameIndex(
    val from: CanonicalIndex,
    val to: CanonicalIndex,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Renaming index ${from.displayName()} to ${to.displayName()} requires dialect capability review.",
    ),
) : MigrationOperation

/** Replaces an index when its physical definition changes. */
@Serializable
@SerialName("recreateIndex")
data class RecreateIndex(
    val previous: CanonicalIndex,
    val replacement: CanonicalIndex,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Recreating index ${replacement.displayName()} can affect query performance while it is rebuilt.",
    ),
) : MigrationOperation

@Serializable
@SerialName("createSequence")
data class CreateSequence(
    val sequence: CanonicalSequence,
    override val risk: MigrationRiskAssessment = safeRisk(
        "Creating sequence ${sequence.displayName()} does not remove stored data.",
    ),
) : MigrationOperation

@Serializable
@SerialName("dropSequence")
data class DropSequence(
    val sequence: CanonicalSequence,
    override val risk: MigrationRiskAssessment = destructiveRisk(
        "Dropping sequence ${sequence.displayName()} permanently removes its current value state.",
    ),
) : MigrationOperation

/** Reserved for sequence options when Canonical Sequence grows configurable properties. */
@Serializable
@SerialName("alterSequence")
data class AlterSequence(
    val from: CanonicalSequence,
    val to: CanonicalSequence,
    override val risk: MigrationRiskAssessment = reviewRisk(
        "Changing sequence ${to.displayName()} requires dialect capability review.",
    ),
) : MigrationOperation

/**
 * Diffs two versioned Canonical Schema documents without reading a database, filesystem, process,
 * or runtime Table object.
 *
 * The previous document's evolution metadata is deliberately ignored. Only [desired]'s explicit
 * lineage hints may resolve an otherwise unmatched previous physical object.
 */
fun diffSchema(previous: CanonicalDesiredSchema, desired: CanonicalDesiredSchema): MigrationPlan {
    previous.requireUsableForDiff("previous")
    desired.requireUsableForDiff("desired")
    val desiredEvolution = desired.validateEvolution()
    val tableMatching = resolveTableMatching(
        previous.schema.tables.sortedWith(canonicalTableComparator),
        desired.schema.tables.sortedWith(canonicalTableComparator),
        desiredEvolution,
    )
    val columnMatchings = tableMatching.matched.map { tableMatch ->
        resolveColumnMatching(tableMatch, desiredEvolution)
    }
    val tableReferences = tableMatching.matched.associate { match ->
        match.previous.reference() to match.desired.reference()
    }
    val columnReferences = columnMatchings.flatMap { it.matched }.associate { match ->
        CanonicalColumnReference(match.table.previous.reference(), match.previous.name) to
            CanonicalColumnReference(match.table.desired.reference(), match.desired.name)
    }

    val sequenceOperations = diffSequences(
        previous.schema.sequences.sortedWith(canonicalSequenceComparator),
        desired.schema.sequences.sortedWith(canonicalSequenceComparator),
    )
    val tableRenameOperations = tableMatching.matched.filter(TableMatch::renamed).map { match ->
        RenameTable(match.previous.reference(), match.desired.reference())
    }
    val constraintOperations = diffConstraints(columnMatchings, tableReferences, columnReferences)
    val indexOperations = diffIndexes(
        previous.schema.indexes.sortedWith(canonicalIndexComparator),
        desired.schema.indexes.sortedWith(canonicalIndexComparator),
        tableMatching,
        tableReferences,
        columnReferences,
        desiredEvolution,
    )
    val columnOperations = diffColumns(columnMatchings, columnReferences)

    val security = diffSecurityObjects(previous.schema, desired.schema)
    val operations = buildList {
        addAll(security.drop)
        addAll(sequenceOperations.create)
        addAll(tableRenameOperations)
        addAll(constraintOperations.dropForeignKeys)
        addAll(indexOperations.drop)
        addAll(constraintOperations.dropOther)
        addAll(columnOperations.rename)
        addAll(columnOperations.add)
        addAll(columnOperations.alter)
        addAll(columnOperations.tableComments)
        addAll(indexOperations.recreate)
        addAll(indexOperations.rename)
        addAll(columnOperations.drop)
        addAll(constraintOperations.addOther)
        addAll(orderCreatedTables(tableMatching.created).map(::CreateTable))
        addAll(constraintOperations.addForeignKeys)
        addAll(indexOperations.create)
        addAll(security.create)
        addAll(tableMatching.dropped.map { table -> DropTable(table.reference()) })
        addAll(sequenceOperations.drop)
    }
    return if (operations.isEmpty()) MigrationPlan.NoChanges else MigrationPlan.Changes(operations)
}

private data class EvolutionHints(
    val tableHistory: Map<CanonicalTableReference, List<String>>,
    val columnHistory: Map<CanonicalColumnReference, List<String>>,
    val indexHistory: Map<CanonicalIndexReference, List<String>>,
)

private data class TableMatch(val previous: CanonicalTable, val desired: CanonicalTable, val renamed: Boolean)

private data class TableMatching(
    val matched: List<TableMatch>,
    val created: List<CanonicalTable>,
    val dropped: List<CanonicalTable>,
)

private data class ColumnMatch(
    val table: TableMatch,
    val previous: CanonicalColumn,
    val desired: CanonicalColumn,
    val renamed: Boolean,
)

private data class ColumnMatching(
    val table: TableMatch,
    val matched: List<ColumnMatch>,
    val added: List<CanonicalColumn>,
    val dropped: List<CanonicalColumn>,
)

private data class IndexMatch(val previous: CanonicalIndex, val desired: CanonicalIndex, val renamed: Boolean)

private data class IndexMatching(
    val matched: List<IndexMatch>,
    val created: List<CanonicalIndex>,
    val dropped: List<CanonicalIndex>,
)

private data class SequenceOperations(val create: List<CreateSequence>, val drop: List<DropSequence>)

private data class ConstraintOperations(
    val dropForeignKeys: List<DropForeignKey>,
    val dropOther: List<MigrationOperation>,
    val addOther: List<MigrationOperation>,
    val addForeignKeys: List<AddForeignKey>,
)

private data class IndexOperations(
    val drop: List<DropIndex>,
    val recreate: List<RecreateIndex>,
    val rename: List<RenameIndex>,
    val create: List<CreateIndex>,
)

private data class ColumnOperations(
    val rename: List<RenameColumn>,
    val add: List<AddColumn>,
    val alter: List<MigrationOperation>,
    val tableComments: List<AlterTableComment>,
    val drop: List<DropColumn>,
)

private fun CanonicalDesiredSchema.requireUsableForDiff(label: String) {
    require(formatVersion == canonicalSchemaVersion(schema)) {
        "Unsupported $label Canonical Schema format version: $formatVersion"
    }
    require(metadata.physicalSchemaHash.algorithm == CanonicalHashAlgorithm.Sha256) {
        "Unsupported $label Canonical Schema hash algorithm: ${metadata.physicalSchemaHash.algorithm}"
    }
    require(metadata.physicalSchemaHash.value == physicalSchemaHash(this)) {
        "$label Canonical Schema physicalSchemaHash does not match schema"
    }
    validateSecurityObjects(schema)
    schema.requireDistinctPhysicalNames(label)
}

private fun CanonicalPhysicalSchema.requireDistinctPhysicalNames(label: String) {
    require(tables.map(CanonicalTable::reference).distinct().size == tables.size) {
        "$label Canonical Schema contains duplicate table names"
    }
    require(sequences.map(CanonicalSequence::reference).distinct().size == sequences.size) {
        "$label Canonical Schema contains duplicate sequence names"
    }
    require(indexes.map(CanonicalIndex::reference).distinct().size == indexes.size) {
        "$label Canonical Schema contains duplicate index names"
    }
    tables.forEach { table ->
        require(table.columns.map(CanonicalColumn::name).distinct().size == table.columns.size) {
            "$label Canonical Schema contains duplicate columns in ${table.displayName()}"
        }
    }
}

private fun CanonicalDesiredSchema.validateEvolution(): EvolutionHints {
    val tableByReference = schema.tables.associateBy(CanonicalTable::reference)
    val indexByReference = schema.indexes.associateBy(CanonicalIndex::reference)
    val tableHistory = buildMap {
        evolution.tableRenames.forEach { rename ->
            val current = rename.current
            val table = requireNotNull(tableByReference[current]) {
                "Table rename metadata refers to unmanaged table ${current.displayName()}"
            }
            validateHistory(rename.previous, table.name, "table ${current.displayName()}")
            require(put(current, rename.previous) == null) {
                "Table rename metadata repeats current table ${current.displayName()}"
            }
        }
    }
    val tableClaims = mutableMapOf<Pair<String?, String>, CanonicalTableReference>()
    tableHistory.forEach { (current, history) ->
        history.forEach { previous ->
            val claim = current.schemaName to previous
            val owner = tableClaims.put(claim, current)
            require(owner == null || owner == current) {
                "Ambiguous table rename metadata: ${previous.displayName(current.schemaName)} is claimed by " +
                    "${owner?.displayName()} and ${current.displayName()}"
            }
        }
    }

    val columnHistory = buildMap {
        evolution.columnRenames.forEach { rename ->
            val table = requireNotNull(tableByReference[rename.table]) {
                "Column rename metadata refers to unmanaged table ${rename.table.displayName()}"
            }
            require(table.columns.any { it.name == rename.current }) {
                "Column rename metadata refers to missing column ${rename.table.displayName()}.${rename.current}"
            }
            validateHistory(rename.previous, rename.current, "column ${rename.table.displayName()}.${rename.current}")
            val current = CanonicalColumnReference(rename.table, rename.current)
            require(put(current, rename.previous) == null) {
                "Column rename metadata repeats current column ${current.displayName()}"
            }
        }
    }
    val columnClaims = mutableMapOf<Pair<CanonicalTableReference, String>, CanonicalColumnReference>()
    columnHistory.forEach { (current, history) ->
        history.forEach { previous ->
            val claim = current.table to previous
            val owner = columnClaims.put(claim, current)
            require(owner == null || owner == current) {
                "Ambiguous column rename metadata: ${current.table.displayName()}.$previous is claimed by " +
                    "${owner?.displayName()} and ${current.displayName()}"
            }
        }
    }

    val indexHistory = buildMap {
        evolution.indexRenames.forEach { rename ->
            val index = requireNotNull(indexByReference[rename.current]) {
                "Index rename metadata refers to unmanaged index ${rename.current.displayName()}"
            }
            validateHistory(rename.previous, index.name, "index ${rename.current.displayName()}")
            require(put(rename.current, rename.previous) == null) {
                "Index rename metadata repeats current index ${rename.current.displayName()}"
            }
        }
    }
    val indexClaims = mutableMapOf<Pair<String?, String>, CanonicalIndexReference>()
    indexHistory.forEach { (current, history) ->
        history.forEach { previous ->
            val claim = current.schemaName to previous
            val owner = indexClaims.put(claim, current)
            require(owner == null || owner == current) {
                "Ambiguous index rename metadata: ${previous.displayName(current.schemaName)} is claimed by " +
                    "${owner?.displayName()} and ${current.displayName()}"
            }
        }
    }
    return EvolutionHints(tableHistory, columnHistory, indexHistory)
}

private fun validateHistory(previous: List<String>, current: String, target: String) {
    require(previous.isNotEmpty()) { "Rename metadata for $target must contain at least one previous name" }
    require(previous.all(String::isNotBlank)) { "Rename metadata for $target cannot contain a blank previous name" }
    require(previous.none { it == current }) { "Rename metadata for $target cannot contain its current name" }
    require(previous.distinct().size == previous.size) { "Rename metadata for $target cannot repeat previous names" }
}

private fun resolveTableMatching(
    previous: List<CanonicalTable>,
    desired: List<CanonicalTable>,
    evolution: EvolutionHints,
): TableMatching {
    val previousByReference = previous.associateBy(CanonicalTable::reference)
    val usedPrevious = mutableSetOf<CanonicalTableReference>()
    val renameMatches = mutableMapOf<CanonicalTableReference, TableMatch>()
    desired.filter { desiredTable -> previousByReference[desiredTable.reference()] == null }.forEach { desiredTable ->
        val history = evolution.tableHistory[desiredTable.reference()].orEmpty()
        val candidates = previous.filter { candidate ->
            candidate.schemaName == desiredTable.schemaName &&
                candidate.name in history
        }
        require(candidates.size <= 1) {
            "Ambiguous table rename for ${desiredTable.displayName()}: previous schema contains " +
                candidates.joinToString { it.displayName() }
        }
        val source = candidates.singleOrNull()
        if (source == null) {
            val crossSchemaSource = previous.firstOrNull { candidate ->
                candidate.reference() !in usedPrevious &&
                    candidate.schemaName != desiredTable.schemaName &&
                    candidate.name in history
            }
            require(crossSchemaSource == null) {
                "Schema moves are not supported: ${crossSchemaSource?.displayName()} cannot be renamed to " +
                    desiredTable.displayName()
            }
        } else {
            require(usedPrevious.add(source.reference())) {
                "Ambiguous table rename for ${desiredTable.displayName()}: ${source.displayName()} is claimed twice"
            }
            renameMatches[desiredTable.reference()] = TableMatch(source, desiredTable, renamed = true)
        }
    }
    val matches = mutableListOf<TableMatch>()
    val created = mutableListOf<CanonicalTable>()
    desired.forEach { desiredTable ->
        val renamed = renameMatches[desiredTable.reference()]
        if (renamed != null) {
            matches += renamed
            return@forEach
        }
        val direct = previousByReference[desiredTable.reference()]
        if (direct != null && usedPrevious.add(direct.reference())) {
            matches += TableMatch(direct, desiredTable, renamed = false)
        } else {
            created += desiredTable
        }
    }
    return TableMatching(
        matched = matches,
        created = created,
        dropped = previous.filter { it.reference() !in usedPrevious },
    )
}

private fun resolveColumnMatching(table: TableMatch, evolution: EvolutionHints): ColumnMatching {
    val previousByName = table.previous.columns.associateBy(CanonicalColumn::name)
    val usedPrevious = mutableSetOf<String>()
    val renameMatches = mutableMapOf<String, ColumnMatch>()
    table.desired.columns.filter { desiredColumn ->
        previousByName[desiredColumn.name] == null
    }.forEach { desiredColumn ->
        val history = evolution.columnHistory[
            CanonicalColumnReference(table.desired.reference(), desiredColumn.name),
        ].orEmpty()
        val candidates = table.previous.columns.filter { candidate ->
            candidate.name in history
        }
        require(candidates.size <= 1) {
            "Ambiguous column rename for ${table.desired.displayName()}.${desiredColumn.name}: " +
                "previous table contains " +
                candidates.joinToString { it.name }
        }
        val source = candidates.singleOrNull()
        if (source != null) {
            require(usedPrevious.add(source.name)) {
                "Ambiguous column rename for ${table.desired.displayName()}.${desiredColumn.name}: " +
                    "${source.name} is claimed twice"
            }
            renameMatches[desiredColumn.name] = ColumnMatch(table, source, desiredColumn, renamed = true)
        }
    }
    val matches = mutableListOf<ColumnMatch>()
    val added = mutableListOf<CanonicalColumn>()
    table.desired.columns.forEach { desiredColumn ->
        val renamed = renameMatches[desiredColumn.name]
        if (renamed != null) {
            matches += renamed
            return@forEach
        }
        val direct = previousByName[desiredColumn.name]
        if (direct != null && usedPrevious.add(direct.name)) {
            matches += ColumnMatch(table, direct, desiredColumn, renamed = false)
        } else {
            added += desiredColumn
        }
    }
    return ColumnMatching(
        table = table,
        matched = matches,
        added = added,
        dropped = table.previous.columns.filter { it.name !in usedPrevious },
    )
}

private fun resolveIndexMatching(
    previous: List<CanonicalIndex>,
    desired: List<CanonicalIndex>,
    evolution: EvolutionHints,
    tableReferences: Map<CanonicalTableReference, CanonicalTableReference>,
): IndexMatching {
    val previousByReference = previous.associateBy(CanonicalIndex::reference)
    val usedPrevious = mutableSetOf<CanonicalIndexReference>()
    val renameMatches = mutableMapOf<CanonicalIndexReference, IndexMatch>()
    desired.filter { desiredIndex -> previousByReference[desiredIndex.reference()] == null }.forEach { desiredIndex ->
        val history = evolution.indexHistory[desiredIndex.reference()].orEmpty()
        val candidates = previous.filter { candidate ->
            candidate.schemaName == desiredIndex.schemaName &&
                candidate.name in history &&
                candidate.remapTables(tableReferences).table == desiredIndex.table
        }
        require(candidates.size <= 1) {
            "Ambiguous index rename for ${desiredIndex.displayName()}: previous schema contains " +
                candidates.joinToString { it.displayName() }
        }
        val source = candidates.singleOrNull()
        if (source == null) {
            val sourceOnAnotherTable = previous.firstOrNull { candidate ->
                candidate.reference() !in usedPrevious &&
                    candidate.schemaName == desiredIndex.schemaName &&
                    candidate.name in history
            }
            require(sourceOnAnotherTable == null) {
                "Index moves are not supported: ${sourceOnAnotherTable?.displayName()} cannot be renamed to " +
                    desiredIndex.displayName()
            }
        } else {
            require(usedPrevious.add(source.reference())) {
                "Ambiguous index rename for ${desiredIndex.displayName()}: ${source.displayName()} is claimed twice"
            }
            renameMatches[desiredIndex.reference()] = IndexMatch(source, desiredIndex, renamed = true)
        }
    }
    val matches = mutableListOf<IndexMatch>()
    val created = mutableListOf<CanonicalIndex>()
    desired.forEach { desiredIndex ->
        val renamed = renameMatches[desiredIndex.reference()]
        if (renamed != null) {
            matches += renamed
            return@forEach
        }
        val direct = previousByReference[desiredIndex.reference()]
        if (direct != null && usedPrevious.add(direct.reference())) {
            matches += IndexMatch(direct, desiredIndex, renamed = false)
        } else {
            created += desiredIndex
        }
    }
    return IndexMatching(
        matched = matches,
        created = created,
        dropped = previous.filter { it.reference() !in usedPrevious },
    )
}

private fun diffSequences(previous: List<CanonicalSequence>, desired: List<CanonicalSequence>): SequenceOperations {
    val previousByReference = previous.associateBy(CanonicalSequence::reference)
    val desiredByReference = desired.associateBy(CanonicalSequence::reference)
    return SequenceOperations(
        create = desired.filter { it.reference() !in previousByReference }.map(::CreateSequence),
        drop = previous.filter { it.reference() !in desiredByReference }.map(::DropSequence),
    )
}

private fun diffColumns(
    matching: List<ColumnMatching>,
    columnReferences: Map<CanonicalColumnReference, CanonicalColumnReference>,
): ColumnOperations {
    val rename = matching.flatMap { columns ->
        columns.matched.filter(ColumnMatch::renamed).map { match ->
            RenameColumn(match.table.desired.reference(), match.previous.name, match.desired.name)
        }
    }
    val add = matching.flatMap { columns ->
        columns.added.map { column -> AddColumn(columns.table.desired.reference(), column) }
    }
    val alter = matching.flatMap { columns ->
        columns.matched.flatMap { match -> diffColumnProperties(match, columnReferences) }
    }
    val comments = matching.mapNotNull { columns ->
        columns.table.takeIf { it.previous.comment != it.desired.comment }?.let { table ->
            AlterTableComment(table.desired.reference(), table.previous.comment, table.desired.comment)
        }
    }
    val drop = matching.flatMap { columns ->
        columns.dropped.map { column ->
            DropColumn(CanonicalColumnReference(columns.table.desired.reference(), column.name))
        }
    }
    return ColumnOperations(rename, add, alter, comments, drop)
}

private fun diffColumnProperties(
    match: ColumnMatch,
    columnReferences: Map<CanonicalColumnReference, CanonicalColumnReference>,
): List<MigrationOperation> {
    val previous = match.previous
    val desired = match.desired
    val reference = CanonicalColumnReference(match.table.desired.reference(), desired.name)
    return buildList {
        if (previous.type != desired.type) add(AlterColumnType(reference, previous.type, desired.type))
        if (previous.nullable && !desired.nullable) add(SetNotNull(reference))
        if (!previous.nullable && desired.nullable) add(DropNotNull(reference))
        val previousDefault = previous.default?.remapColumns(columnReferences)
        when {
            previousDefault == null && desired.default != null -> add(SetDefault(reference, desired.default))

            previousDefault != null && desired.default == null -> add(DropDefault(reference, previousDefault))

            previousDefault != null && previousDefault != desired.default -> {
                add(AlterDefault(reference, previousDefault, requireNotNull(desired.default)))
            }
        }
        if (previous.identity != desired.identity) add(AlterIdentity(reference, previous.identity, desired.identity))
        val previousGenerated = previous.generated?.remapColumns(columnReferences)
        if (previousGenerated != desired.generated) {
            add(AlterGeneratedColumn(reference, previousGenerated, desired.generated))
        }
        if (previous.comment != desired.comment) {
            add(AlterColumnComment(reference, previous.comment, desired.comment))
        }
    }
}

private fun diffConstraints(
    matching: List<ColumnMatching>,
    tableReferences: Map<CanonicalTableReference, CanonicalTableReference>,
    columnReferences: Map<CanonicalColumnReference, CanonicalColumnReference>,
): ConstraintOperations {
    val dropForeignKeys = mutableListOf<DropForeignKey>()
    val dropOther = mutableListOf<MigrationOperation>()
    val addOther = mutableListOf<MigrationOperation>()
    val addForeignKeys = mutableListOf<AddForeignKey>()
    matching.forEach { columns ->
        val table = columns.table
        val currentTable = table.desired.reference()
        val previousPrimary = table.previous.effectivePrimaryKey()
        val normalizedPrimary = previousPrimary?.remapColumns(table.previous.reference(), columnReferences)
        if (normalizedPrimary != table.desired.effectivePrimaryKey()) {
            previousPrimary?.let { dropOther += DropPrimaryKey(currentTable, it) }
            table.desired.effectivePrimaryKey()?.let { addOther += AddPrimaryKey(currentTable, it) }
        }
        diffConstraintLists(
            previous = table.previous.effectiveUniqueConstraints(),
            desired = table.desired.effectiveUniqueConstraints(),
            normalize = { it.remapColumns(table.previous.reference(), columnReferences) },
            drop = { DropUnique(currentTable, it) },
            add = { AddUnique(currentTable, it) },
            drops = dropOther,
            adds = addOther,
        )
        diffConstraintLists(
            previous = table.previous.checks,
            desired = table.desired.checks,
            normalize = { it.remapColumns(columnReferences) },
            drop = { DropCheck(currentTable, it.remapTables(tableReferences)) },
            add = { AddCheck(currentTable, it) },
            drops = dropOther,
            adds = addOther,
        )
        diffConstraintLists(
            previous = table.previous.foreignKeys,
            desired = table.desired.foreignKeys,
            normalize = { it.remap(table.previous.reference(), tableReferences, columnReferences) },
            drop = { DropForeignKey(currentTable, it.remapTables(tableReferences)) },
            add = { AddForeignKey(currentTable, it) },
            drops = dropForeignKeys,
            adds = addForeignKeys,
        )
    }
    return ConstraintOperations(dropForeignKeys, dropOther, addOther, addForeignKeys)
}

private fun <T, Drop : MigrationOperation, Add : MigrationOperation> diffConstraintLists(
    previous: List<T>,
    desired: List<T>,
    normalize: (T) -> T,
    drop: (T) -> Drop,
    add: (T) -> Add,
    drops: MutableList<in Drop>,
    adds: MutableList<in Add>,
) {
    val unmatchedDesired = desired.toMutableList()
    previous.forEach { previousValue ->
        val match = unmatchedDesired.indexOfFirst { desiredValue -> normalize(previousValue) == desiredValue }
        if (match < 0) {
            drops += drop(previousValue)
        } else {
            unmatchedDesired.removeAt(match)
        }
    }
    unmatchedDesired.forEach { adds += add(it) }
}

private fun diffIndexes(
    previous: List<CanonicalIndex>,
    desired: List<CanonicalIndex>,
    tables: TableMatching,
    tableReferences: Map<CanonicalTableReference, CanonicalTableReference>,
    columnReferences: Map<CanonicalColumnReference, CanonicalColumnReference>,
    evolution: EvolutionHints,
): IndexOperations {
    val droppedTableReferences = tables.dropped.map(CanonicalTable::reference).toSet()
    val createdTableReferences = tables.created.map(CanonicalTable::reference).toSet()
    val matching = resolveIndexMatching(previous, desired, evolution, tableReferences)
    val drop = mutableListOf<DropIndex>()
    val recreate = mutableListOf<RecreateIndex>()
    val rename = mutableListOf<RenameIndex>()
    val createReferences = matching.created.mapTo(mutableSetOf()) { index -> index.reference() }
    matching.dropped.forEach { previousIndex ->
        if (previousIndex.table !in droppedTableReferences) {
            drop += DropIndex(previousIndex.remapTables(tableReferences))
        }
    }
    matching.matched.forEach { match ->
        val previousIndex = match.previous
        val desiredIndex = match.desired
        val normalized = previousIndex.remap(tableReferences, columnReferences)
        val changesOwner = normalized.table != desiredIndex.table
        val mustCreateSeparately = previousIndex.table in droppedTableReferences ||
            desiredIndex.table in createdTableReferences ||
            changesOwner
        if (mustCreateSeparately) {
            if (previousIndex.table !in droppedTableReferences) {
                drop += DropIndex(previousIndex.remapTables(tableReferences))
            }
            createReferences += desiredIndex.reference()
        } else if (match.renamed && normalized.copy(name = desiredIndex.name) == desiredIndex) {
            rename += RenameIndex(normalized, desiredIndex)
        } else if (normalized != desiredIndex) {
            recreate += RecreateIndex(normalized, desiredIndex)
        }
    }
    val create = desired.filter { it.reference() in createReferences }.map(::CreateIndex)
    return IndexOperations(drop, recreate, rename, create)
}

private fun orderCreatedTables(created: List<CanonicalTable>): List<CanonicalTable> {
    val remaining = created.associateBy(CanonicalTable::reference).toMutableMap()
    val ordered = mutableListOf<CanonicalTable>()
    while (remaining.isNotEmpty()) {
        val ready = remaining.values.filter { table ->
            table.foreignKeys.none { foreignKey -> foreignKey.targetTable in remaining }
        }.sortedWith(canonicalTableComparator)
        if (ready.isEmpty()) {
            ordered += remaining.values.sortedWith(canonicalTableComparator)
            break
        }
        ordered += ready
        ready.forEach { remaining.remove(it.reference()) }
    }
    return ordered
}

private fun CanonicalTable.effectivePrimaryKey(): CanonicalPrimaryKey? = primaryKey ?: columns
    .filter(CanonicalColumn::primaryKey)
    .map(CanonicalColumn::name)
    .takeIf(List<String>::isNotEmpty)
    ?.let { columns -> CanonicalPrimaryKey(columns = columns) }

private fun CanonicalTable.effectiveUniqueConstraints(): List<CanonicalUniqueConstraint> =
    uniqueConstraints + columns.filter(CanonicalColumn::unique).map { column ->
        CanonicalUniqueConstraint(columns = listOf(column.name))
    }

private fun CanonicalPrimaryKey.remapColumns(
    table: CanonicalTableReference,
    references: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalPrimaryKey = copy(
    columns = columns.map { name -> references[CanonicalColumnReference(table, name)]?.name ?: name },
)

private fun CanonicalUniqueConstraint.remapColumns(
    table: CanonicalTableReference,
    references: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalUniqueConstraint = copy(
    columns = columns.map { name -> references[CanonicalColumnReference(table, name)]?.name ?: name },
)

private fun CanonicalForeignKey.remap(
    sourceTable: CanonicalTableReference,
    tables: Map<CanonicalTableReference, CanonicalTableReference>,
    columns: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalForeignKey = copy(
    sourceColumns = sourceColumns.map { name ->
        columns[CanonicalColumnReference(sourceTable, name)]?.name ?: name
    },
    targetTable = tables[targetTable] ?: targetTable,
    targetColumns = targetColumns.map { name ->
        columns[CanonicalColumnReference(targetTable, name)]?.name ?: name
    },
)

private fun CanonicalForeignKey.remapTables(
    tables: Map<CanonicalTableReference, CanonicalTableReference>,
): CanonicalForeignKey = copy(targetTable = tables[targetTable] ?: targetTable)

private fun CanonicalCheckConstraint.remapColumns(
    columns: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalCheckConstraint = copy(expression = expression.remapColumns(columns))

private fun CanonicalCheckConstraint.remapTables(
    tables: Map<CanonicalTableReference, CanonicalTableReference>,
): CanonicalCheckConstraint = copy(expression = expression.remapTables(tables))

private fun CanonicalIndex.remap(
    tables: Map<CanonicalTableReference, CanonicalTableReference>,
    columns: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalIndex = copy(
    table = tables[table] ?: table,
    keys = keys.map { it.remapColumns(columns) },
    predicate = predicate?.remapColumns(columns),
    includedColumns = includedColumns.map { columns[it] ?: it },
)

private fun CanonicalIndex.remapTables(tables: Map<CanonicalTableReference, CanonicalTableReference>): CanonicalIndex =
    copy(
        table = tables[table] ?: table,
        keys = keys.map { it.remapTables(tables) },
        predicate = predicate?.remapTables(tables),
        includedColumns = includedColumns.map { it.copy(table = tables[it.table] ?: it.table) },
    )

private fun CanonicalIndexKey.remapColumns(
    columns: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalIndexKey = when (this) {
    is CanonicalColumnIndexKey -> copy(column = columns[column] ?: column)
    is CanonicalExpressionIndexKey -> copy(expression = expression.remapColumns(columns))
}

private fun CanonicalIndexKey.remapTables(
    tables: Map<CanonicalTableReference, CanonicalTableReference>,
): CanonicalIndexKey = when (this) {
    is CanonicalColumnIndexKey -> copy(column = column.copy(table = tables[column.table] ?: column.table))
    is CanonicalExpressionIndexKey -> copy(expression = expression.remapTables(tables))
}

private fun CanonicalGeneratedColumn.remapColumns(
    columns: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalGeneratedColumn = copy(expression = expression.remapColumns(columns))

private fun CanonicalSchemaExpression.remapColumns(
    columns: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalSchemaExpression = when (this) {
    is CanonicalColumnExpression -> copy(column = columns[column] ?: column)

    is CanonicalLiteralExpression, is CanonicalSequenceNextValueExpression -> this

    is CanonicalFunctionExpression -> copy(arguments = arguments.map { it.remapColumns(columns) })

    is CanonicalBinaryExpression -> copy(left = left.remapColumns(columns), right = right.remapColumns(columns))

    is CanonicalCastExpression -> copy(expression = expression.remapColumns(columns))

    is CanonicalCaseExpression -> copy(
        branches = branches.map { branch ->
            branch.copy(
                condition = branch.condition.remapColumns(columns),
                result = branch.result.remapColumns(columns),
            )
        },
        elseResult = elseResult.remapColumns(columns),
    )
}

private fun CanonicalSchemaExpression.remapTables(
    tables: Map<CanonicalTableReference, CanonicalTableReference>,
): CanonicalSchemaExpression = when (this) {
    is CanonicalColumnExpression -> copy(column = column.copy(table = tables[column.table] ?: column.table))

    is CanonicalLiteralExpression, is CanonicalSequenceNextValueExpression -> this

    is CanonicalFunctionExpression -> copy(arguments = arguments.map { it.remapTables(tables) })

    is CanonicalBinaryExpression -> copy(left = left.remapTables(tables), right = right.remapTables(tables))

    is CanonicalCastExpression -> copy(expression = expression.remapTables(tables))

    is CanonicalCaseExpression -> copy(
        branches = branches.map { branch ->
            branch.copy(condition = branch.condition.remapTables(tables), result = branch.result.remapTables(tables))
        },
        elseResult = elseResult.remapTables(tables),
    )
}

private fun CanonicalSchemaPredicate.remapColumns(
    columns: Map<CanonicalColumnReference, CanonicalColumnReference>,
): CanonicalSchemaPredicate = when (this) {
    is CanonicalValueComparison -> copy(expression = expression.remapColumns(columns))

    is CanonicalExpressionComparison -> copy(left = left.remapColumns(columns), right = right.remapColumns(columns))

    is CanonicalNullSafeComparison -> copy(left = left.remapColumns(columns), right = right.remapColumns(columns))

    is CanonicalLikeValuePredicate -> copy(expression = expression.remapColumns(columns))

    is CanonicalLikeExpressionPredicate -> copy(
        expression = expression.remapColumns(columns),
        pattern = pattern.remapColumns(columns),
    )

    is CanonicalNullPredicate -> copy(expression = expression.remapColumns(columns))

    is CanonicalLogicalPredicate -> copy(left = left.remapColumns(columns), right = right.remapColumns(columns))
}

private fun CanonicalSchemaPredicate.remapTables(
    tables: Map<CanonicalTableReference, CanonicalTableReference>,
): CanonicalSchemaPredicate = when (this) {
    is CanonicalValueComparison -> copy(expression = expression.remapTables(tables))

    is CanonicalExpressionComparison -> copy(left = left.remapTables(tables), right = right.remapTables(tables))

    is CanonicalNullSafeComparison -> copy(left = left.remapTables(tables), right = right.remapTables(tables))

    is CanonicalLikeValuePredicate -> copy(expression = expression.remapTables(tables))

    is CanonicalLikeExpressionPredicate -> copy(
        expression = expression.remapTables(tables),
        pattern = pattern.remapTables(tables),
    )

    is CanonicalNullPredicate -> copy(expression = expression.remapTables(tables))

    is CanonicalLogicalPredicate -> copy(left = left.remapTables(tables), right = right.remapTables(tables))
}

private fun migrationPlanSummary(operations: List<MigrationOperation>): MigrationPlanSummary {
    val kinds = operations.map(MigrationOperation::summaryKind)
    return MigrationPlanSummary(
        added = kinds.count(PlanChangeKind.Added::equals),
        removed = kinds.count(PlanChangeKind.Removed::equals),
        renamed = kinds.count(PlanChangeKind.Renamed::equals),
        changed = kinds.count(PlanChangeKind.Changed::equals),
        highestRisk = operations.maxOfOrNull { it.risk.level.ordinal }?.let { MigrationRisk.entries[it] },
    )
}

private enum class PlanChangeKind {
    Added,
    Removed,
    Renamed,
    Changed,
}

private fun MigrationOperation.summaryKind(): PlanChangeKind = when (this) {
    is CreatePostgresPolicy, is CreateSecurityPolicy, is CreateVpdPolicy, is AddSecurityPredicate,
    -> PlanChangeKind.Added

    is DropPostgresPolicy, is DropSecurityPolicy, is DropVpdPolicy, is DropSecurityPredicate -> PlanChangeKind.Removed

    is EnableRowLevelSecurity, is DisableRowLevelSecurity, is ForceRowLevelSecurity,
    is SetSecurityPolicyEnabled, is SetVpdPolicyEnabled,
    -> PlanChangeKind.Changed

    is CreateTable, is AddColumn, is CreateIndex, is CreateSequence,
    is AddPrimaryKey, is AddUnique, is AddForeignKey, is AddCheck,
    -> PlanChangeKind.Added

    is DropTable, is DropColumn, is DropIndex, is DropSequence,
    is DropPrimaryKey, is DropUnique, is DropForeignKey, is DropCheck,
    -> PlanChangeKind.Removed

    is RenameTable, is RenameColumn, is RenameIndex -> PlanChangeKind.Renamed

    is AlterColumnType, is SetNotNull, is DropNotNull, is SetDefault, is DropDefault,
    is AlterDefault, is AlterIdentity, is AlterGeneratedColumn, is AlterColumnComment,
    is AlterTableComment, is RecreateIndex, is AlterSequence,
    -> PlanChangeKind.Changed
}

private fun safeRisk(reason: String): MigrationRiskAssessment = MigrationRiskAssessment(MigrationRisk.Safe, reason)

private fun reviewRisk(reason: String): MigrationRiskAssessment = MigrationRiskAssessment(MigrationRisk.Review, reason)

private fun destructiveRisk(reason: String): MigrationRiskAssessment =
    MigrationRiskAssessment(MigrationRisk.Destructive, reason)

private fun addColumnRisk(table: CanonicalTableReference, column: CanonicalColumn): MigrationRiskAssessment =
    if (column.nullable && column.default == null) {
        safeRisk(
            "Adding nullable column ${table.displayName()}.${column.name} without a default does not remove data.",
        )
    } else {
        reviewRisk(
            "Adding ${table.displayName()}.${column.name} can require existing rows or future writes to satisfy it.",
        )
    }

private fun CanonicalTable.reference(): CanonicalTableReference = CanonicalTableReference(schemaName, name)

private fun CanonicalTable.displayName(): String = name.displayName(schemaName)

private fun CanonicalSequence.reference(): CanonicalSequenceReference = CanonicalSequenceReference(schemaName, name)

private fun CanonicalIndex.reference(): CanonicalIndexReference = CanonicalIndexReference(schemaName, name)

private fun CanonicalTableReference.displayName(): String = name.displayName(schemaName)

private fun CanonicalIndexReference.displayName(): String = name.displayName(schemaName)

private fun CanonicalSequence.displayName(): String = name.displayName(schemaName)

private fun CanonicalIndex.displayName(): String = name.displayName(schemaName)

private fun CanonicalColumnReference.displayName(): String = "${table.displayName()}.$name"

private fun String.displayName(schemaName: String?): String = schemaName?.let { "$it.$this" } ?: this

private val canonicalTableComparator: Comparator<CanonicalTable> = compareBy(
    { it.schemaName.orEmpty() },
    CanonicalTable::name,
)

private val canonicalSequenceComparator: Comparator<CanonicalSequence> = compareBy(
    { it.schemaName.orEmpty() },
    CanonicalSequence::name,
)

private val canonicalIndexComparator: Comparator<CanonicalIndex> = compareBy(
    { it.schemaName.orEmpty() },
    CanonicalIndex::name,
)
