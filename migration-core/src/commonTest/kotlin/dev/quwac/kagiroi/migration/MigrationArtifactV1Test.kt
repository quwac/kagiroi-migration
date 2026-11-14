package dev.quwac.kagiroi.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationArtifactV1Test {
    @Test
    fun incompatible_format_and_missing_file_delta_are_rejected() {
        val text = writeMigrationArtifact(MigrationArtifactMetadata(dialect = "postgresql"), "SELECT 1;")
        listOf(2, 3).forEach { version ->
            assertFailsWith<IllegalArgumentException> {
                parseMigrationArtifact(text.replace("formatVersion: 1", "formatVersion: $version"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            parseMigrationArtifact(
                "/*\nkagiroi:\n{\"formatVersion\":1,\"dialect\":\"postgresql\"}\n*/\nSELECT 1;",
            )
        }
    }

    @Test
    fun metadata_round_trip_preserves_sql_body() {
        val artifact = MigrationArtifact(MigrationArtifactMetadata(dialect = "postgresql"), "SELECT '*/';\n")

        assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
    }

    @Test
    fun null_metadata_fields_are_omitted_while_non_null_values_and_flags_are_preserved() {
        val table = CanonicalTable(
            schemaName = "app",
            name = "users",
            comment = "User profiles",
            columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.uuid"), false)),
        )
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            schemaDelta = SchemaDelta(listOf(CreateTable(table))),
        )
        val body = "SELECT 1;\n"
        val written = writeMigrationArtifact(metadata, body)
        val header = written.substringBefore("*/")

        assertFalse(header.contains(": null"))
        assertContains(header, "schemaName: app")
        assertContains(header, "comment: User profiles")
        assertContains(header, "nullable: false")
        assertContains(header, "unique: false")
        assertEquals(MigrationArtifact(metadata, body), parseMigrationArtifact(written))
    }

    @Test
    fun nullable_change_endpoints_are_optional_and_old_explicit_nulls_still_decode() {
        val table = CanonicalTableReference(name = "users")
        val column = CanonicalColumnReference(table, "id")
        val generated = CanonicalGeneratedColumn(
            CanonicalColumnExpression(column),
            CanonicalGeneratedColumnStorage.Stored,
        )
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            schemaDelta = SchemaDelta(
                listOf(
                    AlterIdentity(column, from = CanonicalIdentity(), to = null),
                    AlterGeneratedColumn(column, from = null, to = generated),
                    AlterColumnComment(column, from = null, to = "Identifier"),
                    AlterTableComment(table, from = "Old comment", to = null),
                ),
            ),
        )
        val body = "SELECT 1;\n"
        val compact = writeMigrationArtifact(metadata, body)
        val explicitJson = Json(migrationArtifactJson) { explicitNulls = true }
        val legacy = "/*\nkagiroi:\n" +
            explicitJson.encodeToString(MigrationArtifactMetadata.serializer(), metadata) + "\n*/\n\n" + body
        val current = parseMigrationArtifact(compact)

        assertFalse(compact.substringBefore("*/").contains(": null"))
        assertContains(legacy, ": null")
        assertEquals(metadata, current.metadata)
        assertEquals(current, parseMigrationArtifact(legacy))
        assertEquals(current.steps, parseMigrationArtifact(legacy).steps)
        assertEquals(
            metadata,
            explicitJson.decodeFromJsonElement(
                MigrationArtifactMetadata.serializer(),
                parseMigrationMetadataDocument(compact.substringAfter("kagiroi:\n").substringBefore("*/")),
            ),
        )
    }

    @Test
    fun optional_empty_arrays_are_omitted_without_changing_canonical_hash_encoding() {
        val table = CanonicalTable(
            name = "users",
            columns = listOf(CanonicalColumn("id", CanonicalColumnType("kagiroi.uuid"), false)),
        )
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            schemaDelta = SchemaDelta(listOf(CreateTable(table))),
        )
        val body = "SELECT 1;\n"
        val compact = writeMigrationArtifact(metadata, body)
        val legacy = "/*\nkagiroi:\n" +
            migrationArtifactJson.encodeToString(MigrationArtifactMetadata.serializer(), metadata) + "\n*/\n\n" + body

        assertFalse(compact.substringBefore("*/").contains("[]"))
        assertContains(compact, "nullable: false")
        assertContains(compact, "unique: false")
        assertContains(legacy, "[]")
        assertEquals(parseMigrationArtifact(legacy), parseMigrationArtifact(compact))
        assertEquals(parseMigrationArtifact(legacy).steps, parseMigrationArtifact(compact).steps)
        val canonical = encodePhysicalSchema(CanonicalPhysicalSchema(tables = listOf(table)))
        assertContains(canonical, "\"arguments\":[]")
        assertContains(canonical, "\"uniqueConstraints\":[]")
    }

    @Test
    fun nested_polymorphic_arguments_and_non_empty_arrays_round_trip() {
        val reference = CanonicalTableReference(name = "users")
        val name = CanonicalColumnReference(reference, "name")
        val table = CanonicalTable(
            name = "users",
            columns = listOf(
                CanonicalColumn(
                    "id",
                    CanonicalColumnType("kagiroi.uuid"),
                    false,
                    default = CanonicalFunctionExpression("gen_random_uuid"),
                ),
                CanonicalColumn(
                    "name",
                    CanonicalColumnType("kagiroi.string", listOf(CanonicalTypeArgument("length", "64"))),
                    false,
                ),
            ),
            uniqueConstraints = listOf(CanonicalUniqueConstraint(columns = listOf("name"))),
        )
        val index = CanonicalIndex(
            name = "users_lower_name",
            table = reference,
            unique = false,
            keys = listOf(
                CanonicalExpressionIndexKey(
                    CanonicalFunctionExpression("lower", listOf(CanonicalColumnExpression(name))),
                ),
            ),
        )
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            schemaDelta = SchemaDelta(listOf(CreateTable(table), CreateIndex(index))),
        )
        val text = writeMigrationArtifact(metadata, "SELECT 1;\n")

        assertFalse(text.substringBefore("*/").contains("[]"))
        assertContains(text, "value: \"64\"")
        assertContains(text, "function: lower")
        assertEquals(metadata, parseMigrationArtifact(text).metadata)
        assertContains(encodePhysicalSchema(CanonicalPhysicalSchema(tables = listOf(table))), "\"arguments\":[]")
    }

    @Test
    fun optional_empty_arrays_inside_security_predicates_are_omitted() {
        val condition = CanonicalValueComparison(
            CanonicalFunctionExpression("lower", listOf(CanonicalFunctionExpression("postgresCurrentUser"))),
            CanonicalComparisonOperator.Eq,
            CanonicalStringValue("member"),
            CanonicalColumnType("kagiroi.string"),
        )
        val policy = CanonicalPostgreSqlPolicy(
            CanonicalSecurityIdentity(name = "users", policyName = "members"),
            CanonicalPostgreSqlPolicyDefinition(
                using = condition,
                withCheck = condition.copy(
                    valueType = CanonicalColumnType("kagiroi.string", listOf(CanonicalTypeArgument("length", "64"))),
                ),
            ),
        )
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            schemaDelta = SchemaDelta(listOf(CreatePostgresPolicy(policy))),
        )
        val body = "SELECT 1;\n"
        val compact = writeMigrationArtifact(metadata, body)
        val legacy = "/*\nkagiroi:\n" +
            migrationArtifactJson.encodeToString(MigrationArtifactMetadata.serializer(), metadata) + "\n*/\n\n" + body

        assertFalse(compact.substringBefore("*/").contains("[]"))
        assertContains(compact, "using:")
        assertContains(compact, "withCheck:")
        assertContains(compact, "function: lower")
        assertContains(compact, "value: \"64\"")
        assertEquals(metadata, parseMigrationArtifact(compact).metadata)
        assertEquals(parseMigrationArtifact(legacy), parseMigrationArtifact(compact))
        assertEquals(parseMigrationArtifact(legacy).steps, parseMigrationArtifact(compact).steps)
        val canonical = encodePhysicalSchema(CanonicalPhysicalSchema(securityObjects = listOf(policy)))
        assertContains(canonical, "\"arguments\":[]")
    }

    @Test
    fun backfill_nested_rows_generated_cases_and_type_conversions_compact_recursively() {
        val table = CanonicalTableReference(name = "users")
        val type = CanonicalColumnType("kagiroi.string")
        val value = CanonicalFunctionExpression("currentUser")
        val predicate = CanonicalValueComparison(
            value,
            CanonicalComparisonOperator.Eq,
            CanonicalStringValue("member"),
            type,
        )
        val expression = CanonicalCaseExpression(listOf(CanonicalCaseBranch(predicate, value)), value)
        val column = CanonicalColumn("name", type, false)
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            schemaDelta = SchemaDelta(
                listOf(
                    AddColumnWithBackfill(
                        table,
                        column,
                        listOf(
                            CanonicalBackfillInsert(
                                table,
                                listOf(listOf(CanonicalBackfillAssignment("name", expression))),
                            ),
                            CanonicalBackfillUpdate(
                                table,
                                listOf(CanonicalBackfillAssignment("name", value)),
                                predicate,
                            ),
                        ),
                    ),
                    AddColumn(
                        table,
                        column.copy(
                            name = "generated_name",
                            generated = CanonicalGeneratedColumn(expression, CanonicalGeneratedColumnStorage.Stored),
                        ),
                    ),
                    AlterColumnType(CanonicalColumnReference(table, "name"), type, type, using = expression),
                    AddCheck(table, CanonicalCheckConstraint("member_check", predicate)),
                ),
            ),
        )
        val compact = writeMigrationArtifact(metadata, "SELECT 1;\n")
        assertFalse(compact.substringBefore("*/").contains("[]"))
        assertContains(compact, "rows:")
        assertContains(compact, "branches:")
        assertEquals(metadata, parseMigrationArtifact(compact).metadata)
    }

    @Test
    fun empty_operations_can_be_omitted_but_required_table_columns_cannot() {
        val text = writeMigrationArtifact(MigrationArtifactMetadata(dialect = "postgresql"), "SELECT 1;")
        assertFalse(text.substringBefore("*/").contains("operations:"))
        assertTrue(parseMigrationArtifact(text).metadata.schemaDelta.operations.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            parseMigrationArtifact(
                """
                /*
                kagiroi:
                {"formatVersion":1,"dialect":"postgresql","schemaDelta":{"operations":[
                  {"type":"createTable","table":{"name":"users"},"risk":{"level":"safe","reason":"fixture"}}
                ]}}
                */
                SELECT 1;
                """.trimIndent(),
            )
        }
    }

    @Test
    fun metadata_parser_accepts_crlf_without_rewriting_the_sql_body() {
        val artifact = MigrationArtifact(MigrationArtifactMetadata(dialect = "postgresql"), "SELECT 1;\r\n")
        val written = writeMigrationArtifact(artifact.metadata, artifact.body)
        val header = written.substringBefore("*/") + "*/"
        val text = header.replace("\n", "\r\n") + "\r\n\r\n" + artifact.body

        assertEquals(artifact, parseMigrationArtifact(text))
    }

    @Test
    fun generated_schema_sql_has_a_file_level_editing_warning() {
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            schemaDelta = SchemaDelta(
                listOf(
                    AddColumn(
                        CanonicalTableReference(name = "users"),
                        CanonicalColumn("email", CanonicalColumnType("kagiroi.string"), true),
                    ),
                ),
            ),
        )
        val body = "ALTER TABLE users ADD COLUMN email TEXT;\n"
        val written = writeMigrationArtifact(metadata, body)

        assertTrue(written.startsWith("-- Generated by Kagiroi Migration. DO NOT EDIT THIS FILE.\n"))
        assertEquals(body, parseMigrationArtifact(written).body)
        assertEquals(MigrationArtifact(metadata, body).steps, parseMigrationArtifact(written).steps)
    }

    @Test
    fun custom_sql_identifies_the_editable_body_and_keeps_existing_headers_readable() {
        val metadata = MigrationArtifactMetadata(dialect = "postgresql")
        val body = "INSERT INTO users (name) VALUES ('demo');\n"
        val written = writeMigrationArtifact(metadata, body)
        val previousHeader = written.substring(written.indexOf("/*\nkagiroi:"))

        assertTrue(written.startsWith("-- Custom Kagiroi migration. Edit SQL below the metadata block.\n"))
        assertContains(written.substringBefore("/*"), "Do not edit the generated metadata block.")
        assertEquals(MigrationArtifact(metadata, body), parseMigrationArtifact(written))
        assertEquals(MigrationArtifact(metadata, body), parseMigrationArtifact(previousHeader))
    }

    @Test
    fun handwritten_sql_uses_the_configured_dialect_and_preserves_its_entire_body() {
        val body = "-- Seed data\r\nBEGIN; SELECT 'semi;colon'; COMMIT;\r\n"
        MigrationDialect.entries.forEach { dialect ->
            val artifact = parseMigrationArtifact(body, dialect)
            assertEquals(body, artifact.body)
            assertEquals(dialect.id, artifact.metadata.dialect)
            assertTrue(artifact.metadata.schemaDelta.operations.isEmpty())
            assertEquals(1, artifact.steps.size)
        }
        assertFailsWith<IllegalArgumentException> { parseMigrationArtifact(body) }
    }

    @Test
    fun removing_an_empty_metadata_header_preserves_applied_step_hashes() {
        val metadata = MigrationArtifactMetadata(dialect = "postgresql")
        val body = "BEGIN; INSERT INTO users (name) VALUES ('demo'); COMMIT;\n"
        val embedded = parseMigrationArtifact(writeMigrationArtifact(metadata, body))
        val handwritten = parseMigrationArtifact(body, MigrationDialect.PostgreSql)

        assertEquals(embedded, handwritten)
        assertEquals(embedded.steps, handwritten.steps)
    }

    @Test
    fun configured_dialect_does_not_turn_broken_generated_metadata_into_handwritten_sql() {
        listOf(
            "/*\nkagiroi:\n{broken JSON\n*/\nSELECT 1;",
            "/* kagiroi: {broken JSON */\nSELECT 1;",
            "-- Generated by Kagiroi Migration. DO NOT EDIT THIS FILE.\nSELECT 1;",
            "-- Custom Kagiroi migration. Edit SQL below the metadata block.\nSELECT 1;",
            "/*\nkagiroi:\n{\"formatVersion\":2,\"dialect\":\"postgresql\",\"schemaDelta\":{operations::[]}}\n*/\nSELECT 1;",
        ).forEach { text ->
            assertFailsWith<IllegalArgumentException> { parseMigrationArtifact(text, MigrationDialect.PostgreSql) }
        }
    }

    @Test
    fun handwritten_comments_only_and_incomplete_transactions_are_rejected() {
        assertFailsWith<IllegalArgumentException> {
            validateMigrationArtifacts(
                listOf(
                    NamedMigrationArtifact(
                        "20261101090000_seed.sql",
                        parseMigrationArtifact("-- Write SQL here", MigrationDialect.PostgreSql),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseMigrationArtifact("BEGIN; SELECT 1;", MigrationDialect.PostgreSql).steps
        }
    }

    @Test
    fun filename_is_identity_and_order() {
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
        )
        val first = NamedMigrationArtifact("20261101090000_init.sql", MigrationArtifact(metadata, "SELECT 1;"))
        val second = NamedMigrationArtifact("20261101100000_users.sql", MigrationArtifact(metadata, "SELECT 2;"))

        assertEquals(listOf(first, second), MigrationChain.discover(listOf(second, first)).artifacts)
        assertEquals("20261101090000_init", first.id)
    }

    @Test
    fun canonical_hash_ignores_layout_and_keyword_case() {
        assertEquals(
            canonicalizeSql("SELECT * FROM users;", MigrationDialect.PostgreSql),
            canonicalizeSql("select\n  *\nfrom users;", MigrationDialect.PostgreSql),
        )
        assertFailsWith<IllegalArgumentException> {
            MigrationId.fromFilename("users.sql")
        }
    }

    @Test
    fun metadata_uses_the_published_type_discriminator_for_schema_delta_operations() {
        val table = CanonicalTableReference(name = "users")
        val artifact = MigrationArtifact(
            MigrationArtifactMetadata(
                dialect = "postgresql",
                schemaDelta = SchemaDelta(
                    listOf(AddColumn(table, CanonicalColumn("email", CanonicalColumnType("kagiroi.string"), true))),
                ),
            ),
            "ALTER TABLE users ADD COLUMN email TEXT;",
        )

        assertContains(writeMigrationArtifact(artifact.metadata, artifact.body), "type: addColumn")
    }
}
