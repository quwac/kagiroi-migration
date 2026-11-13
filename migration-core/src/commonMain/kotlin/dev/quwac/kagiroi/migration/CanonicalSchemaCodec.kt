package dev.quwac.kagiroi.migration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val canonicalJson = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
    classDiscriminator = "kind"
}

/** Encodes the complete canonical desired schema document for the JS exporter contract. */
fun encodeCanonicalSchema(schema: CanonicalDesiredSchema): String = canonicalJson.encodeToString(
    CanonicalDesiredSchema.serializer(),
    schema.copy(schema = schema.schema.normalizedSecurity()),
)

/** Decodes and integrity-checks a complete canonical desired schema document. */
fun decodeCanonicalSchema(json: String): CanonicalDesiredSchema = canonicalJson.decodeFromString(
    CanonicalDesiredSchema.serializer(),
    json,
).also { schema ->
    require(schema.formatVersion == canonicalSchemaVersion(schema)) {
        "Unsupported Canonical Schema format version: ${schema.formatVersion}"
    }
    validateSecurityObjects(schema.schema)
    require(
        schema.formatVersion >= BACKFILL_CANONICAL_SCHEMA_FORMAT_VERSION || schema.evolution.notNullBackfills.isEmpty(),
    ) {
        "notNullBackfill requires canonical schema version 3"
    }
    require(
        schema.formatVersion >= TYPE_CONVERSION_CANONICAL_SCHEMA_FORMAT_VERSION ||
            schema.evolution.typeConversions.isEmpty(),
    ) {
        "typeUsing requires canonical schema version 4"
    }
    schema.typeConversions()
    schema.notNullBackfills()
    schema.requireValidMetadata()
}

/**
 * Converts a versioned Kagiroi Schema Projection document into a complete Canonical Schema.
 *
 * The projection intentionally has no integrity metadata. This function owns the Canonical Schema
 * hash and never needs access to runtime Table, Column, or expression implementation objects.
 */
fun canonicalizeSchemaProjection(json: String, expectedFormatVersion: Int): CanonicalDesiredSchema =
    canonicalizeProjection(json, expectedFormatVersion)

/** Accepts base version 1, native security version 2, and typed backfill version 3 projections. */
fun canonicalizeSchemaProjection(json: String): CanonicalDesiredSchema = canonicalizeProjection(json, null)

private fun canonicalizeProjection(json: String, expectedFormatVersion: Int?): CanonicalDesiredSchema {
    val projection = canonicalJson.parseToJsonElement(json).jsonObject
    val formatVersion = projection.requiredElement("formatVersion").jsonPrimitive.int
    val acceptedVersion = if (expectedFormatVersion == null) {
        formatVersion in CANONICAL_SCHEMA_FORMAT_VERSION..TYPE_CONVERSION_CANONICAL_SCHEMA_FORMAT_VERSION
    } else {
        formatVersion == expectedFormatVersion
    }
    require(acceptedVersion) { "Unsupported Kagiroi Schema Projection format version: $formatVersion" }
    val physical = canonicalJson.decodeFromJsonElement(
        CanonicalPhysicalSchema.serializer(),
        projection.requiredElement("schema"),
    )
    require(
        formatVersion >= 2 || physical.securityObjects.isEmpty(),
    ) { "Security objects require projection version 2" }
    val normalized = physical.normalizedSecurity()
    validateSecurityObjects(normalized)
    val evolution = canonicalJson.decodeFromJsonElement(
        CanonicalEvolution.serializer(),
        projection.requiredElement("evolution"),
    )
    require(formatVersion >= BACKFILL_CANONICAL_SCHEMA_FORMAT_VERSION || evolution.notNullBackfills.isEmpty()) {
        "notNullBackfill requires schema projection version 3"
    }
    require(formatVersion >= TYPE_CONVERSION_CANONICAL_SCHEMA_FORMAT_VERSION || evolution.typeConversions.isEmpty()) {
        "typeUsing requires schema projection version 4"
    }
    return CanonicalDesiredSchema(
        formatVersion = if (evolution.typeConversions.isNotEmpty()) {
            TYPE_CONVERSION_CANONICAL_SCHEMA_FORMAT_VERSION
        } else if (evolution.notNullBackfills.isNotEmpty()) {
            BACKFILL_CANONICAL_SCHEMA_FORMAT_VERSION
        } else {
            canonicalSchemaVersion(
                normalized,
            )
        },
        schema = normalized,
        evolution = evolution,
        metadata = CanonicalSchemaMetadata(
            CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(normalized)),
        ),
    )
}

/** Returns the exact stable JSON byte source used for a physical-schema hash. */
fun encodePhysicalSchema(schema: CanonicalPhysicalSchema): String = canonicalJson.encodeToString(
    CanonicalPhysicalSchema.serializer(),
    schema.normalizedSecurity(),
)

/** Computes SHA-256 over Canonical physical-schema JSON and excludes evolution metadata. */
fun physicalSchemaHash(schema: CanonicalDesiredSchema): String = physicalSchemaHash(schema.schema)

/** Computes SHA-256 over a physical schema independently of evolution metadata. */
fun physicalSchemaHash(schema: CanonicalPhysicalSchema): String = sha256Hex(encodePhysicalSchema(schema))

private fun CanonicalDesiredSchema.requireValidMetadata() {
    require(metadata.physicalSchemaHash.algorithm == CanonicalHashAlgorithm.Sha256) {
        "Unsupported Canonical Schema hash algorithm: ${metadata.physicalSchemaHash.algorithm}"
    }
    require(metadata.physicalSchemaHash.value == physicalSchemaHash(this)) {
        "Canonical Schema physicalSchemaHash does not match schema"
    }
}

private fun JsonObject.requiredElement(name: String) = requireNotNull(this[name]) {
    "Kagiroi Schema Projection is missing '$name'"
}
