package dev.quwac.kagiroi.migration

import kotlinx.serialization.Serializable

/** A one-time PostgreSQL USING expression and the exact non-null source type it accepts. */
@Serializable
data class CanonicalColumnTypeConversion(
    val column: CanonicalColumnReference,
    val from: CanonicalColumnType,
    val using: CanonicalSchemaExpression,
)

/** Validate managed scope without reading a live database or inferring a data conversion. */
internal fun CanonicalDesiredSchema.typeConversions(): Map<CanonicalColumnReference, CanonicalColumnTypeConversion> {
    val hints = evolution.typeConversions
    require(hints.map { it.column }.distinct().size == hints.size) { "Duplicate typeUsing column" }
    val tables = schema.tables.associateBy { CanonicalTableReference(it.schemaName, it.name) }
    hints.forEach { hint ->
        val table = requireNotNull(tables[hint.column.table]) { "Unmanaged typeUsing table: ${hint.column.table}" }
        val column = table.columns.singleOrNull { it.name == hint.column.name }
        require(column != null && !column.nullable && column.identity == null && column.generated == null) {
            "typeUsing requires a managed non-null writable column: ${hint.column}"
        }
        require(
            hint.using.backfillColumns().all { reference ->
                reference.table == hint.column.table && table.columns.any {
                    it.name == reference.name && it.generated == null
                }
            },
        ) { "typeUsing can reference only physical writable columns in its target table" }
    }
    return hints.associateBy { it.column }
}
