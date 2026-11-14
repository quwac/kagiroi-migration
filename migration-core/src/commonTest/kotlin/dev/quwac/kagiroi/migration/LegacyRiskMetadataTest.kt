package dev.quwac.kagiroi.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LegacyRiskMetadataTest {
    @Test
    fun readsLegacyRiskFromBothJsonAndYamlAndOmitsItOnRewrite() {
        val table = CanonicalTable(
            name = "users",
            columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), false)),
        )
        val operations = listOf(
            CreateTable(table),
            AddColumn(CanonicalTableReference(name = "users"), table.columns.single().copy(name = "legacy")),
            DropColumn(CanonicalColumnReference(CanonicalTableReference(name = "users"), "legacy")),
        )
        val plan = MigrationPlan.Changes(operations)
        val body = generateMigrationScript(plan, MigrationDialect.PostgreSql).body
        val metadata = createMigrationArtifact(body, MigrationDialect.PostgreSql, plan).metadata
        val encoded = migrationArtifactJson.encodeToJsonElement(metadata).jsonObject
        val oldOperations = operations.zip(listOf("safe", "review", "destructive")) { operation, level ->
            val encodedOperation = migrationArtifactJson.encodeToJsonElement(
                MigrationOperation.serializer(),
                operation,
            ).jsonObject
            JsonObject(encodedOperation + ("risk" to legacyRisk(level)))
        }
        val delta = buildJsonObject { put("operations", JsonArray(oldOperations)) }
        val legacy = JsonObject(encoded + ("schemaDelta" to delta))
        for (document in listOf(
            migrationArtifactJson.encodeToString(JsonElement.serializer(), legacy),
            writeMigrationMetadataDocument(legacy),
        )) {
            val parsed = parseMigrationArtifact("/*\nkagiroi:\n$document\n*/\n\n$body")
            assertEquals(MigrationArtifact(metadata, body), parsed)
            val rewritten = writeMigrationArtifact(parsed.metadata, parsed.body)
            assertFalse("risk:" in rewritten)
            assertFalse("Legacy assessment" in rewritten)
            assertEquals(parsed.steps, parseMigrationArtifact(rewritten).steps)
            assertEquals(
                replaySchemaDeltas(listOf(metadata.schemaDelta)),
                replaySchemaDeltas(listOf(parsed.metadata.schemaDelta)),
            )
        }
    }

    @Test
    fun ignoringLegacyRiskDoesNotIgnoreUnrelatedUnknownOperationFields() {
        assertFailsWith<IllegalArgumentException> {
            parseMigrationArtifact(
                """
                /*
                kagiroi:
                  formatVersion: 1
                  dialect: postgresql
                  schemaDelta:
                    operations:
                    - type: dropTable
                      table: {name: users}
                      risk: {level: destructive, reason: legacy}
                      unexpected: true
                */
                SELECT 1;
                """.trimIndent(),
            )
        }
    }
}

internal fun legacyRisk(level: String): JsonObject = buildJsonObject {
    put("level", level)
    put("reason", "Legacy assessment")
}
