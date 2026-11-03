@file:Suppress("Filename")

package dev.quwac.kagiroi.migration

data class MigrationVerificationResult(
    val migrationCount: Int,
    val stepCount: Int,
    val reconstructedSchema: CanonicalDesiredSchema,
)

/** Validates artifact metadata, script structure, step hashes, marker assignments, and schema replay. */
fun verifyMigrationArtifacts(
    artifacts: List<NamedMigrationArtifact>,
    currentSchema: CanonicalDesiredSchema,
    expectedDialect: MigrationDialect? = null,
): MigrationVerificationResult {
    val chain = validateFinalizedMigrationArtifacts(artifacts)
    val deltas = mutableListOf<SchemaDelta>()
    var count = 0
    chain.artifacts.forEach { named ->
        val artifact = named.artifact
        val dialect = MigrationDialect.parse(artifact.metadata.dialect)
        require(expectedDialect == null || dialect == expectedDialect) {
            "Migration ${named.filename} uses ${dialect.id}, expected ${expectedDialect?.id}"
        }
        artifact.metadata.steps.forEach { metadataStep ->
            deltas += metadataStep.schemaDelta
            count++
        }
    }
    val replayed = replaySchemaDeltas(deltas)
    require(physicalSchemaHash(replayed) == physicalSchemaHash(currentSchema)) {
        "Replayed migration schema does not match the current Kotlin schema"
    }
    return MigrationVerificationResult(chain.artifacts.size, count, replayed)
}
