@file:Suppress("BlankLineBetweenWhenConditions")

package dev.quwac.kagiroi.migration

/** Logical shape of the only table a provider adapter may create for migration state. */
data object KagiroiMigrations {
    const val TABLE_NAME: String = "kagiroi_migrations"
    const val MIGRATION_ID: String = "migration_id"
    const val STEP_INDEX: String = "step_index"
    const val STEP_SHA256: String = "step_sha256"
    const val STATUS: String = "status"
    const val APPLIED_AT: String = "applied_at"
}

enum class MigrationHistoryStatus { Executing, Applied }

data class MigrationHistoryEntry(
    val migrationId: String,
    val stepIndex: Int,
    val stepSha256: String,
    val status: MigrationHistoryStatus,
    val appliedAt: String? = null,
)

/** The only database surface required by migration apply. Provider adapters own connection and proxy semantics. */
interface MigrationDatabase {
    fun validateDialect(dialect: MigrationDialect) = Unit
    suspend fun <T> withMigrationLock(namespace: String, action: suspend () -> T): T
    suspend fun ensureHistoryTable()
    suspend fun readHistory(): List<MigrationHistoryEntry>
    suspend fun executeRaw(sql: String)
    suspend fun insertExecuting(migrationId: String, stepIndex: Int, sha256: String)
    suspend fun markApplied(migrationId: String, stepIndex: Int, sha256: String)
    suspend fun deleteHistory(migrationId: String, stepIndex: Int)
    suspend fun replaceHistoryHash(migrationId: String, stepIndex: Int, sha256: String)

    /** Removes only an explicitly resolved provider lock reservation; it never changes migration history. */
    suspend fun clearMigrationLock(): Unit = error("This migration provider cannot clear a durable migration lock")

    suspend fun close() = Unit
}

data class PendingMigrationStep(
    val migrationId: String,
    val metadata: MigrationArtifactStep,
    val sql: String,
    val dialect: String,
)
data class MigrationPreflight(val pending: List<PendingMigrationStep>)

/** Runs migrations against a host-provided database adapter. The caller owns the database lifecycle. */
class MigrationExecutor(private val database: MigrationDatabase, private val namespace: String = "kagiroi_migrations") {
    suspend fun dryRun(artifacts: List<NamedMigrationArtifact>): MigrationPreflight = preflight(artifacts)

    suspend fun apply(artifacts: List<NamedMigrationArtifact>): MigrationPreflight =
        database.withMigrationLock(namespace) {
            database.ensureHistoryTable()
            val preflight = preflight(artifacts)
            preflight.pending.forEach { pending -> execute(pending) }
            preflight
        }

    suspend fun resolveApplied(migrationId: String, stepIndex: Int, sha256: String) =
        database.withMigrationLock(namespace) {
            database.ensureHistoryTable()
            val history = requireExecutingStep(migrationId, stepIndex)
            require(history.stepSha256 == sha256) {
                "Resolve SHA-256 does not match executing migration $migrationId:$stepIndex"
            }
            database.markApplied(migrationId, stepIndex, sha256)
        }

    suspend fun resolvePending(migrationId: String, stepIndex: Int) = database.withMigrationLock(namespace) {
        database.ensureHistoryTable()
        requireExecutingStep(migrationId, stepIndex)
        database.deleteHistory(migrationId, stepIndex)
    }

    suspend fun acceptHash(migrationId: String, stepIndex: Int, sha256: String) =
        database.withMigrationLock(namespace) {
            database.ensureHistoryTable()
            require(
                database.readHistory().any { entry ->
                    entry.migrationId == migrationId && entry.stepIndex == stepIndex
                },
            ) { "Migration history has no step $migrationId:$stepIndex to reconcile" }
            database.replaceHistoryHash(migrationId, stepIndex, sha256)
        }

    suspend fun clearMigrationLock(): Unit = database.clearMigrationLock()

    private suspend fun preflight(artifacts: List<NamedMigrationArtifact>): MigrationPreflight {
        val chain = validateFinalizedMigrationArtifacts(artifacts)
        chain.artifacts.forEach { database.validateDialect(MigrationDialect.parse(it.artifact.metadata.dialect)) }
        val expected = chain.artifacts.flatMap { artifact ->
            val parsed = MigrationScriptParser.parse(
                artifact.artifact.body,
                dev.quwac.kagiroi.migration.MigrationDialect.parse(artifact.artifact.metadata.dialect),
            )
            parsed.steps.zip(artifact.artifact.metadata.steps).map { (sql, metadata) ->
                PendingMigrationStep(artifact.id, metadata, sql.sql, artifact.artifact.metadata.dialect)
            }
        }
        val history = database.readHistory()
        val expectedByKey = expected.associateBy { it.migrationId to it.metadata.index }
        require(history.map { it.migrationId to it.stepIndex }.distinct().size == history.size) {
            "Database migration history contains duplicate step rows"
        }
        history.forEach { entry ->
            val pending = expectedByKey[entry.migrationId to entry.stepIndex]
                ?: throw IllegalStateException(
                    "Database history has no local migration step ${entry.migrationId}:${entry.stepIndex}",
                )
            require(entry.stepSha256 == pending.metadata.sha256) {
                "Migration history SHA-256 mismatch for ${entry.migrationId}:${entry.stepIndex}"
            }
            require(entry.status != MigrationHistoryStatus.Executing) {
                "Migration ${entry.migrationId}:${entry.stepIndex} is still executing; resolve it explicitly"
            }
        }
        val applied = history.map { it.migrationId to it.stepIndex }.toSet()
        val firstPending = expected.indexOfFirst { (it.migrationId to it.metadata.index) !in applied }
        if (firstPending >= 0) {
            expected.drop(firstPending + 1).forEach { step ->
                require((step.migrationId to step.metadata.index) !in applied) {
                    "Database migration history has a gap before ${step.migrationId}:${step.metadata.index}"
                }
            }
        }
        return MigrationPreflight(if (firstPending < 0) emptyList() else expected.drop(firstPending))
    }

    private suspend fun requireExecutingStep(migrationId: String, stepIndex: Int): MigrationHistoryEntry {
        val entry = database.readHistory().singleOrNull { history ->
            history.migrationId == migrationId && history.stepIndex == stepIndex
        } ?: error("Migration history has no executing step $migrationId:$stepIndex")
        require(entry.status == MigrationHistoryStatus.Executing) {
            "Migration $migrationId:$stepIndex is not executing"
        }
        return entry
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(step: PendingMigrationStep) = when (step.metadata.kind) {
        MigrationStepKind.Transactional -> {
            val units = MigrationScriptParser.parse(
                step.sql,
                dev.quwac.kagiroi.migration.MigrationDialect.parse(step.dialect),
            )
                .steps.single().executionUnits
            database.executeRaw(units.first())
            try {
                units.drop(1).dropLast(1).forEach { unit -> database.executeRaw(unit) }
                database.markApplied(step.migrationId, step.metadata.index, step.metadata.sha256)
                database.executeRaw(units.last())
            } catch (failure: Throwable) {
                try {
                    database.executeRaw("ROLLBACK;")
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
        MigrationStepKind.NonTransactional -> {
            database.insertExecuting(step.migrationId, step.metadata.index, step.metadata.sha256)
            database.executeRaw(step.sql)
            database.markApplied(step.migrationId, step.metadata.index, step.metadata.sha256)
        }
    }
}
