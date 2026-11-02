package dev.quwac.kagiroi.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals

class KagiroiMigrationPluginFunctionalTest {
    @TempDir
    lateinit var fixture: Path

    @Test
    fun generatesAndFinalizesAnExecutableSqlArtifactWithoutApplicationMain() {
        writeFixture()

        val generated = runner("kagiroiGenerateMigration", "-PkagiroiMigrationName=initial").build()
        assertEquals(TaskOutcome.SUCCESS, generated.task(":app:kagiroiGenerateMigration")?.outcome)
        val migration = Files.list(fixture.resolve("app/src/main/resources/kagiroi/migrations")).use { files ->
            files.findFirst().orElseThrow()
        }
        assertContains(migration.readText(), "\"status\": \"draft\"")
        assertContains(migration.readText(), "CREATE TABLE \"users\"")

        val finalized = runner("kagiroiFinalizeMigration").build()
        assertEquals(TaskOutcome.SUCCESS, finalized.task(":app:kagiroiFinalizeMigration")?.outcome)
        assertContains(migration.readText(), "\"status\": \"final\"")
        assertContains(migration.readText(), "\"sqlSha256\":")
        assertContains(migration.readText(), "\"resultSchema\":")

        val noChanges = runner("kagiroiGenerateMigration", "-PkagiroiMigrationName=unused").build()
        assertContains(noChanges.output, "No schema changes.")
        assertEquals(
            1,
            Files.list(fixture.resolve("app/src/main/resources/kagiroi/migrations")).use { files -> files.count() },
        )
    }

    private fun runner(task: String, vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(fixture.toFile())
        .withArguments(":app:$task", *arguments, "--stacktrace", "--console=plain")
        .withPluginClasspath()

    private fun writeFixture() {
        Files.writeString(
            fixture.resolve("settings.gradle.kts"),
            """
            dependencyResolutionManagement {
                repositories { mavenCentral() }
            }
            rootProject.name = "kagiroi-plugin-fixture"
            include(":app")
            """.trimIndent() + "\n",
        )
        val app = fixture.resolve("app")
        app.createDirectories()
        Files.writeString(
            app.resolve("build.gradle.kts"),
            """
            plugins {
                kotlin("multiplatform") version "2.4.21"
                id("dev.quwac.kagiroi.migration")
            }

            kotlin {
                js { nodejs() }
            }

            kagiroi {
                schema.set("example.AppSchema")
                dialect.set("postgresql")
            }
            """.trimIndent() + "\n",
        )
        val source = app.resolve("src/jsMain/kotlin/example")
        source.createDirectories()
        Files.writeString(
            source.resolve("Schema.kt"),
            """
            package example

            object AppSchema
            """.trimIndent() + "\n",
        )
        val exporter = app.resolve("src/jsMain/kotlin/dev/quwac/kagiroi/migration")
        exporter.createDirectories()
        Files.writeString(
            exporter.resolve("NodeSchemaExporter.kt"),
            """
            package dev.quwac.kagiroi.migration

            object NodeSchemaExporter {
                fun write(schema: Any) {
                    println("{\"formatVersion\":1,\"schema\":{\"tables\":[{\"name\":\"users\",\"columns\":[{\"name\":\"name\",\"type\":{\"id\":\"kagiroi.string\",\"arguments\":[]},\"nullable\":true,\"primaryKey\":false,\"unique\":false}],\"uniqueConstraints\":[],\"foreignKeys\":[],\"checks\":[]}],\"sequences\":[],\"indexes\":[]},\"evolution\":{\"tableRenames\":[],\"columnRenames\":[],\"indexRenames\":[],\"constraintRenames\":[],\"sequenceRenames\":[]},\"metadata\":{\"physicalSchemaHash\":{\"algorithm\":\"sha256\",\"value\":\"4e3a63875ef54d8d836696631b92c3a42029176842b27a2cf6ab567e5de6fde2\"}}}")
                }
            }
            """.trimIndent() + "\n",
        )
    }
}
