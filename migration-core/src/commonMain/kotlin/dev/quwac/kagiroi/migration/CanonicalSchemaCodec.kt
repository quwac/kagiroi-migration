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
    schema,
)

/** Decodes and integrity-checks a complete canonical desired schema document. */
fun decodeCanonicalSchema(json: String): CanonicalDesiredSchema = canonicalJson.decodeFromString(
    CanonicalDesiredSchema.serializer(),
    json,
).also { schema ->
    require(schema.formatVersion == CANONICAL_SCHEMA_FORMAT_VERSION) {
        "Unsupported Canonical Schema format version: ${schema.formatVersion}"
    }
    schema.requireValidMetadata()
}

/**
 * Converts a versioned Kagiroi Schema Projection document into a complete Canonical Schema.
 *
 * The projection intentionally has no integrity metadata. This function owns the Canonical Schema
 * hash and never needs access to runtime Table, Column, or expression implementation objects.
 */
fun canonicalizeSchemaProjection(json: String, expectedFormatVersion: Int): CanonicalDesiredSchema {
    val projection = canonicalJson.parseToJsonElement(json).jsonObject
    val formatVersion = projection.requiredElement("formatVersion").jsonPrimitive.int
    require(formatVersion == expectedFormatVersion) {
        "Unsupported Kagiroi Schema Projection format version: $formatVersion"
    }
    val physical = canonicalJson.decodeFromJsonElement(
        CanonicalPhysicalSchema.serializer(),
        projection.requiredElement("schema"),
    )
    val evolution = canonicalJson.decodeFromJsonElement(
        CanonicalEvolution.serializer(),
        projection.requiredElement("evolution"),
    )
    return CanonicalDesiredSchema(
        formatVersion = CANONICAL_SCHEMA_FORMAT_VERSION,
        schema = physical,
        evolution = evolution,
        metadata = CanonicalSchemaMetadata(
            CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
        ),
    )
}

/** Returns the exact stable JSON byte source used for a physical-schema hash. */
fun encodePhysicalSchema(schema: CanonicalPhysicalSchema): String = canonicalJson.encodeToString(
    CanonicalPhysicalSchema.serializer(),
    schema,
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
