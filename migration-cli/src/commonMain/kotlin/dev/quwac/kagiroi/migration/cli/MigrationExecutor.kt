package dev.quwac.kagiroi.migration.cli

typealias MigrationDatabase = dev.quwac.kagiroi.migration.MigrationDatabase
typealias MigrationExecutor = dev.quwac.kagiroi.migration.MigrationExecutor
typealias MigrationHistoryEntry = dev.quwac.kagiroi.migration.MigrationHistoryEntry
typealias MigrationHistoryStatus = dev.quwac.kagiroi.migration.MigrationHistoryStatus
typealias MigrationPreflight = dev.quwac.kagiroi.migration.MigrationPreflight
typealias PendingMigrationStep = dev.quwac.kagiroi.migration.PendingMigrationStep
typealias KagiroiMigrations = dev.quwac.kagiroi.migration.KagiroiMigrations

/** Connection options supplied by the CLI; credentials are resolved by the host connector. */
data class MigrationTarget(val provider: String, val accountId: String? = null, val databaseId: String? = null)
