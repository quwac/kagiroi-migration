@file:Suppress("LongMethod", "UnstableApiUsage")

package dev.quwac.kagiroi.gradle

import dev.quwac.kagiroi.migration.MigrationArtifactMetadata
import dev.quwac.kagiroi.migration.MigrationChain
import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.MigrationId
import dev.quwac.kagiroi.migration.NamedMigrationArtifact
import dev.quwac.kagiroi.migration.canonicalizeSchemaProjection
import dev.quwac.kagiroi.migration.decodeCanonicalSchema
import dev.quwac.kagiroi.migration.diffSchema
import dev.quwac.kagiroi.migration.emptyCanonicalSchema
import dev.quwac.kagiroi.migration.encodeCanonicalSchema
import dev.quwac.kagiroi.migration.finalizeMigrationArtifact
import dev.quwac.kagiroi.migration.generateMigrationScript
import dev.quwac.kagiroi.migration.parseMigrationArtifact
import dev.quwac.kagiroi.migration.replaySchemaDeltas
import dev.quwac.kagiroi.migration.verifyMigrationArtifacts
import dev.quwac.kagiroi.migration.writeMigrationArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Project-scoped configuration for Kagiroi SQL migration generation. */
abstract class KagiroiMigrationExtension @Inject constructor(objects: ObjectFactory) {
    /** Fully-qualified Kotlin object/property expression whose value is a Kagiroi SchemaRegistry. */
    val schema: Property<String> = objects.property(String::class.java)

    /** Stable SQL dialect ID written into each migration artifact. */
    val dialect: Property<String> = objects.property(String::class.java).convention("postgresql")

    /** SQL migration artifact directory. */
    val migrationsDirectory: DirectoryProperty = objects.directoryProperty()
}

/** Adds schema export and raw SQL migration tasks to one Kotlin Multiplatform project. */
class KagiroiMigrationPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("kagiroi", KagiroiMigrationExtension::class.java)
        extension.migrationsDirectory.convention(
            project.layout.projectDirectory.dir("src/main/resources/kagiroi/migrations"),
        )
        val source = project.tasks.register(
            "kagiroiGenerateSchemaExporter",
            GenerateSchemaExporterSourceTask::class.java,
        )
        source.configure { task ->
            task.schema.set(extension.schema)
            task.outputFile.set(
                project.layout.buildDirectory.file(
                    "generated/kagiroiMigration/jsMain/kotlin/dev/quwac/kagiroi/generated/KagiroiSchemaExporter.kt",
                ),
            )
        }
        val exportedSchema = project.tasks.register("kagiroiExportSchema", ExportKagiroiSchemaTask::class.java)
        exportedSchema.configure { task ->
            task.schemaExporterSource.set(source.flatMap(GenerateSchemaExporterSourceTask::outputFile))
            task.outputFile.set(project.layout.buildDirectory.file("kagiroi/migration/schema.json"))
            task.entryModuleDirectory.set(
                project.layout.buildDirectory.dir("compileSync/js/main/developmentExecutable/kotlin"),
            )
            task.entryModuleBaseName.set(project.rootProject.name + project.path.replace(':', '-'))
            task.dependsOn(source)
        }
        val generateMigration = project.tasks.register(
            "kagiroiGenerateMigration",
            GenerateKagiroiMigrationTask::class.java,
        )
        generateMigration.configure { task ->
            task.schemaJson.set(exportedSchema.flatMap(ExportKagiroiSchemaTask::outputFile))
            task.migrationName.set(project.providers.gradleProperty("kagiroiMigrationName"))
            task.dialect.set(extension.dialect)
            task.migrationsDirectory.set(extension.migrationsDirectory)
            task.dependsOn(exportedSchema)
        }
        val finalizeMigration = project.tasks.register(
            "kagiroiFinalizeMigration",
            FinalizeKagiroiMigrationTask::class.java,
        )
        finalizeMigration.configure { task ->
            task.schemaJson.set(exportedSchema.flatMap(ExportKagiroiSchemaTask::outputFile))
            task.migrationsDirectory.set(extension.migrationsDirectory)
            task.dependsOn(exportedSchema)
        }
        val verifyMigration = project.tasks.register(
            "kagiroiVerifyMigration",
            VerifyKagiroiMigrationTask::class.java,
        )
        verifyMigration.configure { task ->
            task.schemaJson.set(exportedSchema.flatMap(ExportKagiroiSchemaTask::outputFile))
            task.dialect.set(extension.dialect)
            task.migrationsDirectory.set(extension.migrationsDirectory)
            task.dependsOn(exportedSchema)
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            configureKotlinJsExport(project, source, exportedSchema)
        }
    }

    private fun configureKotlinJsExport(
        project: Project,
        source: org.gradle.api.tasks.TaskProvider<GenerateSchemaExporterSourceTask>,
        exportedSchema: org.gradle.api.tasks.TaskProvider<ExportKagiroiSchemaTask>,
    ) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        kotlin.targets.withType(KotlinJsIrTarget::class.java).configureEach { target ->
            target.binaries.executable()
            target.compilations.named("main").configure { compilation ->
                compilation.defaultSourceSet.kotlin.srcDir(
                    source.flatMap(GenerateSchemaExporterSourceTask::outputFile).map { output ->
                        output.asFile.parentFile.parentFile.parentFile.parentFile.parentFile
                    },
                )
            }
            project.tasks.named("compileDevelopmentExecutableKotlinJs").configure { task ->
                task.dependsOn(source)
            }
        }
        exportedSchema.configure { task -> task.dependsOn("compileDevelopmentExecutableKotlinJs") }
    }
}

/** Generates the only exporter entrypoint. Application code does not define `main()`. */
@DisableCachingByDefault(because = "The generated source is cheap and driven by configured schema text.")
abstract class GenerateSchemaExporterSourceTask : DefaultTask() {
    @get:Input
    abstract val schema: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val configuredSchema = schema.orNull?.takeIf(String::isNotBlank)
            ?: throw GradleException("kagiroi.schema must name an AppSchema value")
        val file = outputFile.get().asFile.toPath()
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            """
            package dev.quwac.kagiroi.generated

            fun main() {
                println($configuredSchema.project().encode())
            }
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
    }
}

/** Runs the generated Kotlin/JS exporter and writes its JSON-only stdout to build/. */
@DisableCachingByDefault(
    because = "Node execution is an external process and schema code can have initialization effects.",
)
abstract class ExportKagiroiSchemaTask : DefaultTask() {
    @get:InputFile
    abstract val schemaExporterSource: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val entryModuleDirectory: DirectoryProperty

    @get:Input
    abstract val entryModuleBaseName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun export() {
        check(schemaExporterSource.get().asFile.isFile) { "Generated Kagiroi schema exporter is missing" }
        val directory = entryModuleDirectory.get().asFile.toPath()
        val baseName = entryModuleBaseName.get()
        val module = sequenceOf(".mjs", ".js")
            .map { suffix -> directory.resolve(baseName + suffix) }
            .singleOrNull(Files::isRegularFile)
            ?: throw GradleException(
                "Kotlin/JS schema exporter did not produce $baseName.mjs or $baseName.js in $directory",
            )
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        val projection = java.io.ByteArrayOutputStream()
        execOperations.exec { spec ->
            spec.commandLine("node", module.toAbsolutePath().toString())
            spec.standardOutput = projection
            spec.errorOutput = System.err
        }
        try {
            output.writeText(
                encodeCanonicalSchema(canonicalizeSchemaProjection(projection.toString(StandardCharsets.UTF_8), 1)),
                StandardCharsets.UTF_8,
            )
        } catch (exception: IllegalArgumentException) {
            throw GradleException("Generated Kagiroi exporter did not write Schema Projection JSON only", exception)
        }
    }
}

/** Generates one draft artifact atomically after loading and validating the existing chain. */
@DisableCachingByDefault(because = "Migration IDs contain the current timestamp and generation writes a new artifact.")
abstract class GenerateKagiroiMigrationTask : DefaultTask() {
    @get:InputFile
    abstract val schemaJson: RegularFileProperty

    @get:Input
    abstract val migrationName: Property<String>

    @get:Input
    abstract val dialect: Property<String>

    @get:Internal
    abstract val migrationsDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = migrationsDirectory.get().asFile.toPath()
        Files.createDirectories(directory)
        val existing = readArtifacts(directory)
        val chain = MigrationChain.discover(existing)
        require(
            chain.drafts.isEmpty(),
        ) { "Finalize or remove draft migration ${chain.drafts.single().filename} before generating another" }
        val desired = decodeCanonicalSchema(schemaJson.get().asFile.readText(StandardCharsets.UTF_8))
        val previous = replaySchemaDeltas(
            chain.finalized.flatMap { named ->
                named.artifact.metadata.steps.map { it.schemaDelta }
            },
        ).takeIf { chain.finalized.isNotEmpty() } ?: emptyCanonicalSchema()
        val plan = diffSchema(previous, desired)
        if (plan.operations.isEmpty()) {
            logger.lifecycle("No schema changes.")
            return
        }
        val name = MigrationId.requireValidName(
            migrationName.orNull ?: throw GradleException("Use -PkagiroiMigrationName=<lower_snake_case_name>"),
        )
        val migrationDialect = MigrationDialect.parse(dialect.get())
        val id = ID_TIMESTAMP.format(Clock.systemUTC().instant()) + "_$name"
        val target = directory.resolve("$id.sql")
        require(!Files.exists(target)) { "Migration artifact already exists: $target" }
        val body =
            "-- Generated by Kagiroi. Edit SQL and transaction boundaries, then run kagiroiFinalizeMigration.\n\n" +
                generateMigrationScript(plan, migrationDialect).body
        val metadata = MigrationArtifactMetadata(
            dialect = migrationDialect.id,
        )
        atomicWrite(target, writeMigrationArtifact(metadata, body))
        logger.lifecycle("Generated draft Kagiroi migration ${target.fileName}")
    }
}

/** Finalizes the single draft without parsing or otherwise interpreting its raw SQL body. */
@DisableCachingByDefault(because = "Finalization rewrites one user-editable SQL artifact.")
abstract class FinalizeKagiroiMigrationTask : DefaultTask() {
    @get:InputFile
    abstract val schemaJson: RegularFileProperty

    @get:Internal
    abstract val migrationsDirectory: DirectoryProperty

    @TaskAction
    fun finalizeMigration() {
        val directory = migrationsDirectory.get().asFile.toPath()
        val chain = MigrationChain.discover(readArtifacts(directory))
        require(chain.drafts.size == 1) {
            "kagiroiFinalizeMigration requires exactly one draft migration; found ${chain.drafts.size}"
        }
        val named = chain.drafts.single()
        val desired = decodeCanonicalSchema(schemaJson.get().asFile.readText(StandardCharsets.UTF_8))
        val previous = replaySchemaDeltas(
            chain.finalized.flatMap { artifact ->
                artifact.artifact.metadata.steps.map { it.schemaDelta }
            },
        ).takeIf { chain.finalized.isNotEmpty() } ?: emptyCanonicalSchema()
        val finalized = finalizeMigrationArtifact(named.artifact, diffSchema(previous, desired))
        atomicWrite(directory.resolve(named.filename), writeMigrationArtifact(finalized.metadata, finalized.body))
        logger.lifecycle("Finalized Kagiroi migration ${named.filename}")
    }
}

/** Replays filename-ordered step deltas and compares them with the current Kotlin schema. */
@DisableCachingByDefault(because = "Verification reads user-owned SQL migration artifacts.")
abstract class VerifyKagiroiMigrationTask : DefaultTask() {
    @get:InputFile
    abstract val schemaJson: RegularFileProperty

    @get:Input
    abstract val dialect: Property<String>

    @get:Internal
    abstract val migrationsDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val desired = decodeCanonicalSchema(schemaJson.get().asFile.readText(StandardCharsets.UTF_8))
        val directory = migrationsDirectory.get().asFile.toPath()
        val result = verifyMigrationArtifacts(
            readArtifacts(directory),
            desired,
            MigrationDialect.parse(dialect.get()),
        )
        logger.lifecycle(
            "Verified ${result.migrationCount} Kagiroi migrations and ${result.stepCount} execution steps.",
        )
    }
}

private val ID_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern(
    "yyyyMMddHHmmss",
).withZone(java.time.ZoneOffset.UTC)

private fun readArtifacts(directory: java.nio.file.Path): List<NamedMigrationArtifact> = if (!Files.exists(directory)) {
    emptyList()
} else {
    Files.list(directory).use { files ->
        files.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".sql") }
            .map { path ->
                val filename = path.fileName.toString()
                NamedMigrationArtifact(
                    filename,
                    parseMigrationArtifact(Files.readString(path, StandardCharsets.UTF_8)),
                )
            }
            .toList()
    }
}

private fun atomicWrite(target: java.nio.file.Path, text: String) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
    try {
        Files.writeString(temporary, text, StandardCharsets.UTF_8)
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
