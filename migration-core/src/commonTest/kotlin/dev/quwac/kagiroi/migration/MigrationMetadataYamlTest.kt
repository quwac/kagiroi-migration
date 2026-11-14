package dev.quwac.kagiroi.migration

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MigrationMetadataYamlTest {
    @Test
    fun writesYamlUnderTheExistingMarkerAndReadsLegacyJsonWithIdenticalStepHashes() {
        val metadata = MigrationArtifactMetadata(dialect = "postgresql")
        val body = "SELECT 1;\r\n"
        val yaml = writeMigrationArtifact(metadata, body)
        assertContains(yaml, "kagiroi:\n  formatVersion: 1\n  dialect: postgresql\n  schemaDelta: {}\n*/")
        val legacy = parseMigrationArtifact(legacyJsonArtifact(metadata, body))
        val current = parseMigrationArtifact(yaml)
        assertEquals(legacy, current)
        assertEquals(legacy.steps, current.steps)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun preservesStringsExactNumbersNullsAndBooleansAcrossYamlTrees() {
        val values = buildJsonObject {
            listOf("0012", "null", "true", "0x10", "1e20", "", "<<", "*.html", "日本語", "a:\nb\r\n").forEachIndexed {
                    index,
                    text,
                ->
                put("string_$index", JsonPrimitive(text))
            }
            put("integer", JsonUnquotedLiteral("18446744073709551615"))
            put("decimal", JsonUnquotedLiteral("1.10000000000000000001"))
            put("bool", true)
            put("null", JsonNull)
        }
        val encoded = writeMigrationMetadataDocument(values)
        assertEquals(values, parseMigrationMetadataDocument(encoded))
        assertEquals(encoded, writeMigrationMetadataDocument(parseMigrationMetadataDocument(encoded)))
    }

    @Test
    fun escapesBothSqlCommentDelimitersWithoutChangingMetadataOrSql() {
        for (comment in listOf("a*/b", "a/*b", "/*/", "line\n*/ and /*\r\n", "literal \\u002f */")) {
            val table = CanonicalTable(name = "users", columns = emptyList(), comment = comment)
            val metadata = MigrationArtifactMetadata(
                dialect = "postgresql",
                schemaDelta = SchemaDelta(listOf(CreateTable(table))),
            )
            val body = "SELECT '/* */';\n"
            val written = writeMigrationArtifact(metadata, body)
            val payload = written.substringAfter("kagiroi:\n").substringBefore("*/")
            assertFalse("/*" in payload)
            assertEquals(MigrationArtifact(metadata, body), parseMigrationArtifact(written))
        }
    }

    @Test
    fun traversesExpressionDepthBeyondTheGeneralYamlConfigurationDefault() {
        var expression: CanonicalSchemaExpression = CanonicalFunctionExpression("currentTimestamp")
        repeat(60) { expression = CanonicalFunctionExpression("identity", listOf(expression)) }
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            schemaDelta = SchemaDelta(
                listOf(
                    SetDefault(CanonicalColumnReference(CanonicalTableReference(name = "users"), "name"), expression),
                ),
            ),
        )
        val written = writeMigrationArtifact(metadata, "SELECT 1;\n")
        assertEquals(metadata, parseMigrationArtifact(written).metadata)
    }

    @Test
    fun malformedYamlIsRejectedEvenWhenADefaultDialectIsSupplied() {
        for (payload in listOf(
            "formatVersion: [",
            "formatVersion: 1\nformatVersion: 1",
            "formatVersion: 1\nunknown: value",
        )) {
            assertFailsWith<IllegalArgumentException> {
                parseMigrationArtifact("/*\nkagiroi:\n$payload\n*/\n\nSELECT 1;", MigrationDialect.PostgreSql)
            }
        }
    }
}

internal fun legacyJsonArtifact(metadata: MigrationArtifactMetadata, body: String): String =
    "/*\nkagiroi:\n" + migrationArtifactJson.encodeToString(MigrationArtifactMetadata.serializer(), metadata) +
        "\n*/\n\n" + body
