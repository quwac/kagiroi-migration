@file:Suppress("LongMethod", "UnstableApiUsage")

package dev.quwac.kagiroi.gradle

import dev.quwac.kagiroi.migration.MigrationArtifact
import dev.quwac.kagiroi.migration.MigrationArtifactMetadata
import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.MigrationId
import dev.quwac.kagiroi.migration.NamedMigrationArtifact
import dev.quwac.kagiroi.migration.canonicalizeSchemaProjection
import dev.quwac.kagiroi.migration.createMigrationArtifact
import dev.quwac.kagiroi.migration.decodeCanonicalSchema
import dev.quwac.kagiroi.migration.diffSchema
import dev.quwac.kagiroi.migration.encodeCanonicalSchema
import dev.quwac.kagiroi.migration.generateMigrationScript
import dev.quwac.kagiroi.migration.parseMigrationArtifact
import dev.quwac.kagiroi.migration.replaySchemaDeltas
import dev.quwac.kagiroi.migration.validateMigrationArtifacts
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
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val SCHEMA_EXPORTER_SOURCE_DIRECTORY = "generated/kagiroiMigration/jsMain/kotlin"

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
                project.layout.buildDirectory.dir(SCHEMA_EXPORTER_SOURCE_DIRECTORY)
                    .map { it.file("dev/quwac/kagiroi/generated/KagiroiSchemaExporter.kt") },
            )
        }
        val exportedSchema = project.tasks.register("kagiroiExportSchema", ExportKagiroiSchemaTask::class.java)
        exportedSchema.configure { task ->
            task.schemaExporterSource.set(source.flatMap(GenerateSchemaExporterSourceTask::outputFile))
            task.outputFile.set(project.layout.buildDirectory.file("kagiroi/migration/schema.json"))
            task.entryModuleDirectory.set(
                project.layout.buildDirectory.dir("compileSync/js/main/developmentExecutable/kotlin"),
            )
            task.entryModuleBaseName.set(project.rootProject.name + project.path.removeSuffix(":").replace(':', '-'))
            task.dependsOn(source)
        }
        val generateMigration = project.tasks.register(
            "kagiroiGenerateMigration",
            GenerateKagiroiMigrationTask::class.java,
        )
        generateMigration.configure { task ->
            task.schemaJson.set(exportedSchema.flatMap(ExportKagiroiSchemaTask::outputFile))
            task.migrationName.set(project.providers.gradleProperty("kagiroiMigrationName"))
            task.custom.set(
                project.providers.gradleProperty("kagiroiMigrationCustom").map(String::toBooleanStrict).orElse(false),
            )
            task.dialect.set(extension.dialect)
            task.migrationsDirectory.set(extension.migrationsDirectory)
            task.dependsOn(exportedSchema)
        }
        val regenerateMetadata = project.tasks.register(
            "kagiroiRegenerateMigrationMetadata",
            RegenerateKagiroiMigrationMetadataTask::class.java,
        )
        regenerateMetadata.configure { task ->
            task.schemaJson.set(exportedSchema.flatMap(ExportKagiroiSchemaTask::outputFile))
            task.dialect.set(extension.dialect)
            task.migrationFilename.set(project.providers.gradleProperty("kagiroiMigrationFile").orElse(""))
            task.migrationsDirectory.set(extension.migrationsDirectory)
            task.dependsOn(exportedSchema)
        }
        val verifyMigration = project.tasks.register(
            "kagiroiVerifyMigration",
            VerifyKagiroiMigrationTask::class.java,
        )
        verifyMigration.configure { task ->
            task.mustRunAfter(generateMigration, regenerateMetadata)
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
        val generatedSources = project.files(project.layout.buildDirectory.dir(SCHEMA_EXPORTER_SOURCE_DIRECTORY))
            .builtBy(source)
        kotlin.targets.withType(KotlinJsIrTarget::class.java).configureEach { target ->
            if (target.platformType != KotlinPlatformType.js) return@configureEach
            target.binaries.executable()
            target.compilations.named("main").configure { compilation ->
                compilation.defaultSourceSet.kotlin.srcDir(generatedSources)
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
                encodeCanonicalSchema(canonicalizeSchemaProjection(projection.toString(StandardCharsets.UTF_8))),
                StandardCharsets.UTF_8,
            )
        } catch (exception: IllegalArgumentException) {
            throw GradleException("Generated Kagiroi exporter did not write Schema Projection JSON only", exception)
        }
    }
}

/** Generates one directly executable SQL artifact with its file-level schema metadata. */
@DisableCachingByDefault(because = "Migration IDs contain the current timestamp and generation writes a new artifact.")
abstract class GenerateKagiroiMigrationTask : DefaultTask() {
    @get:InputFile
    abstract val schemaJson: RegularFileProperty

    @get:Input
    abstract val migrationName: Property<String>

    @get:Input
    abstract val dialect: Property<String>

    @get:Input
    abstract val custom: Property<Boolean>

    @get:Internal
    abstract val migrationsDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val directory = migrationsDirectory.get().asFile.toPath()
        Files.createDirectories(directory)
        val migrationDialect = MigrationDialect.parse(dialect.get())
        val chain = validateMigrationArtifacts(readArtifacts(directory, migrationDialect))
        require(chain.artifacts.all { it.artifact.metadata.dialect == migrationDialect.id }) {
            "The configured dialect does not match migration history"
        }
        val desired = decodeCanonicalSchema(schemaJson.get().asFile.readText(StandardCharsets.UTF_8))
        val previous = replaySchemaDeltas(chain.artifacts.map { it.artifact.metadata.schemaDelta })
        val plan = diffSchema(previous, desired)
        if (plan.operations.isEmpty() && !custom.get()) {
            logger.lifecycle("No schema changes.")
            return
        }
        require(
            !custom.get() || plan.operations.isEmpty(),
        ) { "Generate pending schema changes before a custom migration" }
        val name = MigrationId.requireValidName(
            migrationName.orNull ?: throw GradleException("Use -PkagiroiMigrationName=<lower_snake_case_name>"),
        )
        val id = ID_TIMESTAMP.format(Clock.systemUTC().instant()) + "_$name"
        val target = directory.resolve("$id.sql")
        require(chain.artifacts.all { it.filename < target.fileName.toString() }) {
            "The generated migration must follow existing filenames"
        }
        val artifact = if (custom.get()) {
            MigrationArtifact(
                MigrationArtifactMetadata(dialect = migrationDialect.id),
                "-- Write migration SQL here.\n",
            )
        } else {
            createMigrationArtifact(generateMigrationScript(plan, migrationDialect).body, migrationDialect, plan)
        }
        publishArtifact(
            target,
            if (custom.get()) artifact.body else writeMigrationArtifact(artifact.metadata, artifact.body),
        )
        logger.lifecycle("Generated Kagiroi migration ${target.fileName}")
    }
}

/** Explicitly regenerates only the latest file's metadata while preserving its edited SQL body. */
@DisableCachingByDefault(because = "Metadata recovery rewrites an explicitly selected SQL artifact.")
abstract class RegenerateKagiroiMigrationMetadataTask : DefaultTask() {
    @get:InputFile
    abstract val schemaJson: RegularFileProperty

    @get:Input
    abstract val dialect: Property<String>

    @get:Input
    abstract val migrationFilename: Property<String>

    @get:Internal
    abstract val migrationsDirectory: DirectoryProperty

    @TaskAction
    fun regenerateMetadata() {
        val directory = migrationsDirectory.get().asFile.toPath()
        val selected = migrationFilename.get().ifBlank {
            throw GradleException("Use -PkagiroiMigrationFile=<timestamp_name.sql>")
        }
        MigrationId.fromFilename(selected)
        val target = directory.resolve(selected)
        require(Files.isRegularFile(target)) { "Migration does not exist: $selected" }
        val migrationDialect = MigrationDialect.parse(dialect.get())
        val chain = validateMigrationArtifacts(readArtifacts(directory, migrationDialect, selected))
        require(
            chain.artifacts.all { it.filename < selected },
        ) { "Only the latest migration metadata can be regenerated" }
        require(chain.artifacts.all { it.artifact.metadata.dialect == migrationDialect.id }) {
            "The configured dialect does not match migration history"
        }
        val desired = decodeCanonicalSchema(schemaJson.get().asFile.readText(StandardCharsets.UTF_8))
        val previous = replaySchemaDeltas(chain.artifacts.map { it.artifact.metadata.schemaDelta })
        val original = Files.readString(target, StandardCharsets.UTF_8)
        val artifact = createMigrationArtifact(original, migrationDialect, diffSchema(previous, desired))
        replaceArtifact(target, writeMigrationArtifact(artifact.metadata, artifact.body))
        logger.lifecycle("Regenerated Kagiroi migration metadata $selected")
    }
}

/** Replays filename-ordered file-level deltas and compares them with the current Kotlin schema. */
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
            readArtifacts(directory, MigrationDialect.parse(dialect.get())),
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

private fun readArtifacts(
    directory: java.nio.file.Path,
    defaultDialect: MigrationDialect,
    excludedFilename: String? = null,
): List<NamedMigrationArtifact> = if (!Files.exists(directory)) {
    emptyList()
} else {
    Files.list(directory).use { files ->
        files.filter { path ->
            Files.isRegularFile(path) && path.fileName.toString().endsWith(".sql") &&
                path.fileName.toString() != excludedFilename
        }
            .map { path ->
                val filename = path.fileName.toString()
                try {
                    NamedMigrationArtifact(
                        filename,
                        parseMigrationArtifact(Files.readString(path, StandardCharsets.UTF_8), defaultDialect),
                    )
                } catch (exception: IllegalArgumentException) {
                    throw GradleException(
                        "Cannot read migration $filename; restore it from version control",
                        exception,
                    )
                }
            }
            .toList()
    }
}

private fun publishArtifact(target: java.nio.file.Path, text: String) {
    Files.createDirectories(target.parent)
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
    try {
        Files.writeString(temporary, text, StandardCharsets.UTF_8)
        Files.move(temporary, target)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun replaceArtifact(target: java.nio.file.Path, text: String) {
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
    try {
        Files.writeString(temporary, text, StandardCharsets.UTF_8)
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
