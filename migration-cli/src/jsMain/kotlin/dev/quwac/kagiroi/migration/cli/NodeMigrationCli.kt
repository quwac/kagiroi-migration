@file:Suppress("TooGenericExceptionCaught", "UnsafeCastFromDynamic", "UnusedParameter")

package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.d1.d1HttpDatabase
import dev.quwac.kagiroi.migration.NamedMigrationArtifact
import dev.quwac.kagiroi.migration.parseMigrationArtifact
import dev.quwac.kagiroi.mysql.node.NodeMySqlDatabase
import dev.quwac.kagiroi.oracle.node.NodeOracleDatabase
import dev.quwac.kagiroi.pglite.PGliteDatabase
import dev.quwac.kagiroi.pglite.PGliteStorage
import dev.quwac.kagiroi.postgres.node.NodePostgresDatabase
import dev.quwac.kagiroi.sqlite.file.FileSqliteDatabase
import dev.quwac.kagiroi.sqlserver.node.NodeSqlServerDatabase
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement

@JsModule("node:fs")
@JsNonModule
private external object NodeFileSystem {
    fun readFileSync(path: String, encoding: String): String
    fun readdirSync(path: String): Array<String>
}

@JsModule("node:path")
@JsNonModule
private external object NodePath {
    fun join(vararg segments: String): String
}

@JsModule("node:process")
@JsNonModule
private external object NodeProcess {
    val argv: Array<String>
    val env: dynamic
}

/** Node and Bun filesystem source for finalized SQL artifacts. */
class NodeMigrationArtifactSource(private val directory: String) : MigrationArtifactSource {
    override suspend fun readArtifacts(): List<NamedMigrationArtifact> = NodeFileSystem.readdirSync(directory)
        .filter { filename -> filename.endsWith(".sql") }
        .map { filename ->
            NamedMigrationArtifact(
                filename,
                parseMigrationArtifact(NodeFileSystem.readFileSync(NodePath.join(directory, filename), "utf8")),
            )
        }
}

/** PostgreSQL migration execution backed by Kagiroi's node-postgres driver. */
object NodePostgresMigrationConnector : MigrationTargetConnector {
    override val provider: String = "postgresql"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.PostgreSql)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase {
        target.requireNoProxy()
        target.requireNoProviderSpecific()
        return KagiroiMigrationDatabase(
            NodePostgresDatabase(target.requireConnectionString("PostgreSQL")),
            MigrationHistorySql.Postgres,
            PostgresMigrationLock,
        )
    }
}

/** MySQL migration execution backed by Kagiroi's mysql2 driver. */
object NodeMySqlMigrationConnector : MigrationTargetConnector {
    override val provider: String = "mysql"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.MySql)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase {
        target.requireNoProxy()
        target.requireNoProviderSpecific()
        return KagiroiMigrationDatabase(
            NodeMySqlDatabase(target.requireConnectionString("MySQL")),
            MigrationHistorySql.MySql,
            MySqlMigrationLock,
        )
    }
}

/** MariaDB uses Kagiroi's shared mysql2 transport with MariaDB SQL artifacts. */
object NodeMariaDbMigrationConnector : MigrationTargetConnector {
    override val provider: String = "mariadb"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.MariaDb)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase {
        target.requireNoProxy()
        target.requireNoProviderSpecific()
        return KagiroiMigrationDatabase(
            NodeMySqlDatabase(target.requireConnectionString("MariaDB")),
            MigrationHistorySql.MySql,
            MySqlMigrationLock,
        )
    }
}

/** Oracle migration execution backed by Kagiroi's node-oracledb driver. */
object NodeOracleMigrationConnector : MigrationTargetConnector {
    override val provider: String = "oracle"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.Oracle)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase {
        target.requireNoProxy()
        require(target.connectionStringEnv == null) { "Oracle target uses providerSpecific OracleTargetConfig" }
        val config = requireNotNull(target.providerSpecific) {
            "Oracle target requires providerSpecific OracleTargetConfig"
        }
            .let { MigrationCliConfiguration.json.decodeFromJsonElement<OracleTargetConfig>(it) }
        return KagiroiMigrationDatabase(
            NodeOracleDatabase {
                connectString = config.connectString
                user = environment(config.userEnv)
                password = environment(config.passwordEnv)
            },
            MigrationHistorySql.Oracle,
            OracleMigrationLock,
        )
    }
}

/** PGlite migration execution uses its PostgreSQL-compatible dialect and advisory locks. */
object NodePGliteMigrationConnector : MigrationTargetConnector {
    override val provider: String = "pglite"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.PostgreSql)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase {
        target.requireNoProxy()
        target.requireNoProviderSpecific()
        return KagiroiMigrationDatabase(
            PGliteDatabase.open(PGliteStorage.FileSystem(target.requireConnectionString("PGlite"))),
            MigrationHistorySql.Postgres,
            PostgresMigrationLock,
        )
    }
}

/** File-backed SQLite execution uses a durable lock record because SQLite has no advisory lock. */
object NodeSqliteMigrationConnector : MigrationTargetConnector {
    override val provider: String = "sqlite"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.Sqlite)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase {
        target.requireNoProxy()
        target.requireNoProviderSpecific()
        return KagiroiMigrationDatabase(
            FileSqliteDatabase(target.requireConnectionString("SQLite")),
            MigrationHistorySql.Sqlite,
            SqliteMigrationLock(migrationLockOwner()),
        )
    }
}

/** SQL Server uses a session-owned application lock through Kagiroi's mssql driver. */
object NodeSqlServerMigrationConnector : MigrationTargetConnector {
    override val provider: String = "sqlserver"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.SqlServer)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase {
        target.requireNoProxy()
        target.requireNoProviderSpecific()
        return KagiroiMigrationDatabase(
            NodeSqlServerDatabase(target.requireConnectionString("SQL Server")),
            MigrationHistorySql.SqlServer,
            SqlServerMigrationLock,
        )
    }
}

/** D1 execution uses the Cloudflare account API and sends explicit transaction steps as batches. */
object NodeD1MigrationConnector : MigrationTargetConnector {
    override val provider: String = "d1"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.D1)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase {
        target.requireNoProxy()
        require(target.connectionStringEnv == null) { "D1 target uses providerSpecific D1TargetConfig" }
        val config = requireNotNull(target.providerSpecific) { "D1 target requires providerSpecific D1TargetConfig" }
            .let { MigrationCliConfiguration.json.decodeFromJsonElement<D1TargetConfig>(it) }
        return D1MigrationDatabase(
            d1HttpDatabase(config.accountId, config.databaseId, environment(config.apiTokenEnv)),
        )
    }
}

private fun MigrationTarget.requireConnectionString(providerName: String): String {
    requireNotNull(connectionStringEnv) { "$providerName target requires connectionStringEnv" }
    return environment(connectionStringEnv)
}

private fun MigrationTarget.requireNoProviderSpecific() {
    require(providerSpecific == null) { "$provider target does not accept providerSpecific configuration" }
}

private fun MigrationTarget.requireNoProxy() {
    require(proxy == null) { "$provider target does not implement proxy transport" }
}

private fun environment(name: String): String = NodeProcess.env[name] as? String
    ?: error("Migration connection environment variable is not set: $name")

@Suppress("UnusedParameter")
private fun migrationLockOwner(): String = js("globalThis.crypto.randomUUID()") as String

suspend fun main() {
    val (configPath, commandArguments) = splitConfigArgument(NodeProcess.argv.drop(2))
    if (commandArguments.singleOrNull() == "--help") {
        println("Usage: kagiroi migration apply [--dry-run] [--target <name>] [--config <path>]")
        println("       kagiroi migration repair [--dry-run] [--target <name>] [--config <path>]")
        println("       kagiroi migration repair --clear-lock [--target <name>] [--config <path>]")
        return
    }
    val configuration = MigrationCliConfiguration.json.decodeFromString<MigrationCliConfiguration>(
        NodeFileSystem.readFileSync(configPath, "utf8"),
    )
    val result = MigrationCli(
        artifacts = NodeMigrationArtifactSource(configuration.migrationsDirectory),
        targets = configuration.targets,
        connectors = listOf(
            NodePostgresMigrationConnector,
            NodeMySqlMigrationConnector,
            NodeMariaDbMigrationConnector,
            NodeOracleMigrationConnector,
            NodePGliteMigrationConnector,
            NodeSqliteMigrationConnector,
            NodeSqlServerMigrationConnector,
            NodeD1MigrationConnector,
        ),
    ).run(parseMigrationCommand(commandArguments))
    when (result) {
        is MigrationCommandResult.Applied -> result.preflight.pending.forEach { step ->
            println("pending ${step.migrationId}:${step.metadata.index}")
        }

        is MigrationCommandResult.Repaired -> {
            val prefix = if (result.dryRun) "would repair" else "repaired"
            result.result.changes.forEach { change ->
                val action = if (change.newSha256 == null) {
                    "remove executing history"
                } else {
                    "align checksum ${change.oldSha256} -> ${change.newSha256}"
                }
                println("$prefix ${change.migrationId}:${change.stepIndex}: $action")
                if (result.dryRun) println(change.sql)
            }
            if (result.result.changes.isEmpty()) println("No history changes.")
        }

        MigrationCommandResult.LockCleared -> println("Cleared migration lock.")
    }
}

private fun splitConfigArgument(arguments: List<String>): Pair<String, List<String>> {
    val configIndex = arguments.indexOf("--config")
    if (configIndex < 0) return "kagiroi.migration.json" to arguments
    val path = arguments.getOrNull(configIndex + 1) ?: error("--config requires a path")
    return path to arguments.filterIndexed { index, _ -> index != configIndex && index != configIndex + 1 }
}
