package dev.quwac.kagiroi.migration

import dev.quwac.kagiroi.schema.SchemaRegistry

/**
 * Thin Kotlin/JS Node adapter for the machine-readable export contract.
 *
 * It writes one Canonical Schema JSON document to stdout and never writes diagnostics there.
 * The Gradle plugin generates an entry point that provides an explicit [SchemaRegistry].
 */
object NodeSchemaExporter {
    fun write(schema: SchemaRegistry) {
        println(exportSchema(schema))
    }
}
