package dev.quwac.kagiroi.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads legacy operation metadata through the representation emitted by current artifacts. */
internal fun normalizeLegacyMigrationOperations(metadata: JsonElement): JsonElement {
    val root = metadata.jsonObject
    val delta = root["schemaDelta"] as? JsonObject ?: return metadata
    val operations = delta["operations"] as? JsonArray ?: return metadata
    val compact = JsonArray(operations.map { normalizeLegacyDrop(JsonObject(it.jsonObject - "risk")) })
    return JsonObject(root + ("schemaDelta" to JsonObject(delta + ("operations" to compact))))
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun normalizeLegacyDrop(operation: JsonObject): JsonObject {
    val fields = operation.toMutableMap()
    fun <T> replaceLegacy(key: String, decode: (JsonElement) -> T, convert: (T) -> MigrationOperation) {
        val legacy = fields[key] ?: return
        val compact = migrationArtifactJson.encodeToJsonElement(
            MigrationOperation.serializer(),
            convert(decode(legacy)),
        )
            .jsonObject
        fields.remove(key)
        compact.filterKeys { it != "type" }.forEach { (name, value) ->
            require(
                name !in fields || (name == "table" && fields[name] == value),
            ) { "Conflicting legacy and compact drop fields: $name" }
            fields[name] = value
        }
    }
    fun table(): CanonicalTableReference = migrationArtifactJson.decodeFromJsonElement(fields.getValue("table"))
    when (operation["type"]?.jsonPrimitive?.content) {
        "dropDefault" -> fields.remove("previous")?.let {
            migrationArtifactJson.decodeFromJsonElement<CanonicalSchemaExpression>(it)
        }

        "dropPrimaryKey" -> replaceLegacy(
            "primaryKey",
            { migrationArtifactJson.decodeFromJsonElement<CanonicalPrimaryKey>(it) },
            { DropPrimaryKey(table(), it) },
        )

        "dropUnique" -> replaceLegacy(
            "unique",
            { migrationArtifactJson.decodeFromJsonElement<CanonicalUniqueConstraint>(it) },
            { DropUnique(table(), it) },
        )

        "dropForeignKey" -> if (operation.hasNamedDefinition("foreignKey")) {
            replaceLegacy(
                "foreignKey",
                { migrationArtifactJson.decodeFromJsonElement<CanonicalForeignKey>(it) },
                { DropForeignKey(table(), it) },
            )
        }

        "dropCheck" -> if (operation.hasNamedDefinition("check")) {
            replaceLegacy(
                "check",
                { migrationArtifactJson.decodeFromJsonElement<CanonicalCheckConstraint>(it) },
                { DropCheck(table(), it) },
            )
        }

        "dropIndex" -> if (operation["index"]?.jsonObject?.containsKey("table") == true) {
            replaceLegacy(
                "index",
                { migrationArtifactJson.decodeFromJsonElement<CanonicalIndex>(it) },
                ::DropIndex,
            )
        }

        "dropPostgresPolicy", "dropSecurityPolicy", "dropVpdPolicy" -> {
            val policy = fields["policy"]?.jsonObject
            if (policy != null && "identity" in policy) {
                // Decode the complete legacy definition to keep malformed artifacts rejected.
                val identity = when (operation.getValue("type").jsonPrimitive.content) {
                    "dropPostgresPolicy" ->
                        migrationArtifactJson
                            .decodeFromJsonElement<CanonicalPostgreSqlPolicy>(policy).identity

                    "dropSecurityPolicy" ->
                        migrationArtifactJson
                            .decodeFromJsonElement<CanonicalSqlServerSecurityPolicy>(policy).identity

                    else -> migrationArtifactJson.decodeFromJsonElement<CanonicalOracleVpdPolicy>(policy).identity
                }
                fields["policy"] = migrationArtifactJson.encodeToJsonElement(identity)
            }
        }

        "dropSecurityPredicate" -> {
            val predicate = fields["predicate"]?.jsonObject
            if (predicate != null && "identity" in predicate) {
                val identity = migrationArtifactJson
                    .decodeFromJsonElement<CanonicalSqlServerSecurityPredicate>(predicate).identity
                fields["predicate"] = migrationArtifactJson.encodeToJsonElement(identity)
            }
        }
    }
    return JsonObject(fields)
}

private fun JsonObject.hasNamedDefinition(key: String): Boolean =
    get(key)?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull != null
