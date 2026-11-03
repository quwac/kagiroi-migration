@file:OptIn(ExperimentalJsExport::class)

package dev.quwac.kagiroi.migration.integration

import dev.quwac.kagiroi.migration.HyperdriveMigrationDatabase
import dev.quwac.kagiroi.migration.MigrationArtifact
import dev.quwac.kagiroi.migration.MigrationArtifactMetadata
import dev.quwac.kagiroi.migration.MigrationArtifactStep
import dev.quwac.kagiroi.migration.MigrationDialect
import dev.quwac.kagiroi.migration.MigrationExecutor
import dev.quwac.kagiroi.migration.MigrationHistoryDialect
import dev.quwac.kagiroi.migration.MigrationScriptParser
import dev.quwac.kagiroi.migration.NamedMigrationArtifact
import dev.quwac.kagiroi.migration.SchemaDelta
import dev.quwac.kagiroi.migration.stepFingerprint
import dev.quwac.kagiroi.mysql.hyperdrive.HyperdriveMySqlDatabase
import dev.quwac.kagiroi.postgres.hyperdrive.HyperdrivePostgresDatabase
import dev.quwac.kagiroi.sql.SqlDatabase
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise
import dev.quwac.kagiroi.mysql.hyperdrive.CloudflareHyperdriveBinding as MySqlBinding
import dev.quwac.kagiroi.postgres.hyperdrive.CloudflareHyperdriveBinding as PostgresBinding

@JsExport
fun postgresContract(binding: PostgresBinding): Promise<String> = launchContract {
    contract({ HyperdrivePostgresDatabase(binding) }, MigrationHistoryDialect.PostgreSql, MigrationDialect.PostgreSql)
}

@JsExport
fun mysqlContract(binding: MySqlBinding): Promise<String> = launchContract {
    contract({ HyperdriveMySqlDatabase(binding) }, MigrationHistoryDialect.MySql, MigrationDialect.MySql)
}

private fun launchContract(block: suspend () -> Unit): Promise<String> = Promise { resolve, reject ->
    block.startCoroutine(object : Continuation<Unit> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {
            result.fold({ resolve("ok") }, { reject(jsError(it.toString())) })
        }
    })
}

private suspend fun contract(open: () -> SqlDatabase, history: MigrationHistoryDialect, dialect: MigrationDialect) {
    val adapter = HyperdriveMigrationDatabase(open(), history) { "worker-contract" }
    val second = HyperdriveMigrationDatabase(open(), history) { "worker-competitor" }
    try {
        adapter.withMigrationLock("worker-contract") {
            val failed = try {
                second.withMigrationLock("worker-contract") {}
                false
            } catch (_: IllegalStateException) {
                true
            }
            check(failed) { "Concurrent migration acquired the lock" }
        }
        val body = "CREATE TABLE kagiroi_worker_contract (id INTEGER);\n" +
            "BEGIN;\nINSERT INTO kagiroi_worker_contract VALUES (1);\nCOMMIT;"
        val steps = MigrationScriptParser.parse(body, dialect).steps.mapIndexed { index, step ->
            MigrationArtifactStep(index, step.kind, stepFingerprint(step), SchemaDelta())
        }
        val artifacts = listOf(
            NamedMigrationArtifact(
                "20261101090000_worker_contract.sql",
                MigrationArtifact(MigrationArtifactMetadata(dialect = dialect.id, steps = steps), body),
            ),
        )
        val executor = MigrationExecutor(adapter)
        check(executor.dryRun(artifacts).pending.size == 2)
        check(executor.apply(artifacts).pending.size == 2)
        check(executor.dryRun(artifacts).pending.isEmpty())
    } finally {
        try {
            adapter.close()
        } finally {
            second.close()
        }
    }
}

fun main() = Unit

@Suppress("UnusedParameter")
private fun jsError(message: String): Throwable = js("new Error(message)")
