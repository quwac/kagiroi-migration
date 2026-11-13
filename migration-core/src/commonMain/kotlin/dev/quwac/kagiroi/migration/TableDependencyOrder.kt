package dev.quwac.kagiroi.migration

internal data class TableDependencyOrder(
    val tables: List<CanonicalTable>,
    val deferredForeignKeys: Map<CanonicalTableReference, List<CanonicalForeignKey>>,
)

/**
 * Topologically orders managed tables by their foreign keys, with stable schema/name tie-breaking.
 * A DFS back edge identifies a cyclic constraint: callers create its table without that constraint
 * and add it after the table-changing phase. Self references remain inline because CREATE TABLE
 * can resolve its own columns. Unmanaged targets do not contribute graph vertices.
 */
internal fun orderTableDependencies(tables: List<CanonicalTable>): TableDependencyOrder {
    val comparator = compareBy<CanonicalTable>({ it.schemaName.orEmpty() }, { it.name })
    val byReference = tables.associateBy { CanonicalTableReference(it.schemaName, it.name) }
    val visiting = mutableSetOf<CanonicalTableReference>()
    val visited = mutableSetOf<CanonicalTableReference>()
    val ordered = mutableListOf<CanonicalTable>()
    val deferred = mutableMapOf<CanonicalTableReference, MutableList<CanonicalForeignKey>>()

    fun visit(table: CanonicalTable) {
        val reference = CanonicalTableReference(table.schemaName, table.name)
        if (reference in visited) return
        visiting += reference
        val dependencies = table.foreignKeys.mapNotNull { key -> byReference[key.targetTable] }
            .distinct().sortedWith(comparator)
        dependencies.forEach { target ->
            val targetReference = CanonicalTableReference(target.schemaName, target.name)
            when {
                targetReference == reference -> Unit

                targetReference in visiting -> deferred.getOrPut(reference, ::mutableListOf)
                    .addAll(table.foreignKeys.filter { it.targetTable == targetReference })

                else -> visit(target)
            }
        }
        visiting -= reference
        visited += reference
        ordered += table
    }

    tables.sortedWith(comparator).forEach(::visit)
    return TableDependencyOrder(ordered, deferred)
}

/** Keeps each table's mutation order while placing referenced tables before their dependants. */
internal fun orderTableChanges(
    operations: List<MigrationOperation>,
    tables: List<CanonicalTable>,
): List<MigrationOperation> {
    val ranks = tables.mapIndexed { index, table ->
        CanonicalTableReference(
            table.schemaName,
            table.name,
        ) to index
    }.toMap()
    return operations.sortedBy { ranks.getValue(it.changedTable()) }
}

@Suppress("CyclomaticComplexMethod") // Flat ownership lookup across immutable operation variants.
private fun MigrationOperation.changedTable(): CanonicalTableReference = when (this) {
    is CreateTable -> CanonicalTableReference(table.schemaName, table.name)
    is RenameColumn -> table
    is AddColumn -> table
    is AlterColumnType -> column.table
    is SetNotNull -> column.table
    is DropNotNull -> column.table
    is SetDefault -> column.table
    is DropDefault -> column.table
    is AlterDefault -> column.table
    is AlterIdentity -> column.table
    is AlterGeneratedColumn -> column.table
    is AlterColumnComment -> column.table
    is AlterTableComment -> table
    is AlterUniqueComment -> table
    is RecreateIndex -> replacement.table
    is RenameIndex -> to.table
    is DropColumn -> column.table
    is AddPrimaryKey -> table
    is AddUnique -> table
    is AddCheck -> table
    else -> error("Operation is not a table mutation: $this")
}
