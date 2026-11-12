package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeSecurityMigrationTest {
    @Test
    fun addsSecurityToAnExistingVersionOneArtifactChain() {
        val desired = canonicalizeSchemaProjection(postgresProjection())
        val plain = schema(desired.schema.copy(securityObjects = emptyList()))
        fun artifact(plan: MigrationPlan): MigrationArtifact {
            val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
            val generated = createMigrationArtifact(
                script.body,
                MigrationDialect.PostgreSql,
                plan,
            )
            return parseMigrationArtifact(writeMigrationArtifact(generated.metadata, generated.body))
        }
        val initial = artifact(diffSchema(emptyCanonicalSchema(), plain))
        val securityPlan = diffSchema(plain, desired)
        assertTrue(securityPlan.operations.all { it is SecurityMigrationOperation })
        val secured = artifact(securityPlan)
        val chain = listOf(
            NamedMigrationArtifact("20261112090000_tables.sql", initial),
            NamedMigrationArtifact("20261112090100_security.sql", secured),
        )
        val verified = verifyMigrationArtifacts(chain, desired, MigrationDialect.PostgreSql)
        assertEquals(2, verified.migrationCount)
        assertEquals(physicalSchemaHash(desired), physicalSchemaHash(verified.reconstructedSchema))
    }

    @Test
    fun readsProjectionV2AndKeepsVersion1HashesWithoutSecurity() {
        val desired = canonicalizeSchemaProjection(postgresProjection())
        assertEquals(2, desired.formatVersion)
        assertEquals(2, desired.schema.securityObjects.size)
        assertEquals(desired, decodeCanonicalSchema(encodeCanonicalSchema(desired)))
        assertEquals("{\"tables\":[],\"sequences\":[],\"indexes\":[]}", encodePhysicalSchema(CanonicalPhysicalSchema()))
        assertFailsWith<IllegalArgumentException> { canonicalizeSchemaProjection(postgresProjection(), 1) }
        assertFailsWith<IllegalArgumentException> {
            canonicalizeSchemaProjection(
                postgresProjection().replace("\"formatVersion\":2", "\"formatVersion\":1"),
            )
        }
    }

    @Test
    fun plansNativePostgresEnableForceAndTypedPolicyAfterCreateTable() {
        val desired = canonicalizeSchemaProjection(postgresProjection())
        val plan = diffSchema(emptyCanonicalSchema(), desired)
        assertEquals(
            listOf(
                CreateTable::class,
                EnableRowLevelSecurity::class,
                ForceRowLevelSecurity::class,
                CreatePostgresPolicy::class,
            ),
            plan.operations.map { it::class },
        )
        val statements = MigrationSqlGenerator.generate(plan, MigrationDialect.PostgreSql).statements.map { it.sql }
        assertTrue(statements[1].endsWith("ENABLE ROW LEVEL SECURITY"))
        assertTrue(statements[2].endsWith("FORCE ROW LEVEL SECURITY"))
        assertTrue(statements[3].contains("USING (\"tenant_id\" = CAST(current_setting('app.tenant_id') AS UUID))"))
        assertEquals(desired.schema, replaySchemaDeltas(listOf(SchemaDelta(plan.operations))).schema)
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        val artifact = createMigrationArtifact(
            script.body,
            MigrationDialect.PostgreSql,
            plan,
        )
        val roundTrip = parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body))
        assertEquals(artifact, roundTrip)
        verifyMigrationArtifacts(
            listOf(NamedMigrationArtifact("20261112090000_security.sql", roundTrip)),
            desired,
            MigrationDialect.PostgreSql,
        )
    }

    @Test
    fun postgresSecurityOnlyChangesNeverGenerateTableOrColumnChanges() {
        val desired = canonicalizeSchemaProjection(postgresProjection())
        val plain = schema(desired.schema.copy(securityObjects = emptyList()))
        val add = diffSchema(plain, desired)
        assertTrue(add.operations.all { it is SecurityMigrationOperation })
        val removed = diffSchema(desired, plain)
        assertEquals(
            listOf(DropPostgresPolicy::class, ForceRowLevelSecurity::class, DisableRowLevelSecurity::class),
            removed.operations.map { it::class },
        )
        assertEquals(
            plain.schema,
            replaySchemaDeltas(
                listOf(
                    SchemaDelta(diffSchema(emptyCanonicalSchema(), desired).operations),
                    SchemaDelta(removed.operations),
                ),
            ).schema,
        )
        val unforced = schema(
            desired.schema.copy(
                securityObjects = desired.schema.securityObjects.map {
                    if (it is CanonicalPostgreSqlRlsTable) {
                        it.copy(definition = it.definition.copy(force = false))
                    } else {
                        it
                    }
                },
            ),
        )
        assertEquals(false, (diffSchema(desired, unforced).operations.single() as ForceRowLevelSecurity).force)
        assertEquals(true, (diffSchema(unforced, desired).operations.single() as ForceRowLevelSecurity).force)
        val changed = canonicalizeSchemaProjection(postgresProjection().replace("app.tenant_id", "app.other_id"))
        assertEquals(
            listOf(DropPostgresPolicy::class, CreatePostgresPolicy::class),
            diffSchema(desired, changed).operations.map { it::class },
        )
        val withoutPolicy = schema(
            desired.schema.copy(
                securityObjects = desired.schema.securityObjects.filterIsInstance<CanonicalPostgreSqlRlsTable>(),
            ),
        )
        assertIs<DropPostgresPolicy>(diffSchema(desired, withoutPolicy).operations.single())
        assertIs<CreatePostgresPolicy>(diffSchema(withoutPolicy, desired).operations.single())
        assertNotEquals(physicalSchemaHash(plain), physicalSchemaHash(desired))
    }

    @Test
    fun securityIsDroppedBeforeTablesAndColumnsAreRenamed() {
        val desired = canonicalizeSchemaProjection(postgresProjection())
        val drop = diffSchema(desired, emptyCanonicalSchema())
        assertIs<DropTable>(drop.operations.last())
        val renamed = canonicalizeSchemaProjection(postgresProjection().replace("tenant_id\"", "tenant_key\""))
        val renameDesired = renamed.copy(
            evolution = CanonicalEvolution(
                columnRenames = listOf(
                    CanonicalColumnRename(
                        table = CanonicalTableReference(name = "documents"),
                        current = "tenant_key",
                        previous = listOf("tenant_id"),
                    ),
                ),
            ),
        )
        val plan = diffSchema(desired, renameDesired)
        assertIs<DropPostgresPolicy>(plan.operations.first())
        assertTrue(plan.operations.any { it is RenameColumn })
        assertIs<CreatePostgresPolicy>(plan.operations.last())
    }

    @Test
    fun mergesSqlServerTablesAndDiffsIndividualPredicatesStateAndArgumentOrder() {
        val filter = predicate("documents", CanonicalSqlServerPredicateOperation.Filter)
        val block = predicate("orders", CanonicalSqlServerPredicateOperation.AfterInsert)
        val identity = CanonicalSecurityIdentity("security", "tenant_security")
        val policy = CanonicalSqlServerSecurityPolicy(
            identity,
            CanonicalSqlServerSecurityPolicyDefinition(predicates = listOf(filter, block)),
        )
        val desired = schema(serverTables(listOf(policy)))
        val create = diffSchema(emptyCanonicalSchema(), desired)
        assertIs<CreateSecurityPolicy>(create.operations.last())
        val sql = MigrationSqlGenerator.generate(create, MigrationDialect.SqlServer).statements.last().sql
        assertTrue(
            sql.contains(
                "ADD FILTER PREDICATE [security].[tenant_predicate]([tenant_id], [region]) ON [dbo].[documents]",
            ),
        )
        assertTrue(
            sql.contains(
                "ADD BLOCK PREDICATE [security].[tenant_predicate]([tenant_id], [region]) ON [dbo].[orders] AFTER INSERT",
            ),
        )
        assertEquals(
            desired.schema.normalizedSecurity(),
            replaySchemaDeltas(listOf(SchemaDelta(create.operations))).schema,
        )
        val reversed = schema(
            serverTables(listOf(policy.copy(definition = policy.definition.copy(predicates = listOf(block, filter))))),
        )
        assertEquals(physicalSchemaHash(desired), physicalSchemaHash(reversed))
        assertEquals(encodeCanonicalSchema(desired), encodeCanonicalSchema(reversed))
        assertIs<MigrationPlan.NoChanges>(diffSchema(desired, reversed))
        val less = schema(
            serverTables(listOf(policy.copy(definition = policy.definition.copy(predicates = listOf(filter))))),
        )
        assertIs<AddSecurityPredicate>(diffSchema(less, desired).operations.single())
        assertIs<DropSecurityPredicate>(diffSchema(desired, less).operations.single())
        val changed = schema(
            serverTables(
                listOf(
                    policy.copy(
                        definition = policy.definition.copy(
                            predicates = listOf(filter.copy(arguments = filter.arguments.reversed()), block),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(DropSecurityPredicate::class, AddSecurityPredicate::class),
            diffSchema(desired, changed).operations.map { it::class },
        )
        val disabled = schema(serverTables(listOf(policy.copy(definition = policy.definition.copy(enabled = false)))))
        assertIs<SetSecurityPolicyEnabled>(diffSchema(desired, disabled).operations.single())
    }

    @Test
    fun oracleNativeBlocksSurviveScriptParsingArtifactFinalizationAndReplay() {
        val identity = CanonicalSecurityIdentity(name = "documents", policyName = "tenant")
        val policy = CanonicalOracleVpdPolicy(
            identity,
            CanonicalOracleVpdDefinition(
                function = CanonicalSecurityFunction("SECURITY", "TENANT_SECURITY", "TENANT_PREDICATE"),
                statements = listOf(CanonicalOracleVpdStatement.Select, CanonicalOracleVpdStatement.Insert),
                updateCheck = true,
            ),
        )
        val desired = schema(CanonicalPhysicalSchema(tables = listOf(documents()), securityObjects = listOf(policy)))
        val plan = diffSchema(emptyCanonicalSchema(), desired)
        val sql = MigrationSqlGenerator.generate(plan, MigrationDialect.Oracle).statements.last().sql
        assertTrue(sql.startsWith("BEGIN DBMS_RLS.ADD_POLICY("))
        assertTrue(sql.contains("policy_function => '\"TENANT_SECURITY\".\"TENANT_PREDICATE\"'"))
        assertTrue(sql.contains("update_check => TRUE, enable => TRUE"))
        val script = generateMigrationScript(plan, MigrationDialect.Oracle)
        assertTrue(script.body.contains("END;\n/"))
        val parsed = MigrationScriptParser.parse(script.body, MigrationDialect.Oracle)
        assertEquals(2, parsed.steps.size)
        assertTrue(parsed.steps.all { it.kind == MigrationStepKind.NonTransactional })
        val artifact = createMigrationArtifact(
            script.body,
            MigrationDialect.Oracle,
            plan,
        )
        val named = NamedMigrationArtifact("20261112090000_security.sql", artifact)
        val verified = verifyMigrationArtifacts(listOf(named), desired, MigrationDialect.Oracle)
        assertEquals(desired.schema.normalizedSecurity(), verified.reconstructedSchema.schema)
        assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
        val disabled = schema(
            desired.schema.copy(
                securityObjects = listOf(policy.copy(definition = policy.definition.copy(enabled = false))),
            ),
        )
        assertIs<SetVpdPolicyEnabled>(diffSchema(desired, disabled).operations.single())
        val replacement = schema(
            desired.schema.copy(
                securityObjects = listOf(
                    policy.copy(
                        definition = policy.definition.copy(function = policy.definition.function.copy(name = "OTHER")),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(DropVpdPolicy::class, CreateVpdPolicy::class),
            diffSchema(desired, replacement).operations.map { it::class },
        )
        assertIs<DropVpdPolicy>(
            diffSchema(desired, schema(desired.schema.copy(securityObjects = emptyList()))).operations.single(),
        )
    }

    @Test
    fun rejectsUnsupportedDialectsAndInvalidNativeClauses() {
        val plan = diffSchema(emptyCanonicalSchema(), canonicalizeSchemaProjection(postgresProjection()))
        listOf(MigrationDialect.MySql, MigrationDialect.MariaDb, MigrationDialect.Sqlite, MigrationDialect.D1).forEach {
            assertFailsWith<MigrationSqlGenerationException> { MigrationSqlGenerator.generate(plan, it) }
        }
        assertFailsWith<IllegalArgumentException> {
            canonicalizeSchemaProjection(postgresProjection().replace("\"command\":\"all\"", "\"command\":\"insert\""))
        }
    }

    private fun documents(name: String = "documents", schema: String? = null): CanonicalTable = CanonicalTable(
        schemaName = schema,
        name = name,
        columns = listOf(
            CanonicalColumn("tenant_id", CanonicalColumnType("kagiroi.uuid"), nullable = false),
            CanonicalColumn("region", CanonicalColumnType("kagiroi.string"), nullable = false),
        ),
    )
    private fun serverTables(policies: List<CanonicalSecurityObject>): CanonicalPhysicalSchema =
        CanonicalPhysicalSchema(
            tables = listOf(documents(schema = "dbo"), documents("orders", "dbo")),
            securityObjects = policies,
        )
    private fun predicate(table: String, operation: CanonicalSqlServerPredicateOperation) =
        CanonicalSqlServerSecurityPredicate(
            CanonicalSqlServerPredicateIdentity(CanonicalTableReference("dbo", table), operation),
            CanonicalSecurityFunction(schemaName = "security", name = "tenant_predicate"),
            listOf("tenant_id", "region"),
        )
    private fun schema(physical: CanonicalPhysicalSchema): CanonicalDesiredSchema = CanonicalDesiredSchema(
        canonicalSchemaVersion(physical),
        physical,
        metadata = CanonicalSchemaMetadata(
            CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
        ),
    )

    private fun postgresProjection(): String = """
        {"formatVersion":2,"schema":{"tables":[{"name":"documents","columns":[
          {"name":"tenant_id","type":{"id":"kagiroi.uuid","arguments":[]},"nullable":false}
        ]}],"securityObjects":[
          {"kind":"postgresRlsTable","identity":{"name":"documents"},"definition":{"enabled":true,"force":true}},
          {"kind":"postgresPolicy","identity":{"name":"documents","policyName":"tenant"},"definition":{
            "command":"all","mode":"permissive","roles":[{"kind":"public"}],
            "using":{"kind":"expressionComparison","operator":"eq",
              "left":{"kind":"column","column":{"table":{"name":"documents"},"name":"tenant_id"}},
              "right":{"kind":"cast","targetType":{"id":"kagiroi.uuid","arguments":[]},
                "expression":{"kind":"function","function":"postgresCurrentSetting","arguments":[
                  {"kind":"literal","value":{"kind":"string","value":"app.tenant_id"},"type":{"id":"kagiroi.string","arguments":[]}}
                ]}}}
          }}
        ]},"evolution":{}}
    """.trimIndent()
}
