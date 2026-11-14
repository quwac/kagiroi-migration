package dev.quwac.kagiroi.migration

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactDropArtifactTest {
    private val table = CanonicalTableReference("app", "documents")
    private val column = CanonicalColumnReference(table, "id")
    private val default: CanonicalSchemaExpression = CanonicalFunctionExpression("currentTimestamp")
    private val primary = CanonicalPrimaryKey("documents_pk", listOf("id"))
    private val unique = CanonicalUniqueConstraint("documents_uq", listOf("id"), "comment")
    private val foreign = CanonicalForeignKey("documents_fk", listOf("id"), table, listOf("id"))
    private val condition = CanonicalValueComparison(
        CanonicalColumnExpression(column),
        CanonicalComparisonOperator.Eq,
        CanonicalIntegerValue("1"),
        CanonicalColumnType("kagiroi.int"),
    )
    private val check = CanonicalCheckConstraint(
        "documents_ck",
        condition,
    )
    private val index = CanonicalIndex(
        name = "documents_idx",
        table = table,
        unique = false,
        keys = listOf(CanonicalColumnIndexKey(column)),
        predicate = condition,
        includedColumns = listOf(column),
        comment = "comment",
    )

    private val predicate = CanonicalSqlServerSecurityPredicate(
        CanonicalSqlServerPredicateIdentity(table, CanonicalSqlServerPredicateOperation.BeforeUpdate),
        CanonicalSecurityFunction(schemaName = "app", name = "tenant_predicate"),
        listOf("id"),
    )
    private val server = CanonicalSqlServerSecurityPolicy(
        CanonicalSecurityIdentity("app", "tenant"),
        CanonicalSqlServerSecurityPolicyDefinition(predicates = listOf(predicate)),
    )

    @Test
    fun namedStructuralDropsReadLegacyDefinitionsAndReplayByIdentity() {
        val before = CanonicalTable(
            schemaName = table.schemaName,
            name = table.name,
            columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int"), false, default = default)),
            primaryKey = primary,
            uniqueConstraints = listOf(unique),
            foreignKeys = listOf(foreign),
            checks = listOf(check),
        )
        val drops = listOf(
            legacyRoundTrip(DropDefault(column), mapOf("previous" to encoded(default)), emptySet(), setOf("previous")),
            legacyRoundTrip(
                DropPrimaryKey(table, primary),
                mapOf("primaryKey" to encoded(primary)),
                setOf("name"),
                setOf("primaryKey"),
            ),
            legacyRoundTrip(
                DropUnique(table, unique),
                mapOf("unique" to encoded(unique)),
                setOf("name"),
                setOf("unique", "columns"),
            ),
            legacyRoundTrip(
                DropForeignKey(table, foreign),
                mapOf("foreignKey" to encoded(foreign)),
                setOf("name"),
                setOf("foreignKey"),
            ),
            legacyRoundTrip(
                DropCheck(table, check),
                mapOf("check" to encoded(check)),
                setOf("name"),
                setOf("check"),
            ),
        )
        val replayed = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(before))), SchemaDelta(drops)))
        assertEquals(
            before.copy(
                columns = before.columns.map { it.copy(default = null) },
                primaryKey = null,
                uniqueConstraints = emptyList(),
                foreignKeys = emptyList(),
                checks = emptyList(),
            ),
            replayed.schema.tables.single(),
        )
        val sql = generateMigrationScript(MigrationPlan.Changes(drops), MigrationDialect.PostgreSql).body
        listOf("DROP DEFAULT", "documents_pk", "documents_uq", "documents_fk", "documents_ck").forEach {
            assertTrue(it in sql)
        }
    }

    @Test
    fun unnamedConstraintsKeepTheirIdentityAndLegacyColumnUniqueFlag() {
        val unnamedUnique = unique.copy(name = null)
        val unnamedForeign = foreign.copy(name = null)
        val unnamedCheck = check.copy(name = null)
        val drops = listOf(
            legacyRoundTrip(
                DropUnique(table, unnamedUnique),
                mapOf("unique" to encoded(unnamedUnique)),
                setOf("columns"),
                setOf("unique", "name"),
            ),
            legacyRoundTrip(
                DropForeignKey(table, unnamedForeign),
                mapOf("foreignKey" to encoded(unnamedForeign)),
                setOf("foreignKey"),
                setOf("name"),
            ),
            legacyRoundTrip(
                DropCheck(table, unnamedCheck),
                mapOf("check" to encoded(unnamedCheck)),
                setOf("check"),
                setOf("name"),
            ),
        )
        val before = CanonicalTable(
            schemaName = table.schemaName,
            name = table.name,
            columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int"), false, unique = true)),
            uniqueConstraints = listOf(unnamedUnique),
            foreignKeys = listOf(unnamedForeign),
            checks = listOf(unnamedCheck),
        )
        val after = replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(before))), SchemaDelta(drops))).schema
        assertEquals(emptyList(), after.tables.single().uniqueConstraints)
        assertFalse(after.tables.single().columns.single().unique)
        assertEquals(emptyList(), after.tables.single().foreignKeys)
        assertEquals(emptyList(), after.tables.single().checks)
        val sql = generateMigrationScript(
            MigrationPlan.Changes(listOf(drops.first())),
            MigrationDialect.PostgreSql,
        ).body
        assertTrue("ARRAY['id']::text[]" in sql)
    }

    @Test
    fun indexDropKeepsOwningTableAndNamespaceAcrossDialects() {
        val drop = legacyRoundTrip(
            DropIndex(index),
            mapOf("index" to encoded(index)),
            setOf("index", "table"),
            emptySet(),
        )
        val other = index.copy(table = table.copy(name = "other"))
        val replayed = replaySchemaDeltas(
            listOf(SchemaDelta(listOf(CreateIndex(index), CreateIndex(other))), SchemaDelta(listOf(drop))),
        )
        assertEquals(listOf(other), replayed.schema.indexes)
        val plan = MigrationPlan.Changes(listOf(drop))
        assertTrue(
            "DROP INDEX \"app\".\"documents_idx\"" in generateMigrationScript(plan, MigrationDialect.PostgreSql).body,
        )
        assertTrue("ON `app`.`documents`" in generateMigrationScript(plan, MigrationDialect.MySql).body)
        assertTrue("ON [app].[documents]" in generateMigrationScript(plan, MigrationDialect.SqlServer).body)
        assertTrue("DROP INDEX \"documents_idx\"" in generateMigrationScript(plan, MigrationDialect.Oracle).body)
    }

    @Test
    fun securityDropsDiscardDefinitionsAndFunctionsButKeepNativeIdentity() {
        val identity = CanonicalSecurityIdentity(table.schemaName, table.name, "tenant")
        val postgres = CanonicalPostgreSqlPolicy(
            identity,
            CanonicalPostgreSqlPolicyDefinition(
                using = condition,
            ),
        )
        val oracle = CanonicalOracleVpdPolicy(
            identity,
            CanonicalOracleVpdDefinition(
                CanonicalSecurityFunction(schemaName = "app", name = "tenant_predicate"),
                listOf(CanonicalOracleVpdStatement.Select),
            ),
        )
        val fixtures = listOf(
            Triple(DropPostgresPolicy(postgres), encoded(postgres), MigrationDialect.PostgreSql),
            Triple(DropSecurityPolicy(server), encoded(server), MigrationDialect.SqlServer),
            Triple(DropVpdPolicy(oracle), encoded(oracle), MigrationDialect.Oracle),
        )
        fixtures.forEach { (drop, legacy, dialect) ->
            val parsed = legacyRoundTrip(drop, mapOf("policy" to legacy), setOf("policy"), emptySet())
            val json = compactOperation(drop).getValue("policy").jsonObject
            assertEquals(encoded(if (drop is DropSecurityPolicy) server.identity else identity), json)
            assertEquals(
                generateMigrationScript(MigrationPlan.Changes(listOf(drop)), dialect),
                generateMigrationScript(MigrationPlan.Changes(listOf(parsed)), dialect),
            )
        }
        val initial = listOf(CreatePostgresPolicy(postgres), CreateSecurityPolicy(server), CreateVpdPolicy(oracle))
        val drops = fixtures.map { it.first }
        assertEquals(
            emptyList(),
            replaySchemaDeltas(listOf(SchemaDelta(initial), SchemaDelta(drops))).schema.securityObjects,
        )
    }

    @Test
    fun predicateDropRetainsOtherOperationsOnTheSameTable() {
        val predicateDrop = legacyRoundTrip(
            DropSecurityPredicate(server.identity, predicate),
            mapOf("predicate" to encoded(predicate)),
            setOf("policy", "predicate"),
            emptySet(),
        )
        assertEquals(encoded(predicate.identity), compactOperation(predicateDrop).getValue("predicate"))
        val nativeSql = generateMigrationScript(
            MigrationPlan.Changes(listOf(predicateDrop)),
            MigrationDialect.SqlServer,
        ).body
        assertTrue("DROP BLOCK PREDICATE ON [app].[documents] BEFORE UPDATE" in nativeSql)
        val retained = predicate.copy(
            identity = predicate.identity.copy(operation = CanonicalSqlServerPredicateOperation.Filter),
        )
        val withTwo = server.copy(definition = server.definition.copy(predicates = listOf(predicate, retained)))
        val managedTable = CanonicalTable(
            schemaName = table.schemaName,
            name = table.name,
            columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.int"), false)),
        )
        val remaining = replaySchemaDeltas(
            listOf(
                SchemaDelta(listOf(CreateTable(managedTable), CreateSecurityPolicy(withTwo))),
                SchemaDelta(listOf(predicateDrop)),
            ),
        ).schema.securityObjects.single() as CanonicalSqlServerSecurityPolicy
        assertEquals(listOf(retained), remaining.definition.predicates)
    }

    @Test
    fun conflictingLegacyAndCompactIdentifiersAreRejected() {
        val compact = compactOperation(DropPrimaryKey(table, "other"))
        val legacy = JsonObject(compact + ("primaryKey" to encoded(primary)))
        assertFailsWith<IllegalArgumentException> { parseMigrationArtifact(artifactText(legacy)) }
    }

    private fun legacyRoundTrip(
        operation: MigrationOperation,
        legacy: Map<String, JsonElement>,
        required: Set<String>,
        omitted: Set<String>,
    ): MigrationOperation {
        val compact = compactOperation(operation)
        assertTrue(compact.keys.containsAll(required))
        assertFalse("risk" in compact)
        omitted.forEach { assertFalse(it in compact) }
        val referenceFields = setOf("name", "columns", "table").filter { it != "table" || operation is DropIndex }
        val old = JsonObject((compact - referenceFields) + legacy + ("risk" to legacyRisk("review")))
        val parsed = parseMigrationArtifact(artifactText(old))
        assertEquals(operation, parsed.metadata.schemaDelta.operations.single())
        val roundTrip = parseMigrationArtifact(writeMigrationArtifact(parsed.metadata, parsed.body))
        assertEquals(parsed, roundTrip)
        assertEquals("SELECT 1;\n", roundTrip.body)
        return roundTrip.metadata.schemaDelta.operations.single()
    }

    private fun compactOperation(operation: MigrationOperation): JsonObject {
        val artifact = createMigrationArtifact(
            "SELECT 1;\n",
            MigrationDialect.PostgreSql,
            MigrationPlan.Changes(listOf(operation)),
        )
        val written = writeMigrationArtifact(artifact.metadata, artifact.body)
        val metadata = parseMigrationMetadataDocument(
            written.substringAfter("kagiroi:\n").substringBefore("*/"),
        )
        return metadata.jsonObject.getValue(
            "schemaDelta",
        ).jsonObject.getValue("operations").jsonArray.single().jsonObject
    }

    private fun artifactText(operation: JsonObject): String = "/*\nkagiroi:\n" +
        "{\"formatVersion\":1,\"dialect\":\"postgresql\",\"schemaDelta\":{\"operations\":[$operation]}}\n*/\n\nSELECT 1;\n"

    private inline fun <reified T> encoded(value: T): JsonElement = migrationArtifactJson.encodeToJsonElement(value)
}
