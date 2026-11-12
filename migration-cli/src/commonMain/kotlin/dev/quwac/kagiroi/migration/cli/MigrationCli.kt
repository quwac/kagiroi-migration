@file:Suppress("BlankLineBetweenWhenConditions")

package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.MigrationRepairResult
import dev.quwac.kagiroi.migration.NamedMigrationArtifact

/** The local SQL artifact source. A Node/Bun host supplies filesystem access outside migration-core. */
interface MigrationArtifactSource {
    suspend fun readArtifacts(defaultDialect: MigrationDialect? = null): List<NamedMigrationArtifact>
}

/** A provider adapter resolves credentials and a database-level lock for one connection. */
interface MigrationTargetConnector {
    val provider: String
    val supportedDialects: Set<MigrationDialect>

    suspend fun connect(target: MigrationTarget): MigrationDatabase
}

sealed interface MigrationCommand {
    val target: MigrationTarget
    val directory: String

    data class Apply(
        override val target: MigrationTarget,
        override val directory: String = "src/main/resources/kagiroi/migrations",
        val dryRun: Boolean = false,
    ) : MigrationCommand

    data class Repair(
        override val target: MigrationTarget,
        override val directory: String = "src/main/resources/kagiroi/migrations",
        val dryRun: Boolean = false,
        val clearLock: Boolean = false,
    ) : MigrationCommand
}

sealed interface MigrationCommandResult {
    data class Applied(val preflight: MigrationPreflight) : MigrationCommandResult
    data class Repaired(val result: MigrationRepairResult, val dryRun: Boolean) : MigrationCommandResult
    data object LockCleared : MigrationCommandResult
}

/** Executes a parsed command after a host has supplied its local artifacts and provider connector. */
class MigrationCli(private val artifacts: MigrationArtifactSource, connectors: Iterable<MigrationTargetConnector>) {
    private val connectorsByProvider = connectors.associateBy(MigrationTargetConnector::provider)

    suspend fun run(command: MigrationCommand): MigrationCommandResult {
        val target = command.target
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
                    ) { "Migration artifact dialect is not supported by provider ${target.provider}" }
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
                        ) { "Migration artifact dialect is not supported by provider ${target.provider}" }
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
    val action = arguments.getOrNull(1)
    require(action == "apply" || action == "repair") { "Expected: kagiroi migration <apply|repair>" }
    val supported = setOf("--provider", "--dir", "--account-id", "--database-id", "--dry-run") +
        if (action == "repair") setOf("--clear-lock") else emptySet()
    val options = options(arguments.drop(2), supported)
    val provider = requireNotNull(options["--provider"]) { "--provider is required" }
    val accountId = options["--account-id"]
    val databaseId = options["--database-id"]
    if (provider == "d1") {
        require(accountId != null && databaseId != null) { "D1 requires --account-id and --database-id" }
    } else {
        require(accountId == null && databaseId == null) { "--account-id and --database-id require --provider d1" }
    }
    val target = MigrationTarget(provider, accountId, databaseId)
    val directory = options["--dir"] ?: "src/main/resources/kagiroi/migrations"
    val dryRun = options.containsKey("--dry-run")
    val clearLock = options.containsKey("--clear-lock")
    require(!clearLock || !dryRun) { "--clear-lock cannot be combined with --dry-run" }
    return when (action) {
        "apply" -> MigrationCommand.Apply(target, directory, dryRun)
        else -> MigrationCommand.Repair(target, directory, dryRun, clearLock)
    }
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
            val value = arguments.getOrNull(index + 1)
            require(!value.isNullOrBlank() && !value.startsWith("--")) {
                "Migration option $option requires a value"
            }
            result[option] = value
            index += 2
        }
    }
    return result
}
