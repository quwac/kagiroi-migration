package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Version of the persisted Canonical Schema JSON contract. */
const val CANONICAL_SCHEMA_FORMAT_VERSION: Int = 1

/**
 * A machine-readable desired schema projected from explicitly registered Kagiroi definitions.
 *
 * [schema] contains only the current managed database shape. [evolution] contains lineage hints
 * for a future diff engine and deliberately does not contribute to [metadata]'s physical schema
 * hash.
 */
@Serializable
data class CanonicalDesiredSchema(
    val formatVersion: Int,
    val schema: CanonicalPhysicalSchema,
    val evolution: CanonicalEvolution = CanonicalEvolution(),
    val metadata: CanonicalSchemaMetadata,
)

/** Deterministic metadata for validating and storing one exported Canonical Schema document. */
@Serializable
data class CanonicalSchemaMetadata(val physicalSchemaHash: CanonicalSchemaHash)

/** A hash whose byte source is defined by the Canonical Schema format. */
@Serializable
data class CanonicalSchemaHash(val algorithm: CanonicalHashAlgorithm, val value: String)

/** Hash algorithms supported by the Canonical Schema format. */
@Serializable
enum class CanonicalHashAlgorithm {
    @SerialName("sha256")
    Sha256,
}

/** The dialect-neutral, managed database objects that describe current physical state. */
@Serializable
data class CanonicalPhysicalSchema(
    val tables: List<CanonicalTable> = emptyList(),
    val sequences: List<CanonicalSequence> = emptyList(),
    val indexes: List<CanonicalIndex> = emptyList(),
)

/** One managed database table. [columns] retain declaration order. */
@Serializable
data class CanonicalTable(
    val schemaName: String? = null,
    val name: String,
    val comment: String? = null,
    val columns: List<CanonicalColumn>,
    val primaryKey: CanonicalPrimaryKey? = null,
    val uniqueConstraints: List<CanonicalUniqueConstraint> = emptyList(),
    val foreignKeys: List<CanonicalForeignKey> = emptyList(),
    val checks: List<CanonicalCheckConstraint> = emptyList(),
)

/** One managed physical column. Runtime value mappings are intentionally absent. */
@Serializable
data class CanonicalColumn(
    val name: String,
    val type: CanonicalColumnType,
    val nullable: Boolean,
    val comment: String? = null,
    val primaryKey: Boolean = false,
    val unique: Boolean = false,
    val default: CanonicalSchemaExpression? = null,
    val identity: CanonicalIdentity? = null,
    val generated: CanonicalGeneratedColumn? = null,
)

/** A stable type identifier and its ordered extension arguments. */
@Serializable
data class CanonicalColumnType(val id: String, val arguments: List<CanonicalTypeArgument> = emptyList())

/** A named stable type argument. Values are strings to avoid JavaScript numeric coercion. */
@Serializable
data class CanonicalTypeArgument(val name: String, val value: String)

/** Reserved identity shape; the current portable identity API has no start or increment options. */
@Serializable
data class CanonicalIdentity(val start: String? = null, val increment: String? = null)

/** A physical generated column expression and its requested storage mode. */
@Serializable
data class CanonicalGeneratedColumn(
    val expression: CanonicalSchemaExpression,
    val storage: CanonicalGeneratedColumnStorage,
)

/** Physical generated-column storage requested by the table definition. */
@Serializable
enum class CanonicalGeneratedColumnStorage {
    @SerialName("virtual")
    Virtual,

    @SerialName("stored")
    Stored,
}

/** A table-level primary-key declaration. Column order is significant. */
@Serializable
data class CanonicalPrimaryKey(val name: String? = null, val columns: List<String>)

/** A table-level UNIQUE declaration. Column order is significant. */
@Serializable
data class CanonicalUniqueConstraint(val name: String? = null, val columns: List<String>, val comment: String? = null)

/** A table-level foreign-key declaration. Source and target column positions are paired. */
@Serializable
data class CanonicalForeignKey(
    val name: String? = null,
    val sourceColumns: List<String>,
    val targetTable: CanonicalTableReference,
    val targetColumns: List<String>,
    val onDelete: CanonicalForeignKeyAction? = null,
    val onUpdate: CanonicalForeignKeyAction? = null,
)

/** A stable table reference; no runtime object identity is serialized. */
@Serializable
data class CanonicalTableReference(val schemaName: String? = null, val name: String)

/** A stable physical-column reference; no runtime object identity is serialized. */
@Serializable
data class CanonicalColumnReference(val table: CanonicalTableReference, val name: String)

/** The portable referential actions currently modeled by Kagiroi. */
@Serializable
enum class CanonicalForeignKeyAction {
    @SerialName("cascade")
    Cascade,

    @SerialName("restrict")
    Restrict,

    @SerialName("noAction")
    NoAction,

    @SerialName("setNull")
    SetNull,

    @SerialName("setDefault")
    SetDefault,
}

/** A structured typed CHECK expression, never rendered SQL text. */
@Serializable
data class CanonicalCheckConstraint(val name: String? = null, val expression: CanonicalSchemaPredicate)

/** A managed independent sequence. Current Kagiroi sequences have no configurable options. */
@Serializable
data class CanonicalSequence(val schemaName: String? = null, val name: String)

/** A managed reusable index definition, independent from a CREATE INDEX operation. */
@Serializable
data class CanonicalIndex(
    val schemaName: String? = null,
    val name: String,
    val table: CanonicalTableReference,
    val unique: Boolean,
    val keys: List<CanonicalIndexKey>,
    val predicate: CanonicalSchemaPredicate? = null,
    val includedColumns: List<CanonicalColumnReference> = emptyList(),
    val comment: String? = null,
)

/** One ordered index key. */
@Serializable
sealed interface CanonicalIndexKey {
    val direction: CanonicalIndexDirection?
}

/** A direct physical-column index key. */
@Serializable
@SerialName("column")
data class CanonicalColumnIndexKey(
    val column: CanonicalColumnReference,
    override val direction: CanonicalIndexDirection? = null,
) : CanonicalIndexKey

/** A structured expression index key. */
@Serializable
@SerialName("expression")
data class CanonicalExpressionIndexKey(
    val expression: CanonicalSchemaExpression,
    override val direction: CanonicalIndexDirection? = null,
) : CanonicalIndexKey

/** The explicit ordering of one index key; absence retains the database default. */
@Serializable
enum class CanonicalIndexDirection {
    @SerialName("asc")
    Asc,

    @SerialName("desc")
    Desc,
}

/** A typed schema expression stored as a structured AST rather than raw SQL. */
@Serializable
sealed interface CanonicalSchemaExpression

/** A stable physical-column reference expression. */
@Serializable
@SerialName("column")
data class CanonicalColumnExpression(val column: CanonicalColumnReference) : CanonicalSchemaExpression

/** A typed scalar literal after its Kotlin mapping has been converted to storage form. */
@Serializable
@SerialName("literal")
data class CanonicalLiteralExpression(val value: CanonicalLiteralValue, val type: CanonicalColumnType) :
    CanonicalSchemaExpression

/** A request for the next value of one independent sequence. */
@Serializable
@SerialName("sequenceNextValue")
data class CanonicalSequenceNextValueExpression(val sequence: CanonicalSequenceReference) : CanonicalSchemaExpression

/** A stable sequence reference. */
@Serializable
data class CanonicalSequenceReference(val schemaName: String? = null, val name: String)

/** A typed built-in or explicitly named unsafe function call. */
@Serializable
@SerialName("function")
data class CanonicalFunctionExpression(val function: String, val arguments: List<CanonicalSchemaExpression>) :
    CanonicalSchemaExpression

/** A structured SQL arithmetic expression. */
@Serializable
@SerialName("binary")
data class CanonicalBinaryExpression(
    val operator: CanonicalBinaryOperator,
    val left: CanonicalSchemaExpression,
    val right: CanonicalSchemaExpression,
) : CanonicalSchemaExpression

/** A dialect-neutral SQL arithmetic operator. */
@Serializable
enum class CanonicalBinaryOperator {
    @SerialName("add")
    Add,

    @SerialName("subtract")
    Subtract,

    @SerialName("multiply")
    Multiply,

    @SerialName("divide")
    Divide,

    @SerialName("modulo")
    Modulo,
}

/** A structured CAST expression with a stable target storage type. */
@Serializable
@SerialName("cast")
data class CanonicalCastExpression(val expression: CanonicalSchemaExpression, val targetType: CanonicalColumnType) :
    CanonicalSchemaExpression

/** A searched CASE expression with ordered WHEN branches. */
@Serializable
@SerialName("case")
data class CanonicalCaseExpression(
    val branches: List<CanonicalCaseBranch>,
    val elseResult: CanonicalSchemaExpression,
) : CanonicalSchemaExpression

/** One ordered searched CASE branch. */
@Serializable
data class CanonicalCaseBranch(val condition: CanonicalSchemaPredicate, val result: CanonicalSchemaExpression)

/** A structured typed predicate used by checks and partial indexes. */
@Serializable
sealed interface CanonicalSchemaPredicate

/** A comparison between a structured expression and a storage-form literal. */
@Serializable
@SerialName("valueComparison")
data class CanonicalValueComparison(
    val expression: CanonicalSchemaExpression,
    val operator: CanonicalComparisonOperator,
    val value: CanonicalLiteralValue,
    val valueType: CanonicalColumnType,
) : CanonicalSchemaPredicate

/** A comparison between two structured expressions. */
@Serializable
@SerialName("expressionComparison")
data class CanonicalExpressionComparison(
    val left: CanonicalSchemaExpression,
    val operator: CanonicalComparisonOperator,
    val right: CanonicalSchemaExpression,
) : CanonicalSchemaPredicate

/** A structured `IS [NOT] DISTINCT FROM` comparison. */
@Serializable
@SerialName("nullSafeComparison")
data class CanonicalNullSafeComparison(
    val left: CanonicalSchemaExpression,
    val distinct: Boolean,
    val right: CanonicalSchemaExpression,
) : CanonicalSchemaPredicate

/** A structured LIKE comparison with a literal pattern. */
@Serializable
@SerialName("likeValue")
data class CanonicalLikeValuePredicate(
    val expression: CanonicalSchemaExpression,
    val operator: CanonicalLikeOperator,
    val pattern: String,
) : CanonicalSchemaPredicate

/** A structured LIKE comparison whose pattern is an expression. */
@Serializable
@SerialName("likeExpression")
data class CanonicalLikeExpressionPredicate(
    val expression: CanonicalSchemaExpression,
    val operator: CanonicalLikeOperator,
    val pattern: CanonicalSchemaExpression,
) : CanonicalSchemaPredicate

/** A structured NULL predicate. */
@Serializable
@SerialName("null")
data class CanonicalNullPredicate(val expression: CanonicalSchemaExpression, val operator: CanonicalNullOperator) :
    CanonicalSchemaPredicate

/** A structured predicate conjunction or disjunction. */
@Serializable
@SerialName("logical")
data class CanonicalLogicalPredicate(
    val left: CanonicalSchemaPredicate,
    val operator: CanonicalLogicalOperator,
    val right: CanonicalSchemaPredicate,
) : CanonicalSchemaPredicate

/** A stable comparison operator. */
@Serializable
enum class CanonicalComparisonOperator {
    @SerialName("eq")
    Eq,

    @SerialName("neq")
    Neq,

    @SerialName("lt")
    Lt,

    @SerialName("lte")
    Lte,

    @SerialName("gt")
    Gt,

    @SerialName("gte")
    Gte,
}

/** A stable LIKE operator. */
@Serializable
enum class CanonicalLikeOperator {
    @SerialName("like")
    Like,

    @SerialName("notLike")
    NotLike,

    @SerialName("iLike")
    ILike,

    @SerialName("notILike")
    NotILike,
}

/** A stable NULL predicate operator. */
@Serializable
enum class CanonicalNullOperator {
    @SerialName("isNull")
    IsNull,

    @SerialName("isNotNull")
    IsNotNull,
}

/** A stable logical predicate operator. */
@Serializable
enum class CanonicalLogicalOperator {
    @SerialName("and")
    And,

    @SerialName("or")
    Or,
}

/** A scalar literal tagged by its unambiguous stable representation. */
@Serializable
sealed interface CanonicalLiteralValue

@Serializable
@SerialName("null")
data object CanonicalNullValue : CanonicalLiteralValue

@Serializable
@SerialName("boolean")
data class CanonicalBooleanValue(val value: Boolean) : CanonicalLiteralValue

@Serializable
@SerialName("string")
data class CanonicalStringValue(val value: String) : CanonicalLiteralValue

@Serializable
@SerialName("integer")
data class CanonicalIntegerValue(val value: String) : CanonicalLiteralValue

@Serializable
@SerialName("decimal")
data class CanonicalDecimalValue(val value: String) : CanonicalLiteralValue

@Serializable
@SerialName("bytes")
data class CanonicalBytesValue(val hexadecimal: String) : CanonicalLiteralValue

/** Evolution hints intentionally separated from [CanonicalPhysicalSchema]. */
@Serializable
data class CanonicalEvolution(
    val tableRenames: List<CanonicalTableRename> = emptyList(),
    val columnRenames: List<CanonicalColumnRename> = emptyList(),
    /** Explicit Index name lineage, separate from the current physical Index definition. */
    val indexRenames: List<CanonicalIndexRename> = emptyList(),
    /** Reserved extension point; no constraint rename API exists yet. */
    val constraintRenames: List<CanonicalConstraintRename> = emptyList(),
    /** Reserved extension point; no sequence rename API exists yet. */
    val sequenceRenames: List<CanonicalSequenceRename> = emptyList(),
)

/** Ordered historical table names, retained exactly in application declaration order. */
@Serializable
data class CanonicalTableRename(val current: CanonicalTableReference, val previous: List<String>)

/** Ordered historical column names, retained exactly in application declaration order. */
@Serializable
data class CanonicalColumnRename(val table: CanonicalTableReference, val current: String, val previous: List<String>)

/** Ordered historical index names, retained exactly in application declaration order. */
@Serializable
data class CanonicalIndexRename(val current: CanonicalIndexReference, val previous: List<String>)

/** Reserved extensible constraint-rename shape. */
@Serializable
data class CanonicalConstraintRename(
    val table: CanonicalTableReference,
    val current: String,
    val previous: List<String>,
)

/** Reserved extensible sequence-rename shape. */
@Serializable
data class CanonicalSequenceRename(val current: CanonicalSequenceReference, val previous: List<String>)

/** A stable index reference for future evolution metadata. */
@Serializable
data class CanonicalIndexReference(val schemaName: String? = null, val name: String)
