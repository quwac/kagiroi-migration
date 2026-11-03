@file:Suppress("BlankLineBetweenWhenConditions")

package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.MigrationId
import dev.quwac.kagiroi.migration.NamedMigrationArtifact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MigrationCliConfiguration(
    val migrationsDirectory: String = "src/main/resources/kagiroi/migrations",
    val targets: Map<String, MigrationTarget>,
) {
    init {
        require(targets.isNotEmpty()) { "Migration CLI configuration must define at least one target" }
    }

    companion object {
        val json: Json = Json { ignoreUnknownKeys = false }
    }
}

/** The finalized local artifact source. A Node/Bun host supplies filesystem access outside migration-core. */
interface MigrationArtifactSource {
    suspend fun readArtifacts(): List<NamedMigrationArtifact>
}

/** A provider adapter resolves credentials, proxy settings, and a database-level lock for one target. */
interface MigrationTargetConnector {
    val provider: String
    val supportedDialects: Set<MigrationDialect>

    suspend fun connect(target: MigrationTarget): MigrationDatabase
}

sealed interface MigrationCommand {
    val target: String

    data class Apply(override val target: String, val dryRun: Boolean) : MigrationCommand

    data class ResolveApplied(
        override val target: String,
        val migrationId: String,
        val stepIndex: Int,
        val sha256: String,
    ) : MigrationCommand

    data class ResolvePending(override val target: String, val migrationId: String, val stepIndex: Int) :
        MigrationCommand

    data class ResolveHash(
        override val target: String,
        val migrationId: String,
        val stepIndex: Int,
        val sha256: String,
    ) : MigrationCommand

    data class ResolveClearLock(override val target: String) : MigrationCommand
}

/** Executes a parsed command after a host has supplied its local artifacts and provider connector. */
class MigrationCli(
    private val artifacts: MigrationArtifactSource,
    private val targets: Map<String, MigrationTarget>,
    connectors: Iterable<MigrationTargetConnector>,
) {
    private val connectorsByProvider = connectors.associateBy(MigrationTargetConnector::provider)

    suspend fun run(command: MigrationCommand): MigrationPreflight? {
        val target = requireNotNull(targets[command.target]) { "Unknown migration target: ${command.target}" }
        val connector = requireNotNull(connectorsByProvider[target.provider]) {
            "No migration connector is installed for provider ${target.provider}"
        }
        val database = connector.connect(target)
        try {
            val executor = MigrationExecutor(database)
            return when (command) {
                is MigrationCommand.Apply -> {
                    val migrationArtifacts = artifacts.readArtifacts()
                    require(
                        migrationArtifacts.all { artifact ->
                            MigrationDialect.parse(artifact.artifact.metadata.dialect) in connector.supportedDialects
                        },
                    ) { "Migration artifact dialect is not supported by target ${command.target}" }
                    if (command.dryRun) executor.dryRun(migrationArtifacts) else executor.apply(migrationArtifacts)
                }
                is MigrationCommand.ResolveApplied -> {
                    executor.resolveApplied(command.migrationId, command.stepIndex, command.sha256)
                    null
                }
                is MigrationCommand.ResolvePending -> {
                    executor.resolvePending(command.migrationId, command.stepIndex)
                    null
                }
                is MigrationCommand.ResolveHash -> {
                    executor.acceptHash(command.migrationId, command.stepIndex, command.sha256)
                    null
                }
                is MigrationCommand.ResolveClearLock -> {
                    executor.clearMigrationLock()
                    null
                }
            }
        } finally {
            database.close()
        }
    }
}

/** Parses only the supported apply and explicit history-resolution commands. */
fun parseMigrationCommand(arguments: List<String>): MigrationCommand {
    require(arguments.firstOrNull() == "migration") { "Expected: kagiroi migration <apply|resolve>" }
    return when (arguments.getOrNull(1)) {
        "apply" -> parseApply(arguments.drop(2))
        "resolve" -> parseResolve(arguments.drop(2))
        else -> error("Expected: kagiroi migration <apply|resolve>")
    }
}

private fun parseApply(arguments: List<String>): MigrationCommand.Apply {
    val options = options(arguments, setOf("--dry-run", "--target"))
    return MigrationCommand.Apply(options["--target"] ?: "default", options.containsKey("--dry-run"))
}

private fun parseResolve(arguments: List<String>): MigrationCommand {
    val options = options(
        arguments,
        setOf(
            "--target",
            "--migration",
            "--step",
            "--mark-applied",
            "--mark-pending",
            "--accept-sha256",
            "--clear-lock",
        ),
    )
    val target = options["--target"] ?: "default"
    if (options.containsKey("--clear-lock")) {
        require(options.keys.all { it == "--target" || it == "--clear-lock" }) {
            "--clear-lock cannot be combined with a migration-step resolution"
        }
        return MigrationCommand.ResolveClearLock(target)
    }
    val migrationId = requireNotNull(options["--migration"]) { "--migration is required" }.also(::requireMigrationId)
    val step = requireNotNull(options["--step"]) { "--step is required" }.toIntOrNull()
        ?: error("--step must be a non-negative integer")
    require(step >= 0) { "--step must be a non-negative integer" }
    val actions = listOf("--mark-applied", "--mark-pending", "--accept-sha256").filter(options::containsKey)
    require(actions.size == 1) { "resolve requires exactly one of --mark-applied, --mark-pending, or --accept-sha256" }
    return when (val action = actions.single()) {
        "--mark-applied" -> MigrationCommand.ResolveApplied(
            target,
            migrationId,
            step,
            requireSha256(options.getValue(action)),
        )
        "--mark-pending" -> MigrationCommand.ResolvePending(target, migrationId, step)
        else -> MigrationCommand.ResolveHash(target, migrationId, step, requireSha256(options.getValue(action)))
    }
}

private fun options(arguments: List<String>, supported: Set<String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < arguments.size) {
        val option = arguments[index]
        require(option in supported) { "Unsupported migration option: $option" }
        require(option !in result) { "Migration option appears more than once: $option" }
        if (option == "--dry-run" || option == "--mark-pending" || option == "--clear-lock") {
            result[option] = ""
            index++
        } else {
            result[option] = arguments.getOrNull(index + 1)
                ?: error("Migration option $option requires a value")
            index += 2
        }
    }
    return result
}

private fun requireMigrationId(value: String): String {
    MigrationId.fromFilename("$value.sql")
    return value
}

private fun requireSha256(value: String): String {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "Migration SHA-256 must be lowercase hexadecimal" }
    return value
}
