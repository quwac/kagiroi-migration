package dev.quwac.kagiroi.migration

import dev.quwac.kagiroi.schema.SchemaRegistry
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema as registerSchema

/**
 * Compatibility name for Kagiroi's public application-schema registry.
 *
 * New code should import [SchemaRegistry] and `dev.quwac.kagiroi.schema.schema` directly. The
 * migration adapter consumes only the registry's public projection contract.
 */
@Deprecated(
    message = "Use dev.quwac.kagiroi.schema.SchemaRegistry",
    replaceWith = ReplaceWith(
        expression = "SchemaRegistry",
        imports = ["dev.quwac.kagiroi.schema.SchemaRegistry"],
    ),
)
typealias SchemaDefinition = SchemaRegistry

/**
 * Compatibility factory for an explicit application schema registry.
 *
 * New code should import `dev.quwac.kagiroi.schema.schema` directly.
 */
@Deprecated(
    message = "Use dev.quwac.kagiroi.schema.schema",
    replaceWith = ReplaceWith(
        expression = "schema(*tables)",
        imports = ["dev.quwac.kagiroi.schema.schema"],
    ),
)
fun schema(vararg tables: Table): SchemaRegistry = registerSchema(*tables)
