package dev.quwac.kagiroi.migration.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

typealias MigrationDatabase = dev.quwac.kagiroi.migration.MigrationDatabase
typealias MigrationExecutor = dev.quwac.kagiroi.migration.MigrationExecutor
typealias MigrationHistoryEntry = dev.quwac.kagiroi.migration.MigrationHistoryEntry
typealias MigrationHistoryStatus = dev.quwac.kagiroi.migration.MigrationHistoryStatus
typealias MigrationPreflight = dev.quwac.kagiroi.migration.MigrationPreflight
typealias PendingMigrationStep = dev.quwac.kagiroi.migration.PendingMigrationStep
typealias KagiroiMigrations = dev.quwac.kagiroi.migration.KagiroiMigrations

@Serializable
data class MigrationTarget(
    val provider: String,
    val connectionStringEnv: String? = null,
    val proxy: ProxyConfig? = null,
    val providerSpecific: JsonObject? = null,
)

@Serializable
data class ProxyConfig(val urlEnv: String, val headersEnv: String? = null)

@Serializable
data class OracleTargetConfig(val connectString: String, val userEnv: String, val passwordEnv: String)

@Serializable
data class D1TargetConfig(val accountId: String, val databaseId: String, val apiTokenEnv: String)
