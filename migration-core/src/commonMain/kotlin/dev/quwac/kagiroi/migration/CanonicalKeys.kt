package dev.quwac.kagiroi.migration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun CanonicalTable.effectivePrimaryKey(): CanonicalPrimaryKey? = primaryKey ?: columns
    .filter(CanonicalColumn::primaryKey)
    .map(CanonicalColumn::name)
    .takeIf(List<String>::isNotEmpty)
    ?.let { columns -> CanonicalPrimaryKey(columns = columns) }

/** An inline primary key already supplies uniqueness; its UNIQUE flag emits no additional constraint. */
internal fun CanonicalTable.effectiveUniqueConstraints(): List<CanonicalUniqueConstraint> =
    uniqueConstraints + columns.filter { it.unique && !it.primaryKey }.map { column ->
        CanonicalUniqueConstraint(columns = listOf(column.name))
    }

/** Compares key declarations by their SQL constraints without rewriting persisted schemas or hashes. */
internal fun CanonicalTable.keysForComparison(): CanonicalTable {
    val inlinePrimaryCount = columns.count(CanonicalColumn::primaryKey)
    // Conflicting declarations must not become equivalent to a valid single primary key.
    if (inlinePrimaryCount > 1 || (primaryKey != null && inlinePrimaryCount != 0)) return this
    return copy(
        columns = columns.map { it.copy(primaryKey = false, unique = false) },
        primaryKey = effectivePrimaryKey(),
        uniqueConstraints = effectiveUniqueConstraints().sortedBy { Json.encodeToString(it) },
    )
}
