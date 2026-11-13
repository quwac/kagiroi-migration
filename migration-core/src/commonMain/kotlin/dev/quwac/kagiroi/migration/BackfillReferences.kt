package dev.quwac.kagiroi.migration

/** Preserve expression scope when unqualified column names are emitted inside data statements. */
internal fun CanonicalSchemaExpression.backfillColumns(): List<CanonicalColumnReference> = when (this) {
    is CanonicalColumnExpression -> listOf(column)

    is CanonicalLiteralExpression, is CanonicalSequenceNextValueExpression -> emptyList()

    is CanonicalFunctionExpression -> arguments.flatMap { it.backfillColumns() }

    is CanonicalBinaryExpression -> left.backfillColumns() + right.backfillColumns()

    is CanonicalCastExpression -> expression.backfillColumns()

    is CanonicalCaseExpression -> branches.flatMap { it.condition.backfillColumns() + it.result.backfillColumns() } +
        elseResult.backfillColumns()
}

internal fun CanonicalSchemaPredicate.backfillColumns(): List<CanonicalColumnReference> = when (this) {
    is CanonicalValueComparison -> expression.backfillColumns()
    is CanonicalExpressionComparison -> left.backfillColumns() + right.backfillColumns()
    is CanonicalNullSafeComparison -> left.backfillColumns() + right.backfillColumns()
    is CanonicalLikeValuePredicate -> expression.backfillColumns()
    is CanonicalLikeExpressionPredicate -> expression.backfillColumns() + pattern.backfillColumns()
    is CanonicalNullPredicate -> expression.backfillColumns()
    is CanonicalLogicalPredicate -> left.backfillColumns() + right.backfillColumns()
}
