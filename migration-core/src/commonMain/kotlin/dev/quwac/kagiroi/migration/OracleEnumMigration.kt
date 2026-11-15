package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val MAX_ORACLE_ENUM_VALUE_BYTES = 4000

/** Explicit Oracle enumeration name and its assigned text value. */
@Serializable
data class CanonicalOracleEnumEntry(val name: String, val value: String)

/** Native Oracle 23.4+ enumeration domain, without inferred ordinals. */
@Serializable
data class CanonicalOracleEnumDomain(
    val name: String,
    val entries: List<CanonicalOracleEnumEntry>,
    val schemaName: String? = null,
) {
    init {
        require(name.isNotBlank() && (schemaName == null || schemaName.isNotBlank())) { "Invalid domain name" }
        require(
            entries.isNotEmpty() && entries.map { it.name }.distinct().size == entries.size,
        ) { "Duplicate or missing enumeration names" }
        require(
            entries.map { it.value }.distinct().size == entries.size &&
                entries.all {
                    it.name.isNotBlank() && '\u0000' !in it.name && it.value.isNotEmpty() &&
                        '\u0000' !in it.value &&
                        it.value.encodeToByteArray().size <= MAX_ORACLE_ENUM_VALUE_BYTES
                },
        ) { "Enumeration values must be explicit and unique" }
    }
    internal fun reference() = CanonicalTableReference(schemaName, name)
}

/** Creates a native Oracle enumeration domain before its columns. */
@Serializable
@SerialName("createOracleEnumDomain")
data class CreateOracleEnumDomain(val domain: CanonicalOracleEnumDomain) : MigrationOperation

/** Drops an unused Oracle enumeration domain after its columns. */
@Serializable
@SerialName("dropOracleEnumDomain")
data class DropOracleEnumDomain(val domain: CanonicalTableReference) : MigrationOperation

internal fun diffOracleEnums(
    previous: CanonicalPhysicalSchema,
    desired: CanonicalPhysicalSchema,
): List<MigrationOperation> {
    val old = previous.oracleEnumDomains.associateBy { it.reference() }
    return desired.oracleEnumDomains.mapNotNull { domain ->
        val existing = old[domain.reference()]
        require(
            existing == null || existing == domain,
        ) { "Oracle enumeration entries cannot be altered; declare a new domain" }
        if (existing == null) CreateOracleEnumDomain(domain) else null
    }
}

internal fun CanonicalPhysicalSchema.applyOracleEnum(op: MigrationOperation): CanonicalPhysicalSchema = when (op) {
    is CreateOracleEnumDomain -> {
        require(oracleEnumDomains.none { it.reference() == op.domain.reference() }) { "Domain already exists" }
        copy(oracleEnumDomains = oracleEnumDomains + op.domain)
    }

    is DropOracleEnumDomain -> copy(oracleEnumDomains = oracleEnumDomains.filterNot { it.reference() == op.domain })

    else -> error("Not an Oracle enumeration operation")
}

internal fun validateOracleEnums(schema: CanonicalPhysicalSchema) {
    require(schema.oracleEnumDomains.map { it.reference() }.distinct().size == schema.oracleEnumDomains.size) {
        "Duplicate Oracle enumeration domain names"
    }
    schema.tables.flatMap { it.columns }.filter { it.type.id == "kagiroi.oracle.enumDomain" }.forEach { column ->
        val reference = column.type.enumReference()
        require(schema.oracleEnumDomains.any { it.name == reference.name && it.schemaName == reference.schemaName }) {
            "Column references an unmanaged Oracle enumeration domain"
        }
    }
}
