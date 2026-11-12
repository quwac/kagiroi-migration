package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.migration.MigrationArtifact
import dev.quwac.kagiroi.migration.MigrationArtifactMetadata
import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.writeMigrationArtifact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@JsModule("node:fs")
@JsNonModule
@Suppress("UnusedParameter")
private external object ArtifactTestFileSystem {
    fun mkdtempSync(prefix: String): String
    fun writeFileSync(path: String, contents: String)
    fun readFileSync(path: String, encoding: String): String
    fun unlinkSync(path: String)
    fun rmdirSync(path: String)
}

class NodeMigrationArtifactSourceTest {
    @Test
    fun readsHandwrittenSqlUsingTheTargetDialectWithoutChangingTheFile() = runSuspend {
        val directory = ArtifactTestFileSystem.mkdtempSync("/tmp/kagiroi-raw-sql-")
        val file = "$directory/20261101090000_seed.sql"
        val body = "INSERT INTO users (name) VALUES ('demo');\n"
        ArtifactTestFileSystem.writeFileSync(file, body)
        try {
            val source = NodeMigrationArtifactSource(directory)
            assertFailsWith<IllegalArgumentException> { source.readArtifacts() }
            MigrationDialect.entries.forEach { dialect ->
                val artifact = source.readArtifacts(dialect).single().artifact
                assertEquals(body, artifact.body)
                assertEquals(dialect.id, artifact.metadata.dialect)
                assertTrue(artifact.metadata.schemaDelta.operations.isEmpty())
            }
            assertEquals(body, ArtifactTestFileSystem.readFileSync(file, "utf8"))
        } finally {
            ArtifactTestFileSystem.unlinkSync(file)
            ArtifactTestFileSystem.rmdirSync(directory)
        }
    }

    @Test
    fun readsGeneratedSqlAndDerivesStepsAfterEditingItsBody() = runSuspend {
        val directory = ArtifactTestFileSystem.mkdtempSync("/tmp/kagiroi-sql-")
        val file = "$directory/20261101090000_initial.sql"
        val artifact = MigrationArtifact(MigrationArtifactMetadata(dialect = "postgresql"), "SELECT 1;")
        ArtifactTestFileSystem.writeFileSync(file, writeMigrationArtifact(artifact.metadata, artifact.body))
        try {
            assertEquals(1, NodeMigrationArtifactSource(directory).readArtifacts().single().artifact.steps.size)
            val edited = "BEGIN; SELECT 1; COMMIT;\nSELECT 2;"
            ArtifactTestFileSystem.writeFileSync(file, writeMigrationArtifact(artifact.metadata, edited))
            val result = NodeMigrationArtifactSource(directory).readArtifacts().single().artifact
            assertEquals(edited, result.body)
            assertEquals(2, result.steps.size)
        } finally {
            ArtifactTestFileSystem.unlinkSync(file)
            ArtifactTestFileSystem.rmdirSync(directory)
        }
    }
}
