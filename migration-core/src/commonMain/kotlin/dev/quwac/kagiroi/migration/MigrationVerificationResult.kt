@file:Suppress("Filename")

package dev.quwac.kagiroi.migration

data class MigrationVerificationResult(
    val migrationCount: Int,
    val stepCount: Int,
    val reconstructedSchema: CanonicalDesiredSchema,
)

/** Validates artifact metadata, executable script boundaries, and file-level schema replay. */
fun verifyMigrationArtifacts(
    artifacts: List<NamedMigrationArtifact>,
    currentSchema: CanonicalDesiredSchema,
    expectedDialect: MigrationDialect? = null,
): MigrationVerificationResult {
    val chain = validateMigrationArtifacts(artifacts)
    val deltas = mutableListOf<SchemaDelta>()
    var count = 0
    chain.artifacts.forEach { named ->
        val artifact = named.artifact
        val dialect = MigrationDialect.parse(artifact.metadata.dialect)
        require(expectedDialect == null || dialect == expectedDialect) {
            "Migration ${named.filename} uses ${dialect.id}, expected ${expectedDialect?.id}"
        }
        deltas += artifact.metadata.schemaDelta
        count += artifact.steps.size
    }
    val replayed = replaySchemaDeltas(deltas)
    require(
        physicalSchemaHash(replayed.schema.withColumnsByName()) ==
            physicalSchemaHash(currentSchema.schema.withColumnsByName()),
    ) {
        "Replayed migration schema does not match the current Kotlin schema"
    }
    return MigrationVerificationResult(chain.artifacts.size, count, replayed)
}

/** Comparison alone ignores declaration order; persisted schema and step hashes retain their byte sources. */
private fun CanonicalPhysicalSchema.withColumnsByName(): CanonicalPhysicalSchema = copy(
    tables = tables.map { table -> table.copy(columns = table.columns.sortedBy(CanonicalColumn::name)) },
)
