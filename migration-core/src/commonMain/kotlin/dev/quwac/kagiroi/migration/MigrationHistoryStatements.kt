@file:Suppress("BlankLineBetweenWhenConditions")

package dev.quwac.kagiroi.migration

/** SQL for the migration history table on PostgreSQL and MySQL-compatible databases. */
enum class MigrationHistoryDialect { PostgreSql, MySql }

class MigrationHistoryStatements(private val dialect: MigrationHistoryDialect) {
    val createTable: String = when (dialect) {
        MigrationHistoryDialect.PostgreSql -> """
            CREATE TABLE IF NOT EXISTS kagiroi_migrations (
                migration_id TEXT NOT NULL,
                step_index INTEGER NOT NULL,
                step_sha256 TEXT NOT NULL,
                status TEXT NOT NULL,
                applied_at TIMESTAMPTZ NULL,
                PRIMARY KEY (migration_id, step_index)
            )
        """.trimIndent()
        MigrationHistoryDialect.MySql -> """
            CREATE TABLE IF NOT EXISTS kagiroi_migrations (
                migration_id VARCHAR(255) NOT NULL,
                step_index INT NOT NULL,
                step_sha256 VARCHAR(64) NOT NULL,
                status VARCHAR(16) NOT NULL,
                applied_at TIMESTAMP NULL,
                PRIMARY KEY (migration_id, step_index)
            )
        """.trimIndent()
    }

    val readHistory: String = when (dialect) {
        MigrationHistoryDialect.PostgreSql ->
            "SELECT migration_id, step_index, step_sha256, status, applied_at::text " +
                "FROM kagiroi_migrations ORDER BY migration_id, step_index"
        MigrationHistoryDialect.MySql ->
            "SELECT migration_id, step_index, step_sha256, status, applied_at " +
                "FROM kagiroi_migrations ORDER BY migration_id, step_index"
    }

    fun insertExecuting(id: String, index: Int, hash: String): String = insert(id, index, hash, "executing", "NULL")

    fun markApplied(id: String, index: Int, hash: String): String =
        insert(id, index, hash, "applied", "CURRENT_TIMESTAMP") + when (dialect) {
            MigrationHistoryDialect.PostgreSql ->
                " ON CONFLICT (migration_id, step_index) DO UPDATE SET " +
                    "step_sha256 = EXCLUDED.step_sha256, status = 'applied', applied_at = CURRENT_TIMESTAMP"
            MigrationHistoryDialect.MySql ->
                " ON DUPLICATE KEY UPDATE step_sha256 = VALUES(step_sha256), status = 'applied', " +
                    "applied_at = CURRENT_TIMESTAMP"
        }

    fun deleteHistory(id: String, index: Int): String =
        "DELETE FROM kagiroi_migrations WHERE migration_id = '${escape(id)}' AND step_index = $index"

    fun replaceHistoryHash(id: String, index: Int, hash: String): String =
        "UPDATE kagiroi_migrations SET step_sha256 = '${escape(hash)}' " +
            "WHERE migration_id = '${escape(id)}' AND step_index = $index"

    private fun insert(id: String, index: Int, hash: String, status: String, appliedAt: String): String =
        "INSERT INTO kagiroi_migrations (migration_id, step_index, step_sha256, status, applied_at) VALUES " +
            "('${escape(id)}', $index, '${escape(hash)}', '$status', $appliedAt)"

    private fun escape(value: String): String = value.replace("'", "''")
}
