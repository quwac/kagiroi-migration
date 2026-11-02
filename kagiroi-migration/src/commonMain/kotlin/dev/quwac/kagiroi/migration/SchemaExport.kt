package dev.quwac.kagiroi.migration

import dev.quwac.kagiroi.schema.SCHEMA_PROJECTION_FORMAT_VERSION
import dev.quwac.kagiroi.schema.SchemaRegistry

/**
 * Converts an explicit Kagiroi schema registry into its current physical schema and evolution
 * metadata through Kagiroi's stable projection contract.
 */
fun canonicalize(schema: SchemaRegistry): CanonicalDesiredSchema = canonicalizeSchemaProjection(
    json = schema.project().encode(),
    expectedFormatVersion = SCHEMA_PROJECTION_FORMAT_VERSION,
)

/** Returns compact deterministic Canonical Desired Schema JSON. */
fun exportSchema(schema: SchemaRegistry): String = encodeCanonicalSchema(canonicalize(schema))

/** Computes the physical-schema SHA-256 hash through the stable Schema Projection contract. */
fun physicalSchemaHash(schema: SchemaRegistry): String = physicalSchemaHash(canonicalize(schema))
