@file:Suppress("BlankLineBetweenWhenConditions", "CyclomaticComplexMethod", "TooManyFunctions")

package dev.quwac.kagiroi.migration

import kotlinx.serialization.Serializable

/** The physical-schema change declared by one complete migration file. */
@Serializable
data class SchemaDelta(val operations: List<MigrationOperation> = emptyList())

fun emptyCanonicalSchema(): CanonicalDesiredSchema = CanonicalDesiredSchema(
    formatVersion = CANONICAL_SCHEMA_FORMAT_VERSION,
    schema = CanonicalPhysicalSchema(),
    metadata = CanonicalSchemaMetadata(
        CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(CanonicalPhysicalSchema())),
    ),
)

/** Replays persisted artifact deltas without reading a database or runtime Table objects. */
fun replaySchemaDeltas(deltas: Iterable<SchemaDelta>): CanonicalDesiredSchema {
    var schema = CanonicalPhysicalSchema()
    deltas.forEach { delta -> schema = schema.apply(delta) }
    val normalized = schema.normalized()
    validateSecurityObjects(normalized)
    return CanonicalDesiredSchema(
        formatVersion = canonicalSchemaVersion(normalized),
        schema = normalized,
        metadata = CanonicalSchemaMetadata(
            CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(normalized)),
        ),
    )
}

private fun CanonicalPhysicalSchema.apply(delta: SchemaDelta): CanonicalPhysicalSchema = delta.operations.fold(this) {
        schema,
        op,
    ->
    when (op) {
        is SecurityMigrationOperation -> schema.applySecurityOperation(op)
        is CreateTable -> schema.copy(tables = schema.tables + op.table)
        is DropTable -> schema.copy(tables = schema.tables.filterNot { it.reference() == op.table })
        is RenameTable -> schema.renameTable(op.from, op.to)
        is AddColumn -> schema.changeTable(op.table) { it.copy(columns = it.columns + op.column) }
        is DropColumn -> schema.changeTable(op.column.table) { table ->
            table.copy(columns = table.columns.filterNot { it.name == op.column.name })
        }
        is RenameColumn -> schema.changeTable(op.table) { table ->
            table.copy(columns = table.columns.map { if (it.name == op.from) it.copy(name = op.to) else it })
        }.renameColumnReferences(op.table, op.from, op.to)
        is AlterColumnType -> schema.changeColumn(op.column) { it.copy(type = op.to) }
        is SetNotNull -> schema.changeColumn(op.column) { it.copy(nullable = false) }
        is DropNotNull -> schema.changeColumn(op.column) { it.copy(nullable = true) }
        is SetDefault -> schema.changeColumn(op.column) { it.copy(default = op.default) }
        is DropDefault -> schema.changeColumn(op.column) { it.copy(default = null) }
        is AlterDefault -> schema.changeColumn(op.column) { it.copy(default = op.to) }
        is AlterIdentity -> schema.changeColumn(op.column) { it.copy(identity = op.to) }
        is AlterGeneratedColumn -> schema.changeColumn(op.column) { it.copy(generated = op.to) }
        is AlterColumnComment -> schema.changeColumn(op.column) { it.copy(comment = op.to) }
        is AlterTableComment -> schema.changeTable(op.table) { it.copy(comment = op.to) }
        is AddPrimaryKey -> schema.changeTable(op.table) { it.copy(primaryKey = op.primaryKey) }
        is DropPrimaryKey -> schema.changeTable(op.table) { it.copy(primaryKey = null) }
        is AddUnique -> schema.changeTable(op.table) { it.copy(uniqueConstraints = it.uniqueConstraints + op.unique) }
        is DropUnique -> schema.changeTable(op.table) { table ->
            table.copy(
                uniqueConstraints = table.uniqueConstraints - op.unique,
                columns = table.columns.map { column ->
                    if (op.unique.name == null && op.unique.columns == listOf(column.name)) {
                        column.copy(unique = false)
                    } else {
                        column
                    }
                },
            )
        }
        is AddForeignKey -> schema.changeTable(op.table) { it.copy(foreignKeys = it.foreignKeys + op.foreignKey) }
        is DropForeignKey -> schema.changeTable(op.table) { it.copy(foreignKeys = it.foreignKeys - op.foreignKey) }
        is AddCheck -> schema.changeTable(op.table) { it.copy(checks = it.checks + op.check) }
        is DropCheck -> schema.changeTable(op.table) { it.copy(checks = it.checks - op.check) }
        is CreateIndex -> schema.copy(indexes = schema.indexes + op.index)
        is DropIndex -> schema.copy(indexes = schema.indexes - op.index)
        is RenameIndex -> schema.copy(indexes = schema.indexes.map { if (it == op.from) op.to else it })
        is RecreateIndex -> schema.copy(indexes = (schema.indexes - op.previous) + op.replacement)
        is CreateSequence -> schema.copy(sequences = schema.sequences + op.sequence)
        is DropSequence -> schema.copy(sequences = schema.sequences - op.sequence)
        is AlterSequence -> schema.copy(sequences = schema.sequences.map { if (it == op.from) op.to else it })
    }
}

private fun CanonicalPhysicalSchema.changeTable(
    reference: CanonicalTableReference,
    transform: (CanonicalTable) -> CanonicalTable,
): CanonicalPhysicalSchema {
    require(tables.any { it.reference() == reference }) { "Schema delta refers to missing table ${reference.name}" }
    return copy(tables = tables.map { if (it.reference() == reference) transform(it) else it })
}

private fun CanonicalPhysicalSchema.changeColumn(
    reference: CanonicalColumnReference,
    transform: (CanonicalColumn) -> CanonicalColumn,
): CanonicalPhysicalSchema = changeTable(reference.table) { table ->
    require(
        table.columns.any {
            it.name == reference.name
        },
    ) { "Schema delta refers to missing column ${reference.name}" }
    table.copy(columns = table.columns.map { if (it.name == reference.name) transform(it) else it })
}

private fun CanonicalPhysicalSchema.renameTable(
    from: CanonicalTableReference,
    to: CanonicalTableReference,
): CanonicalPhysicalSchema {
    require(tables.any { it.reference() == from }) { "Schema delta refers to missing table ${from.name}" }
    val renamed = copy(
        tables = tables.map { table ->
            if (table.reference() == from) table.copy(schemaName = to.schemaName, name = to.name) else table
        },
    )
    return renamed.mapReferences(
        tableReference = { reference -> if (reference == from) to else reference },
        columnReference = { reference -> if (reference.table == from) reference.copy(table = to) else reference },
    )
}

private fun CanonicalPhysicalSchema.renameColumnReferences(
    table: CanonicalTableReference,
    from: String,
    to: String,
): CanonicalPhysicalSchema {
    val oldReference = CanonicalColumnReference(table, from)
    val newReference = CanonicalColumnReference(table, to)
    val mapped = mapReferences(
        tableReference = { it },
        columnReference = { reference -> if (reference == oldReference) newReference else reference },
    )
    return mapped.copy(
        tables = mapped.tables.map { current ->
            current.copy(
                primaryKey = current.primaryKey?.mapColumns(from, to).takeIf { current.reference() == table }
                    ?: current.primaryKey,
                uniqueConstraints = if (current.reference() == table) {
                    current.uniqueConstraints.map { it.mapColumns(from, to) }
                } else {
                    current.uniqueConstraints
                },
                foreignKeys = current.foreignKeys.map { foreignKey ->
                    foreignKey.copy(
                        sourceColumns = if (current.reference() == table) {
                            foreignKey.sourceColumns.map { column -> if (column == from) to else column }
                        } else {
                            foreignKey.sourceColumns
                        },
                        targetColumns = if (foreignKey.targetTable == table) {
                            foreignKey.targetColumns.map { column -> if (column == from) to else column }
                        } else {
                            foreignKey.targetColumns
                        },
                    )
                },
            )
        },
    )
}

private fun CanonicalPrimaryKey.mapColumns(from: String, to: String): CanonicalPrimaryKey = copy(
    columns = columns.map { column -> if (column == from) to else column },
)

private fun CanonicalUniqueConstraint.mapColumns(from: String, to: String): CanonicalUniqueConstraint = copy(
    columns = columns.map { column -> if (column == from) to else column },
)

private fun CanonicalPhysicalSchema.mapReferences(
    tableReference: (CanonicalTableReference) -> CanonicalTableReference,
    columnReference: (CanonicalColumnReference) -> CanonicalColumnReference,
): CanonicalPhysicalSchema = copy(
    tables = tables.map { table ->
        table.copy(
            columns = table.columns.map { column ->
                column.copy(
                    default = column.default?.mapColumnReferences(columnReference),
                    generated = column.generated?.let { generated ->
                        generated.copy(expression = generated.expression.mapColumnReferences(columnReference))
                    },
                )
            },
            foreignKeys = table.foreignKeys.map { foreignKey ->
                foreignKey.copy(targetTable = tableReference(foreignKey.targetTable))
            },
            checks = table.checks.map { check ->
                check.copy(expression = check.expression.mapColumnReferences(columnReference))
            },
        )
    },
    indexes = indexes.map { index ->
        index.copy(
            table = tableReference(index.table),
            keys = index.keys.map { key -> key.mapColumnReferences(columnReference) },
            predicate = index.predicate?.mapColumnReferences(columnReference),
            includedColumns = index.includedColumns.map(columnReference),
        )
    },
)

private fun CanonicalIndexKey.mapColumnReferences(
    transform: (CanonicalColumnReference) -> CanonicalColumnReference,
): CanonicalIndexKey = when (this) {
    is CanonicalColumnIndexKey -> copy(column = transform(column))
    is CanonicalExpressionIndexKey -> copy(expression = expression.mapColumnReferences(transform))
}

private fun CanonicalSchemaExpression.mapColumnReferences(
    transform: (CanonicalColumnReference) -> CanonicalColumnReference,
): CanonicalSchemaExpression = when (this) {
    is CanonicalColumnExpression -> copy(column = transform(column))
    is CanonicalLiteralExpression, is CanonicalSequenceNextValueExpression -> this
    is CanonicalFunctionExpression -> copy(arguments = arguments.map { it.mapColumnReferences(transform) })
    is CanonicalBinaryExpression -> copy(
        left = left.mapColumnReferences(transform),
        right = right.mapColumnReferences(transform),
    )
    is CanonicalCastExpression -> copy(expression = expression.mapColumnReferences(transform))
    is CanonicalCaseExpression -> copy(
        branches = branches.map { branch ->
            branch.copy(
                condition = branch.condition.mapColumnReferences(transform),
                result = branch.result.mapColumnReferences(transform),
            )
        },
        elseResult = elseResult.mapColumnReferences(transform),
    )
}

private fun CanonicalSchemaPredicate.mapColumnReferences(
    transform: (CanonicalColumnReference) -> CanonicalColumnReference,
): CanonicalSchemaPredicate = when (this) {
    is CanonicalValueComparison -> copy(expression = expression.mapColumnReferences(transform))
    is CanonicalExpressionComparison -> copy(
        left = left.mapColumnReferences(transform),
        right = right.mapColumnReferences(transform),
    )
    is CanonicalNullSafeComparison -> copy(
        left = left.mapColumnReferences(transform),
        right = right.mapColumnReferences(transform),
    )
    is CanonicalLikeValuePredicate -> copy(expression = expression.mapColumnReferences(transform))
    is CanonicalLikeExpressionPredicate -> copy(
        expression = expression.mapColumnReferences(transform),
        pattern = pattern.mapColumnReferences(transform),
    )
    is CanonicalNullPredicate -> copy(expression = expression.mapColumnReferences(transform))
    is CanonicalLogicalPredicate -> copy(
        left = left.mapColumnReferences(transform),
        right = right.mapColumnReferences(transform),
    )
}

private fun CanonicalTable.reference() = CanonicalTableReference(schemaName, name)

private fun CanonicalPhysicalSchema.normalized(): CanonicalPhysicalSchema = normalizedSecurity().copy(
    tables = tables.sortedWith(compareBy(CanonicalTable::schemaName, CanonicalTable::name)),
    sequences = sequences.sortedWith(compareBy(CanonicalSequence::schemaName, CanonicalSequence::name)),
    indexes = indexes.sortedWith(compareBy(CanonicalIndex::schemaName, CanonicalIndex::name)),
)
