package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrossDialectExpansionTest {
    @Test
    fun rejectsColumnSnapshotsThatTargetADifferentNameOrType() {
        val table = CanonicalTableReference(name = "events")
        val reference = CanonicalColumnReference(table, "kind")
        val before = CanonicalColumnType("kagiroi.int32")
        val after = CanonicalColumnType("kagiroi.int64")
        for (definition in listOf(CanonicalColumn("other", after, false), CanonicalColumn("kind", before, false))) {
            assertFailsWith<IllegalArgumentException> {
                AlterColumnType(reference, before, after, definition = definition)
            }
        }
    }

    @Test
    fun retainsOldDropMetadataAndRejectsNativeEnumsOnOtherDatabases() {
        val plan = MigrationPlan.Changes(listOf(DropTable(CanonicalTableReference(name = "old"))))
        val artifact = createMigrationArtifact(
            generateMigrationScript(plan, MigrationDialect.PostgreSql).body,
            MigrationDialect.PostgreSql,
            plan,
        )
        assertTrue(!writeMigrationArtifact(artifact.metadata, artifact.body).contains("purge"))
        val domain = CanonicalOracleEnumDomain("state", listOf(CanonicalOracleEnumEntry("In", "in")))
        for (dialect in MigrationDialect.entries.filterNot { it == MigrationDialect.Oracle }) {
            assertFailsWith<IllegalArgumentException> {
                generateMigrationScript(MigrationPlan.Changes(listOf(CreateOracleEnumDomain(domain))), dialect)
            }
        }
        for (declaration in listOf("SET('read', 'write')", "ENUM('can''t', 'a,b')")) {
            val table = CanonicalTable(
                name = "native_values",
                columns = listOf(
                    CanonicalColumn("value", mysqlEnumType(declaration), false),
                ),
            )
            for (dialect in listOf(MigrationDialect.MySql, MigrationDialect.MariaDb)) {
                assertTrue(
                    generateMigrationScript(MigrationPlan.Changes(listOf(CreateTable(table))), dialect)
                        .body.contains(declaration),
                )
            }
        }
    }

    @Test
    fun verifiesDeclarationOrderAcrossEveryDialectWithoutChangingArtifactHashes() {
        val id = CanonicalColumn("id", CanonicalColumnType("kagiroi.int32"), false)
        val parent = CanonicalTable(
            name = "parent",
            columns = listOf(id),
            primaryKey = CanonicalPrimaryKey(columns = listOf("id")),
        )
        val key = CanonicalForeignKey(
            sourceColumns = listOf("first"),
            targetTable = CanonicalTableReference(name = "parent"),
            targetColumns = listOf("id"),
        )
        val child = CanonicalTable(
            name = "child",
            columns = listOf(id.copy(name = "first"), id.copy(name = "second")),
            foreignKeys = listOf(key, key.copy(sourceColumns = listOf("second"))),
        )
        val desired = replaySchemaDeltas(
            listOf(
                SchemaDelta(
                    listOf(
                        CreateTable(parent),
                        CreateTable(
                            child.copy(
                                columns = child.columns.reversed(),
                                foreignKeys = child.foreignKeys.reversed(),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val plan = MigrationPlan.Changes(listOf(CreateTable(parent), CreateTable(child)))
        for (dialect in MigrationDialect.entries) {
            val artifact = createMigrationArtifact(generateMigrationScript(plan, dialect).body, dialect, plan)
            val bytes = writeMigrationArtifact(artifact.metadata, artifact.body)
            verifyMigrationArtifacts(listOf(NamedMigrationArtifact("20261115000000.sql", artifact)), desired, dialect)
            assertEquals(artifact, parseMigrationArtifact(bytes))
            val changed = desired.copy(
                schema = desired.schema.copy(
                    tables = desired.schema.tables.map {
                        if (it.name == "child") {
                            it.copy(
                                foreignKeys = listOf(key.copy(targetColumns = listOf("missing"))),
                            )
                        } else {
                            it
                        }
                    },
                ),
            )
            assertFailsWith<IllegalArgumentException> {
                verifyMigrationArtifacts(
                    listOf(NamedMigrationArtifact("20261115000000.sql", artifact)),
                    changed,
                    dialect,
                )
            }
        }
    }

    @Test
    fun generatesNativeHistoryCommentsAndKeepsSqliteWithoutInventedCommentStorage() {
        for (dialect in MigrationDialect.entries) {
            val comments = migrationHistoryComments(dialect)
            assertEquals(if (dialect in setOf(MigrationDialect.Sqlite, MigrationDialect.D1)) 0 else 6, comments.size)
            if (comments.isNotEmpty()) assertTrue(comments.any { it.contains("Execution checkpoints") })
        }
        assertTrue(migrationHistoryComments(MigrationDialect.MySql).any { it.contains("TIMESTAMP NULL DEFAULT NULL") })
        assertTrue(
            migrationHistoryComments(
                MigrationDialect.SqlServer,
            ).all { it.contains("sp_updateextendedproperty") && it.contains("sp_addextendedproperty") },
        )
    }

    @Test
    fun mysqlAndMariaDbEnumsPreserveColumnAttributesAndRejectRemovedOrReorderedLabels() {
        val reference = CanonicalTableReference(name = "events")
        val original = CanonicalColumn(
            "kind",
            mysqlEnumType("ENUM('in', 'out')"),
            true,
            comment = "Kind",
            default = CanonicalLiteralExpression(CanonicalStringValue("in"), CanonicalColumnType("kagiroi.string")),
        )
        fun desired(column: CanonicalColumn) = replaySchemaDeltas(
            listOf(SchemaDelta(listOf(CreateTable(CanonicalTable(name = reference.name, columns = listOf(column)))))),
        )
        val before = desired(original)
        val after = desired(original.copy(type = mysqlEnumType("ENUM('in', 'out', 'new')")))
        for (dialect in listOf(MigrationDialect.MySql, MigrationDialect.MariaDb)) {
            val plan = diffSchema(before, after)
            val script = generateMigrationScript(plan, dialect)
            assertTrue(script.body.contains("ENUM('in', 'out', 'new') DEFAULT 'in'"))
            assertTrue(script.body.contains("COMMENT 'Kind'"))
            val artifact = createMigrationArtifact(script.body, dialect, plan)
            assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
            val replayed = replaySchemaDeltas(
                listOf(
                    SchemaDelta(diffSchema(emptyCanonicalSchema(), before).operations),
                    artifact.metadata.schemaDelta,
                ),
            )
            assertEquals(MigrationPlan.NoChanges, diffSchema(replayed, after))
        }
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                before,
                desired(original.copy(type = mysqlEnumType("ENUM('out', 'in')"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            generateMigrationScript(
                MigrationPlan.Changes(
                    listOf(
                        CreateTable(
                            CanonicalTable(
                                name = "bad",
                                columns = listOf(original.copy(type = mysqlEnumType("ENUM('in'); DROP TABLE events"))),
                            ),
                        ),
                    ),
                ),
                MigrationDialect.MySql,
            )
        }
    }

    @Test
    fun resolvesUnnamedKeysNativelyWhereAnonymousBlocksAreAvailable() {
        val table = CanonicalTableReference("app", "child")
        val key = CanonicalForeignKey(
            sourceColumns = listOf("org", "user_id"),
            targetTable = CanonicalTableReference("app", "parent"),
            targetColumns = listOf("org", "id"),
        )
        for (dialect in listOf(MigrationDialect.PostgreSql, MigrationDialect.SqlServer, MigrationDialect.Oracle)) {
            val plan = MigrationPlan.Changes(listOf(DropForeignKey(table, key), DropPrimaryKey(table)))
            val script = generateMigrationScript(plan, dialect)
            val artifact = createMigrationArtifact(script.body, dialect, plan)
            assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
            assertTrue(script.body.contains("Expected exactly one"))
        }
        for (dialect in listOf(
            MigrationDialect.MySql,
            MigrationDialect.MariaDb,
            MigrationDialect.Sqlite,
            MigrationDialect.D1,
        )) {
            assertFailsWith<IllegalArgumentException> {
                generateMigrationScript(
                    MigrationPlan.Changes(listOf(DropForeignKey(table, key))),
                    dialect,
                )
            }
        }
    }

    @Test
    fun createsOracleEnumerationDomainsBeforeTablesAndRejectsEntryMutations() {
        val domain = CanonicalOracleEnumDomain(
            "state",
            listOf(CanonicalOracleEnumEntry("Open", "open"), CanonicalOracleEnumEntry("Closed", "closed")),
            "app",
        )
        val type = CanonicalColumnType(
            "kagiroi.oracle.enumDomain",
            listOf(CanonicalTypeArgument("schemaName", "app"), CanonicalTypeArgument("name", "state")),
        )
        fun schema(value: CanonicalOracleEnumDomain) = replaySchemaDeltas(
            listOf(
                SchemaDelta(
                    listOf(
                        CreateOracleEnumDomain(value),
                        CreateTable(
                            CanonicalTable("app", "events", columns = listOf(CanonicalColumn("state", type, false))),
                        ),
                    ),
                ),
            ),
        )
        val desired = schema(domain)
        assertEquals(6, desired.formatVersion)
        val plan = diffSchema(emptyCanonicalSchema(), desired)
        assertTrue(plan.operations.first() is CreateOracleEnumDomain)
        val script = generateMigrationScript(plan, MigrationDialect.Oracle)
        assertTrue(
            script.body.contains("CREATE DOMAIN \"app\".\"state\" AS ENUM (\"Open\" = 'open', \"Closed\" = 'closed')"),
        )
        val artifact = createMigrationArtifact(script.body, MigrationDialect.Oracle, plan)
        verifyMigrationArtifacts(
            listOf(NamedMigrationArtifact("20261115000000.sql", artifact)),
            desired,
            MigrationDialect.Oracle,
        )
        assertEquals(desired, decodeCanonicalSchema(encodeCanonicalSchema(desired)))
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                desired,
                schema(domain.copy(entries = domain.entries + CanonicalOracleEnumEntry("New", "new"))),
            )
        }
        val removal = diffSchema(desired, emptyCanonicalSchema())
        assertTrue(removal.operations.last() is DropOracleEnumDomain)
        assertTrue(removal.operations.filterIsInstance<DropTable>().single().purge)
        assertTrue(generateMigrationScript(removal, MigrationDialect.Oracle).body.contains("PURGE"))
        assertFailsWith<IllegalArgumentException> {
            decodeCanonicalSchema(
                encodeCanonicalSchema(desired.copy(schema = desired.schema.copy(oracleEnumDomains = emptyList()))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            diffSchema(
                emptyCanonicalSchema(),
                desired.copy(schema = desired.schema.copy(oracleEnumDomains = listOf(domain, domain))),
            )
        }
    }
}

internal fun mysqlEnumType(declaration: String) = CanonicalColumnType(
    "kagiroi.mysql.declared",
    listOf(CanonicalTypeArgument("declaration", declaration)),
)
