@file:Suppress("BlankLineBetweenWhenConditions")

package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.MigrationRepairResult
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

/** The local SQL artifact source. A Node/Bun host supplies filesystem access outside migration-core. */
interface MigrationArtifactSource {
    suspend fun readArtifacts(defaultDialect: MigrationDialect? = null): List<NamedMigrationArtifact>
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

    data class Repair(override val target: String, val dryRun: Boolean = false, val clearLock: Boolean = false) :
        MigrationCommand
}

sealed interface MigrationCommandResult {
    data class Applied(val preflight: MigrationPreflight) : MigrationCommandResult
    data class Repaired(val result: MigrationRepairResult, val dryRun: Boolean) : MigrationCommandResult
    data object LockCleared : MigrationCommandResult
}

/** Executes a parsed command after a host has supplied its local artifacts and provider connector. */
class MigrationCli(
    private val artifacts: MigrationArtifactSource,
    private val targets: Map<String, MigrationTarget>,
    connectors: Iterable<MigrationTargetConnector>,
) {
    private val connectorsByProvider = connectors.associateBy(MigrationTargetConnector::provider)

    suspend fun run(command: MigrationCommand): MigrationCommandResult {
        val target = requireNotNull(targets[command.target]) { "Unknown migration target: ${command.target}" }
        val connector = requireNotNull(connectorsByProvider[target.provider]) {
            "No migration connector is installed for provider ${target.provider}"
        }
        val database = connector.connect(target)
        try {
            val executor = MigrationExecutor(database)
            return when (command) {
                is MigrationCommand.Apply -> {
                    val migrationArtifacts = artifacts.readArtifacts(connector.supportedDialects.singleOrNull())
                    require(
                        migrationArtifacts.all { artifact ->
                            MigrationDialect.parse(artifact.artifact.metadata.dialect) in connector.supportedDialects
                        },
                    ) { "Migration artifact dialect is not supported by target ${command.target}" }
                    MigrationCommandResult.Applied(
                        if (command.dryRun) executor.dryRun(migrationArtifacts) else executor.apply(migrationArtifacts),
                    )
                }
                is MigrationCommand.Repair -> {
                    if (command.clearLock) {
                        executor.clearMigrationLock()
                        MigrationCommandResult.LockCleared
                    } else {
                        val migrationArtifacts = artifacts.readArtifacts(connector.supportedDialects.singleOrNull())
                        require(
                            migrationArtifacts.all { artifact ->
                                MigrationDialect.parse(artifact.artifact.metadata.dialect) in
                                    connector.supportedDialects
                            },
                        ) { "Migration artifact dialect is not supported by target ${command.target}" }
                        MigrationCommandResult.Repaired(
                            executor.repair(migrationArtifacts, command.dryRun),
                            command.dryRun,
                        )
                    }
                }
            }
        } finally {
            database.close()
        }
    }
}

/** Parses the apply and history repair commands. */
fun parseMigrationCommand(arguments: List<String>): MigrationCommand {
    require(arguments.firstOrNull() == "migration") { "Expected: kagiroi migration <apply|repair>" }
    return when (arguments.getOrNull(1)) {
        "apply" -> parseApply(arguments.drop(2))
        "repair" -> parseRepair(arguments.drop(2))
        else -> error("Expected: kagiroi migration <apply|repair>")
    }
}

private fun parseApply(arguments: List<String>): MigrationCommand.Apply {
    val options = options(arguments, setOf("--dry-run", "--target"))
    return MigrationCommand.Apply(options["--target"] ?: "default", options.containsKey("--dry-run"))
}

private fun parseRepair(arguments: List<String>): MigrationCommand.Repair {
    val options = options(arguments, setOf("--target", "--dry-run", "--clear-lock"))
    val clearLock = options.containsKey("--clear-lock")
    require(!clearLock || options.keys.all { it == "--target" || it == "--clear-lock" }) {
        "--clear-lock cannot be combined with other repair actions"
    }
    return MigrationCommand.Repair(
        options["--target"] ?: "default",
        options.containsKey("--dry-run"),
        clearLock,
    )
}

private fun options(arguments: List<String>, supported: Set<String>): Map<String, String> {
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < arguments.size) {
        val option = arguments[index]
        require(option in supported) { "Unsupported migration option: $option" }
        require(option !in result) { "Migration option appears more than once: $option" }
        if (option == "--dry-run" || option == "--clear-lock") {
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
