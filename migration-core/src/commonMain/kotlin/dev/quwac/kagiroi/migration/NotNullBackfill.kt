package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** One-time evolution hint; deliberately excluded from the physical schema hash. */
@Serializable
data class CanonicalNotNullBackfill(
    val column: CanonicalColumnReference,
    val statements: List<CanonicalBackfillStatement>,
)

@Serializable
data class CanonicalBackfillAssignment(val column: String, val expression: CanonicalSchemaExpression)

/** Data operations have explicit targets and typed expressions, rather than parsed SQL strings. */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface CanonicalBackfillStatement {
    val table: CanonicalTableReference
}

@Serializable
@SerialName("insert")
data class CanonicalBackfillInsert(
    override val table: CanonicalTableReference,
    val rows: List<List<CanonicalBackfillAssignment>>,
) : CanonicalBackfillStatement {
    init {
        requireValidRows(rows)
        require(rows.flatten().all { it.expression.backfillColumns().isEmpty() }) {
            "Backfill INSERT VALUES cannot reference relation columns"
        }
    }
}

@Serializable
@SerialName("update")
data class CanonicalBackfillUpdate(
    override val table: CanonicalTableReference,
    val assignments: List<CanonicalBackfillAssignment>,
    val predicate: CanonicalSchemaPredicate? = null,
) : CanonicalBackfillStatement {
    init {
        requireValidRows(listOf(assignments))
        val references =
            assignments.flatMap { it.expression.backfillColumns() } + predicate?.backfillColumns().orEmpty()
        require(references.all { it.table == table }) { "Backfill UPDATE can reference only its target table" }
    }
}

/** Records the final column and the user's data operations as a single replayable addition. */
@Serializable
@SerialName("addColumnWithBackfill")
data class AddColumnWithBackfill(
    val table: CanonicalTableReference,
    val column: CanonicalColumn,
    val statements: List<CanonicalBackfillStatement>,
) : MigrationOperation {
    init {
        require(!column.nullable && column.identity == null && column.generated == null) {
            "notNullBackfill requires a non-null, non-identity, writable column"
        }
        require(statements.isNotEmpty()) { "notNullBackfill requires statements" }
    }
}

/** Validates explicit targets without inspecting data or trying to infer business requirements. */
internal fun CanonicalDesiredSchema.notNullBackfills(): Map<CanonicalColumnReference, CanonicalNotNullBackfill> {
    val entries = evolution.notNullBackfills
    require(entries.map { it.column }.distinct().size == entries.size) { "Duplicate notNullBackfill column" }
    val tables = schema.tables.associateBy { CanonicalTableReference(it.schemaName, it.name) }
    entries.forEach { hint ->
        val column = tables[hint.column.table]?.columns?.singleOrNull { it.name == hint.column.name }
        require(column != null && !column.nullable && column.identity == null && column.generated == null) {
            "notNullBackfill target must be a managed non-null writable column: ${hint.column}"
        }
        require(hint.statements.isNotEmpty()) { "notNullBackfill requires statements" }
        hint.statements.forEach { statement ->
            val target = requireNotNull(tables[statement.table]) { "Unmanaged backfill table: ${statement.table}" }
            val rows = when (statement) {
                is CanonicalBackfillInsert -> statement.rows
                is CanonicalBackfillUpdate -> listOf(statement.assignments)
            }
            val names = rows.flatten().map { it.column }
            require(names.all { name -> target.columns.any { it.name == name && it.generated == null } }) {
                "Backfill assignments must target physical writable columns"
            }
            val references = rows.flatten().flatMap { it.expression.backfillColumns() } +
                (statement as? CanonicalBackfillUpdate)?.predicate?.backfillColumns().orEmpty()
            require(references.all { ref -> target.columns.any { it.name == ref.name } }) {
                "Backfill expressions must reference physical columns"
            }
        }
    }
    return entries.associateBy { it.column }
}

/** Validate ordered VALUES rows and UPDATE assignment lists, including metadata read from artifacts. */
private fun requireValidRows(rows: List<List<CanonicalBackfillAssignment>>) {
    require(rows.isNotEmpty()) { "Backfill INSERT requires rows" }
    rows.forEach { assignments ->
        val names = assignments.map { it.column }
        require(names.isNotEmpty() && names.distinct().size == names.size) { "Invalid backfill assignments" }
    }
    require(rows.all { row -> row.map { it.column }.toSet() == rows.first().map { it.column }.toSet() }) {
        "Backfill INSERT rows must assign the same columns"
    }
}

/** Inline key flags belong to the final column snapshot; their DDL follows that column's backfill. */
internal fun withoutBackfillKeyDuplicates(
    operations: List<MigrationOperation>,
    additions: List<MigrationOperation>,
    tables: List<CanonicalTable>,
): List<MigrationOperation> {
    val remaining = operations.toMutableList()
    additions.filterIsInstance<AddColumnWithBackfill>().forEach { addition ->
        val owner = tables.single { CanonicalTableReference(it.schemaName, it.name) == addition.table }
        if (addition.column.primaryKey && owner.primaryKey == null) {
            val index = remaining.indexOfLast {
                it is AddPrimaryKey && it.table == addition.table && it.primaryKey.columns == listOf(
                    addition.column.name,
                )
            }
            if (index >= 0) remaining.removeAt(index)
        }
        if (addition.column.unique) {
            val index = remaining.indexOfLast {
                it is AddUnique && it.table == addition.table && it.unique.name == null &&
                    it.unique.columns == listOf(addition.column.name)
            }
            if (index >= 0) remaining.removeAt(index)
        }
    }
    return remaining
}

/** Evolution versioning must not change the physical-schema hash or replayed physical format. */
internal fun canonicalSchemaVersion(desired: CanonicalDesiredSchema): Int =
    if (desired.evolution.typeConversions.isNotEmpty()) {
        TYPE_CONVERSION_CANONICAL_SCHEMA_FORMAT_VERSION
    } else if (desired.evolution.notNullBackfills.isNotEmpty()) {
        BACKFILL_CANONICAL_SCHEMA_FORMAT_VERSION
    } else {
        canonicalSchemaVersion(desired.schema)
    }
