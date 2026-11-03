package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationArtifactV1Test {
    @Test
    fun metadata_round_trip_preserves_sql_body() {
        val artifact = MigrationArtifact(MigrationArtifactMetadata(dialect = "postgresql"), "SELECT '*/';\n")

        assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
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
    fun filename_is_identity_and_order() {
        val metadata = MigrationArtifactMetadata(
            dialect = "postgresql",
            steps = listOf(MigrationArtifactStep(0, MigrationStepKind.NonTransactional, "0".repeat(64), SchemaDelta())),
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
                steps = listOf(
                    MigrationArtifactStep(
                        0,
                        MigrationStepKind.NonTransactional,
                        "0".repeat(64),
                        SchemaDelta(
                            listOf(
                                AddColumn(table, CanonicalColumn("email", CanonicalColumnType("kagiroi.string"), true)),
                            ),
                        ),
                    ),
                ),
            ),
            "ALTER TABLE users ADD COLUMN email TEXT;",
        )

        assertContains(writeMigrationArtifact(artifact.metadata, artifact.body), "\"type\": \"addColumn\"")
    }
}
