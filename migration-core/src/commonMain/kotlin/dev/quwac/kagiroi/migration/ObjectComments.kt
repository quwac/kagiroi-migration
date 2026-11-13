package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Changes a named UNIQUE constraint's comment without rebuilding the constraint. */
@Serializable
@SerialName("alterUniqueComment")
data class AlterUniqueComment(
    val table: CanonicalTableReference,
    val name: String,
    val from: String? = null,
    val to: String? = null,
    override val risk: MigrationRiskAssessment = MigrationRiskAssessment(
        MigrationRisk.Safe,
        "Changing the comment on UNIQUE constraint $name does not alter data.",
    ),
) : MigrationOperation

/** Changes an index's comment without rebuilding the index. */
@Serializable
@SerialName("alterIndexComment")
data class AlterIndexComment(
    val index: CanonicalIndexReference,
    val from: String? = null,
    val to: String? = null,
    override val risk: MigrationRiskAssessment = MigrationRiskAssessment(
        MigrationRisk.Safe,
        "Changing the comment on index ${index.name} does not alter data.",
    ),
    /** Owning table used by table-scoped index comment stores such as SQL Server. */
    val table: CanonicalTableReference? = null,
) : MigrationOperation

/** PostgreSQL places an unqualified index in its owning table's schema. */
internal fun CanonicalIndex.commentReference(): CanonicalIndexReference =
    CanonicalIndexReference(schemaName ?: table.schemaName, name)

/** Matches UNIQUE definitions independently of comments so documentation edits preserve constraints. */
internal fun diffUniqueConstraints(
    table: CanonicalTableReference,
    previous: List<CanonicalUniqueConstraint>,
    desired: List<CanonicalUniqueConstraint>,
    normalize: (CanonicalUniqueConstraint) -> CanonicalUniqueConstraint,
    drops: MutableList<MigrationOperation>,
    adds: MutableList<MigrationOperation>,
) {
    val unmatched = desired.toMutableList()
    previous.forEach { old ->
        val normalized = normalize(old)
        val match = unmatched.indexOfFirst { normalized.copy(comment = it.comment) == it }
        if (match < 0) {
            drops += DropUnique(table, old)
        } else {
            val current = unmatched.removeAt(match)
            if (normalized.comment != current.comment) {
                adds += AlterUniqueComment(
                    table,
                    requireNotNull(current.name) { "UNIQUE constraint comment requires a name" },
                    normalized.comment,
                    current.comment,
                )
            }
        }
    }
    unmatched.forEach { adds += AddUnique(table, it) }
}
