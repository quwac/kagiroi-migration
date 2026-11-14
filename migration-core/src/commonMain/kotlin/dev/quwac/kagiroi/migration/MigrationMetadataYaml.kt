package dev.quwac.kagiroi.migration

import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.json.toJsonElement
import com.charleskorn.kaml.json.toYamlNode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

// Schema expression trees can be deeper than ordinary application configuration.
private val metadataYaml = Yaml(
    configuration = YamlConfiguration(
        singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
        maxNestingDepth = 512,
    ),
)
private val quotedMetadataYaml = Yaml(configuration = YamlConfiguration(maxNestingDepth = 512))

/** Keeps legacy JSON decoding strict and shares semantic decoding with YAML metadata. */
@OptIn(ExperimentalSerializationApi::class)
internal fun parseMigrationMetadataDocument(source: String): JsonElement {
    val document = source.trimIndent()
    return if (document.trimStart().startsWith("{")) {
        migrationArtifactJson.parseToJsonElement(document)
    } else {
        metadataYaml.parseToYamlNode(document).toJsonElement()
    }
}

/** Quotes SQL comment delimiters before escaping their slashes, preserving the original strings. */
@OptIn(ExperimentalSerializationApi::class)
internal fun writeMigrationMetadataDocument(value: JsonElement): String {
    val node = value.toYamlNode()
    val plain = metadataYaml.encodeToString(YamlNode.serializer(), node)
    val escaped = if ("*/" in plain || "/*" in plain) {
        quotedMetadataYaml.encodeToString(YamlNode.serializer(), node)
            .replace("/*", "\\u002f*")
            .replace("*/", "*\\u002f")
    } else {
        plain
    }
    return escaped.removeSuffix("\n")
}
