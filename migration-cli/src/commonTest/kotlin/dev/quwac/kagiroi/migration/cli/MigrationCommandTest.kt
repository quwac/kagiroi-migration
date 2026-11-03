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
    fun resolve_requires_one_explicit_resolution_action() {
        val sha = "a".repeat(64)
        assertEquals(
            MigrationCommand.ResolveApplied("default", "20261101090000_init", 0, sha),
            parseMigrationCommand(
                listOf(
                    "migration",
                    "resolve",
                    "--migration",
                    "20261101090000_init",
                    "--step",
                    "0",
                    "--mark-applied",
                    sha,
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            parseMigrationCommand(listOf("migration", "resolve", "--migration", "20261101090000_init", "--step", "0"))
        }
    }

    @Test
    fun parses_explicit_stale_lock_clearance_without_a_migration_step() {
        assertEquals(
            MigrationCommand.ResolveClearLock("production"),
            parseMigrationCommand(listOf("migration", "resolve", "--target", "production", "--clear-lock")),
        )
        assertFailsWith<IllegalArgumentException> {
            parseMigrationCommand(
                listOf("migration", "resolve", "--clear-lock", "--migration", "20261101090000_init"),
            )
        }
    }
}
