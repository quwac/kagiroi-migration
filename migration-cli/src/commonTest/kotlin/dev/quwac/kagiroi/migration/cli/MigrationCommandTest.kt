package dev.quwac.kagiroi.migration.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationCommandTest {
    @Test
    fun parses_apply_and_dry_run() {
        assertEquals(
            MigrationCommand.Apply("production", dryRun = true),
            parseMigrationCommand(listOf("migration", "apply", "--target", "production", "--dry-run")),
        )
    }

    @Test
    fun parses_repair_without_step_or_hash_arguments() {
        assertEquals(MigrationCommand.Repair("default"), parseMigrationCommand(listOf("migration", "repair")))
        assertEquals(
            MigrationCommand.Repair("production", dryRun = true),
            parseMigrationCommand(
                listOf("migration", "repair", "--target", "production", "--dry-run"),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            parseMigrationCommand(listOf("migration", "repair", "--migration", "20261101090000_init"))
        }
    }

    @Test
    fun lock_clearance_is_a_separate_explicit_action() {
        assertEquals(
            MigrationCommand.Repair("production", clearLock = true),
            parseMigrationCommand(listOf("migration", "repair", "--target", "production", "--clear-lock")),
        )
        assertFailsWith<IllegalArgumentException> {
            parseMigrationCommand(listOf("migration", "repair", "--clear-lock", "--dry-run"))
        }
    }
}
