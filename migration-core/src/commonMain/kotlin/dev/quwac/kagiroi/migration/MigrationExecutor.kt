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
data class MigrationRepairChange(
    val migrationId: String,
    val stepIndex: Int,
    val oldSha256: String,
    val newSha256: String?,
    val sql: String,
)
data class MigrationRepairResult(val changes: List<MigrationRepairChange>)

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

    /** Reconciles history only. The caller must first undo any incomplete database changes. */
    @Suppress("LongMethod")
    suspend fun repair(artifacts: List<NamedMigrationArtifact>, dryRun: Boolean = false): MigrationRepairResult =
        database.withMigrationLock(namespace) {
            database.ensureHistoryTable()
            val chain = validateFinalizedMigrationArtifacts(artifacts)
            chain.artifacts.forEach { database.validateDialect(MigrationDialect.parse(it.artifact.metadata.dialect)) }
            val expected = chain.artifacts.flatMap { artifact ->
                artifact.artifact.metadata.steps.map { step -> (artifact.id to step.index) to step.sha256 }
            }
            val expectedByKey = expected.toMap()
            val sqlByKey = chain.artifacts.flatMap { artifact ->
                val dialect = MigrationDialect.parse(artifact.artifact.metadata.dialect)
                MigrationScriptParser.parse(artifact.artifact.body, dialect).steps.mapIndexed { index, step ->
                    (artifact.id to index) to step.sql
                }
            }.toMap()
            val history = database.readHistory()
            require(history.map { it.migrationId to it.stepIndex }.distinct().size == history.size) {
                "Database migration history contains duplicate step rows"
            }
            history.forEach { entry ->
                require((entry.migrationId to entry.stepIndex) in expectedByKey) {
                    "Database history has no local migration step ${entry.migrationId}:${entry.stepIndex}"
                }
            }
            val executing = history.filter { it.status == MigrationHistoryStatus.Executing }
            val applied = history.filter { it.status == MigrationHistoryStatus.Applied }
            val appliedKeys = applied.map { it.migrationId to it.stepIndex }.toSet()
            require(expected.take(applied.size).all { it.first in appliedKeys }) {
                "Database migration history contains a gap"
            }
            executing.forEach { entry ->
                val key = entry.migrationId to entry.stepIndex
                require(expected.indexOfFirst { it.first == key } == applied.size) {
                    "Executing migration is not the first pending step"
                }
            }
            val changes = buildList {
                applied.forEach { entry ->
                    val expectedHash = expectedByKey.getValue(entry.migrationId to entry.stepIndex)
                    if (entry.stepSha256 != expectedHash) {
                        add(
                            MigrationRepairChange(
                                entry.migrationId,
                                entry.stepIndex,
                                entry.stepSha256,
                                expectedHash,
                                sqlByKey.getValue(entry.migrationId to entry.stepIndex),
                            ),
                        )
                    }
                }
                executing.forEach {
                    add(
                        MigrationRepairChange(
                            it.migrationId,
                            it.stepIndex,
                            it.stepSha256,
                            null,
                            sqlByKey.getValue(it.migrationId to it.stepIndex),
                        ),
                    )
                }
            }
            if (!dryRun) {
                changes.forEach { change ->
                    if (change.newSha256 == null) {
                        database.deleteHistory(change.migrationId, change.stepIndex)
                    } else {
                        database.replaceHistoryHash(change.migrationId, change.stepIndex, change.newSha256)
                    }
                }
            }
            MigrationRepairResult(changes)
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
                "Migration ${entry.migrationId}:${entry.stepIndex} is still executing; inspect the database and run repair"
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
