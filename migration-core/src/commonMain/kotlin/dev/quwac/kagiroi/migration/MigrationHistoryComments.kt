package dev.quwac.kagiroi.migration

/** Native comments for supported history providers; SQLite/D1 do not persist SQL comments. */
fun migrationHistoryComments(dialect: MigrationDialect): List<String> {
    if (dialect == MigrationDialect.PostgreSql) {
        return MigrationHistoryStatements(
            MigrationHistoryDialect.PostgreSql,
        ).tableComments
    }
    if (dialect in setOf(MigrationDialect.Sqlite, MigrationDialect.D1)) return emptyList()
    val tableComment = "Execution checkpoints for Kagiroi migrations and provider lock reservations where required."
    val columns = linkedMapOf(
        "migration_id" to
            "Migration identifier derived from the SQL filename; __kagiroi_lock__ is reserved for provider locks.",
        "step_index" to "Zero-based execution step within a migration; -1 is reserved for provider locks.",
        "step_sha256" to
            "SHA-256 identifying the migration step content; provider lock rows store the lock owner token.",
        "status" to "Execution state: executing (started but not completed) or applied (completed).",
        "applied_at" to
            "Database timestamp when the step was recorded as applied; provider lock rows may use their reservation time.",
    )
    if (dialect in setOf(MigrationDialect.MySql, MigrationDialect.MariaDb)) {
        val definitions = mapOf(
            "migration_id" to "VARCHAR(255) NOT NULL",
            "step_index" to "INT NOT NULL",
            "step_sha256" to "VARCHAR(64) NOT NULL",
            "status" to "VARCHAR(16) NOT NULL",
            "applied_at" to "TIMESTAMP NULL DEFAULT NULL",
        )
        return listOf("ALTER TABLE kagiroi_migrations COMMENT = '$tableComment'") + columns.map { (name, comment) ->
            "ALTER TABLE kagiroi_migrations MODIFY COLUMN `$name` ${definitions.getValue(name)} COMMENT '$comment'"
        }
    }
    val target = when (dialect) {
        MigrationDialect.Oracle -> CanonicalTableReference(name = "KAGIROI_MIGRATIONS")
        else -> CanonicalTableReference("dbo", "kagiroi_migrations")
    }
    val operations = listOf(AlterTableComment(target, to = tableComment)) + columns.map { (name, comment) ->
        AlterColumnComment(
            CanonicalColumnReference(target, if (dialect == MigrationDialect.Oracle) name.uppercase() else name),
            to = comment,
        )
    }
    return MigrationSqlGenerator.generate(MigrationPlan.Changes(operations), dialect).statements.map { it.sql }
}
