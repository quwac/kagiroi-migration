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
        assertContains(migration.readText(), "\"formatVersion\": 1")
        assertContains(migration.readText(), "\"steps\": []")
        assertContains(migration.readText(), "CREATE TABLE \"users\"")

        val finalized = runner("kagiroiFinalizeMigration").build()
        assertEquals(TaskOutcome.SUCCESS, finalized.task(":app:kagiroiFinalizeMigration")?.outcome)
        assertContains(migration.readText(), "\"sha256\":")
        assertContains(migration.readText(), "\"schemaDelta\":")

        val verified = runner("kagiroiVerifyMigration").build()
        assertEquals(TaskOutcome.SUCCESS, verified.task(":app:kagiroiVerifyMigration")?.outcome)

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

                sourceSets {
                    jsMain.dependencies {
                        implementation("dev.quwac.kagiroi:kagiroi-schema:${System.getProperty("kagiroi.test.version")}")
                    }
                }
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

            import dev.quwac.kagiroi.schema.Table
            import dev.quwac.kagiroi.schema.nullable
            import dev.quwac.kagiroi.schema.schema

            object Users : Table("users") {
                val name = string("name").nullable()
            }

            val AppSchema = schema(Users)
            """.trimIndent() + "\n",
        )
    }
}
