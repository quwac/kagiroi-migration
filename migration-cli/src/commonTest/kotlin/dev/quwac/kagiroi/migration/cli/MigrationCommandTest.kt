package dev.quwac.kagiroi.migration.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationCommandTest {
    @Test
    fun parses_apply_with_provider_directory_and_dry_run() {
        assertEquals(
            MigrationCommand.Apply(MigrationTarget("postgresql"), "migrations", dryRun = true),
            parseMigrationCommand(
                listOf("migration", "apply", "--provider", "postgresql", "--dir", "migrations", "--dry-run"),
            ),
        )
    }

    @Test
    fun repair_uses_the_same_connection_and_directory_options() {
        assertEquals(
            MigrationCommand.Repair(MigrationTarget("postgresql")),
            parseMigrationCommand(listOf("migration", "repair", "--provider", "postgresql")),
        )
        assertEquals(
            MigrationCommand.Repair(MigrationTarget("sqlite"), "migrations", dryRun = true),
            parseMigrationCommand(
                listOf("migration", "repair", "--provider", "sqlite", "--dir", "migrations", "--dry-run"),
            ),
        )
    }

    @Test
    fun lock_clearance_accepts_d1_identifiers_and_rejects_dry_run() {
        assertEquals(
            MigrationCommand.Repair(MigrationTarget("d1", "account", "database"), clearLock = true),
            parseMigrationCommand(
                listOf(
                    "migration", "repair", "--provider", "d1", "--account-id", "account",
                    "--database-id", "database", "--clear-lock",
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            parseMigrationCommand(
                listOf("migration", "repair", "--provider", "sqlite", "--clear-lock", "--dry-run"),
            )
        }
    }

    @Test
    fun rejects_missing_empty_duplicate_and_unsupported_options() {
        val invalidOptions = listOf(
            emptyList(),
            listOf("--provider"),
            listOf("--provider", ""),
            listOf("--provider", "--dry-run"),
            listOf("--provider", "sqlite", "--dir"),
            listOf("--provider", "sqlite", "--dir", "--dry-run"),
            listOf("--provider", "sqlite", "--provider", "postgresql"),
            listOf("--provider", "sqlite", "--dry-run", "--dry-run"),
            listOf("--provider", "sqlite", "--clear-lock"),
            listOf("--provider", "sqlite", "--config", "migration.json"),
            listOf("--provider", "sqlite", "--target", "production"),
            listOf("--provider", "sqlite", "--connection-string", "synthetic"),
        )
        invalidOptions.forEach { options ->
            assertFailsWith<IllegalArgumentException>(options.toString()) {
                parseMigrationCommand(listOf("migration", "apply") + options)
            }
        }
    }

    @Test
    fun requires_both_d1_identifiers_and_rejects_them_for_other_providers() {
        listOf(
            listOf("--provider", "d1"),
            listOf("--provider", "d1", "--account-id", "account"),
            listOf("--provider", "d1", "--database-id", "database"),
            listOf("--provider", "postgresql", "--account-id", "account"),
            listOf("--provider", "oracle", "--database-id", "database"),
        ).forEach { options ->
            assertFailsWith<IllegalArgumentException>(options.toString()) {
                parseMigrationCommand(listOf("migration", "apply") + options)
            }
        }
    }
}
