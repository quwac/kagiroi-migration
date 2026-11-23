package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val MAX_ENUM_LABEL_BYTES = 63

/** A managed native PostgreSQL ENUM in label comparison order. */
@Serializable
data class CanonicalPostgresEnum(val name: String, val labels: List<String>, val schemaName: String? = null) {
    init {
        require(name.isNotBlank() && (schemaName == null || schemaName.isNotBlank())) { "Invalid ENUM name" }
        require(
            labels.isNotEmpty() && labels.distinct().size == labels.size,
        ) { "ENUM labels must be nonempty and unique" }
        require(
            labels.all { '\u0000' !in it && it.encodeToByteArray().size <= MAX_ENUM_LABEL_BYTES },
        ) { "Invalid PostgreSQL ENUM label" }
    }
}

/** Physical ENUM identity without its label list. */
@Serializable
data class CanonicalPostgresEnumReference(val name: String, val schemaName: String? = null)

/** Creates a native PostgreSQL ENUM before dependent columns. */
@Serializable
@SerialName("createPostgresEnum")
data class CreatePostgresEnum(val enumType: CanonicalPostgresEnum) : MigrationOperation

/** Drops an unused ENUM after dependent columns or tables have been removed. */
@Serializable
@SerialName("dropPostgresEnum")
data class DropPostgresEnum(val enumType: CanonicalPostgresEnumReference) : MigrationOperation

/** Appends a label in its own committed execution step before later operations can use it. */
@Serializable
@SerialName("addPostgresEnumValue")
data class AddPostgresEnumValue(val enumType: CanonicalPostgresEnumReference, val label: String) : MigrationOperation

internal fun CanonicalPostgresEnum.reference() = CanonicalPostgresEnumReference(name, schemaName)

internal fun diffPostgresEnums(
    previous: CanonicalPhysicalSchema,
    desired: CanonicalPhysicalSchema,
): List<MigrationOperation> {
    val old = previous.enumTypes.associateBy { it.reference() }
    return desired.enumTypes.sortedWith(
        compareBy(CanonicalPostgresEnum::schemaName, CanonicalPostgresEnum::name),
    ).flatMap { type ->
        val before = old[type.reference()]
        if (before == null) {
            listOf(CreatePostgresEnum(type))
        } else {
            require(type.labels.take(before.labels.size) == before.labels) {
                "ENUM label removal, renaming and reordering are unsupported: ${type.name}"
            }
            type.labels.drop(before.labels.size).map { AddPostgresEnumValue(type.reference(), it) }
        }
    }
}

internal fun validatePostgresEnums(schema: CanonicalPhysicalSchema) {
    require(schema.enumTypes.map { it.reference() }.distinct().size == schema.enumTypes.size) { "Duplicate ENUM names" }
    schema.tables.flatMap { it.columns }.filter { it.type.id == "kagiroi.postgres.enum" }.forEach { column ->
        require(
            schema.enumTypes.any { it.reference() == column.type.enumReference() },
        ) { "Column references an unmanaged ENUM" }
    }
}

internal fun CanonicalColumnType.enumReference(): CanonicalPostgresEnumReference {
    require(
        arguments.map { it.name }.distinct().size == arguments.size && arguments.all {
            it.name in setOf(
                "name",
                "schemaName",
            )
        },
    ) {
        "Invalid ENUM type arguments"
    }
    return CanonicalPostgresEnumReference(
        requireNotNull(arguments.singleOrNull { it.name == "name" }?.value),
        arguments.singleOrNull { it.name == "schemaName" }?.value,
    )
}

internal fun CanonicalPhysicalSchema.applyPostgresEnum(op: MigrationOperation): CanonicalPhysicalSchema = when (op) {
    is CreatePostgresEnum -> {
        require(enumTypes.none { it.reference() == op.enumType.reference() }) { "ENUM already exists" }
        copy(enumTypes = enumTypes + op.enumType)
    }

    is DropPostgresEnum -> copy(enumTypes = enumTypes.filterNot { it.reference() == op.enumType })

    is AddPostgresEnumValue -> {
        require(enumTypes.count { it.reference() == op.enumType } == 1) { "Unknown ENUM type" }
        copy(
            enumTypes = enumTypes.map {
                if (it.reference() == op.enumType) it.copy(labels = it.labels + op.label) else it
            },
        )
    }

    else -> error("Not an ENUM operation")
}
