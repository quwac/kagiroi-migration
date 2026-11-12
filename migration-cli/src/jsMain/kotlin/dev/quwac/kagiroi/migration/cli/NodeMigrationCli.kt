@file:Suppress("TooGenericExceptionCaught", "UnsafeCastFromDynamic", "UnusedParameter")

package dev.quwac.kagiroi.migration.cli

import dev.quwac.kagiroi.d1.d1HttpDatabase
import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.MigrationId
import dev.quwac.kagiroi.migration.NamedMigrationArtifact
import dev.quwac.kagiroi.migration.parseMigrationArtifact
import dev.quwac.kagiroi.mysql.node.NodeMySqlDatabase
import dev.quwac.kagiroi.oracle.node.NodeOracleDatabase
import dev.quwac.kagiroi.pglite.PGliteDatabase
import dev.quwac.kagiroi.pglite.PGliteStorage
import dev.quwac.kagiroi.postgres.node.NodePostgresDatabase
import dev.quwac.kagiroi.sqlite.file.FileSqliteDatabase
import dev.quwac.kagiroi.sqlserver.node.NodeSqlServerDatabase

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
    var exitCode: Int
    val stderr: NodeWritable
}

private external interface NodeWritable {
    fun write(text: String): Boolean
}

/** Node and Bun filesystem source for generated migrations and handwritten SQL. */
class NodeMigrationArtifactSource(private val directory: String) : MigrationArtifactSource {
    override suspend fun readArtifacts(defaultDialect: MigrationDialect?): List<NamedMigrationArtifact> =
        NodeFileSystem.readdirSync(directory)
            .filter { filename -> filename.endsWith(".sql") }
            .map { filename ->
                MigrationId.fromFilename(filename)
                NamedMigrationArtifact(
                    filename,
                    parseMigrationArtifact(
                        NodeFileSystem.readFileSync(NodePath.join(directory, filename), "utf8"),
                        defaultDialect,
                    ),
                )
            }
}

/** PostgreSQL migration execution backed by Kagiroi's node-postgres driver. */
object NodePostgresMigrationConnector : MigrationTargetConnector {
    override val provider: String = "postgresql"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.PostgreSql)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase = KagiroiMigrationDatabase(
        NodePostgresDatabase(databaseUrl()),
        MigrationHistorySql.Postgres,
        PostgresMigrationLock,
    )
}

/** MySQL migration execution backed by Kagiroi's mysql2 driver. */
object NodeMySqlMigrationConnector : MigrationTargetConnector {
    override val provider: String = "mysql"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.MySql)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase = KagiroiMigrationDatabase(
        NodeMySqlDatabase(databaseUrl()),
        MigrationHistorySql.MySql,
        MySqlMigrationLock,
    )
}

/** MariaDB uses Kagiroi's shared mysql2 transport with MariaDB SQL artifacts. */
object NodeMariaDbMigrationConnector : MigrationTargetConnector {
    override val provider: String = "mariadb"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.MariaDb)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase = KagiroiMigrationDatabase(
        NodeMySqlDatabase(databaseUrl()),
        MigrationHistorySql.MySql,
        MySqlMigrationLock,
    )
}

/** Oracle migration execution backed by Kagiroi's node-oracledb driver. */
object NodeOracleMigrationConnector : MigrationTargetConnector {
    override val provider: String = "oracle"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.Oracle)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase = KagiroiMigrationDatabase(
        NodeOracleDatabase {
            connectString = databaseUrl()
            user = environment("ORACLE_USER")
            password = environment("ORACLE_PASSWORD")
        },
        MigrationHistorySql.Oracle,
        OracleMigrationLock,
    )
}

/** PGlite migration execution uses its PostgreSQL-compatible dialect and advisory locks. */
object NodePGliteMigrationConnector : MigrationTargetConnector {
    override val provider: String = "pglite"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.PostgreSql)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase = KagiroiMigrationDatabase(
        PGliteDatabase.open(PGliteStorage.FileSystem(databaseUrl())),
        MigrationHistorySql.Postgres,
        PostgresMigrationLock,
    )
}

/** File-backed SQLite execution uses a durable lock record because SQLite has no advisory lock. */
object NodeSqliteMigrationConnector : MigrationTargetConnector {
    override val provider: String = "sqlite"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.Sqlite)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase = KagiroiMigrationDatabase(
        FileSqliteDatabase(databaseUrl()),
        MigrationHistorySql.Sqlite,
        SqliteMigrationLock(migrationLockOwner()),
    )
}

/** SQL Server uses a session-owned application lock through Kagiroi's mssql driver. */
object NodeSqlServerMigrationConnector : MigrationTargetConnector {
    override val provider: String = "sqlserver"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.SqlServer)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase = KagiroiMigrationDatabase(
        NodeSqlServerDatabase(databaseUrl()),
        MigrationHistorySql.SqlServer,
        SqlServerMigrationLock,
    )
}

/** D1 execution uses the Cloudflare account API and sends explicit transaction steps as batches. */
object NodeD1MigrationConnector : MigrationTargetConnector {
    override val provider: String = "d1"
    override val supportedDialects = setOf(dev.quwac.kagiroi.migration.MigrationDialect.D1)

    override suspend fun connect(target: MigrationTarget): MigrationDatabase = D1MigrationDatabase(
        d1HttpDatabase(
            requireNotNull(target.accountId) { "D1 requires --account-id" },
            requireNotNull(target.databaseId) { "D1 requires --database-id" },
            environment("CLOUDFLARE_API_TOKEN"),
        ),
    )
}

private fun databaseUrl(): String = environment("DATABASE_URL")

private fun environment(name: String): String = NodeProcess.env[name] as? String
    ?: error("Migration connection environment variable is not set: $name")

@Suppress("UnusedParameter")
private fun migrationLockOwner(): String = js("globalThis.crypto.randomUUID()") as String

suspend fun main() {
    try {
        runCli()
    } catch (failure: Throwable) {
        NodeProcess.stderr.write("kagiroi: ${failure.message ?: failure.toString()}\n")
        NodeProcess.exitCode = 1
    }
}

private suspend fun runCli() {
    val commandArguments = NodeProcess.argv.drop(2)
    if (commandArguments.singleOrNull() == "--help") {
        println("Usage: kagiroi migration apply --provider <provider> [--dir <path>] [--dry-run]")
        println("       kagiroi migration repair --provider <provider> [--dir <path>] [--dry-run | --clear-lock]")
        println("Providers: postgresql, mysql, mariadb, sqlserver, sqlite, pglite, oracle, d1")
        println("--dir defaults to src/main/resources/kagiroi/migrations")
        println("D1 also requires --account-id <id> --database-id <id>")
        println("Connections: DATABASE_URL; Oracle also uses ORACLE_USER and ORACLE_PASSWORD")
        println("D1 token: CLOUDFLARE_API_TOKEN")
        return
    }
    val command = parseMigrationCommand(commandArguments)
    val result = MigrationCli(
        artifacts = NodeMigrationArtifactSource(command.directory),
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
    ).run(command)
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
