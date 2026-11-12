package dev.quwac.kagiroi.migration

/** Validates artifact identity, dialect, and executable SQL without current Kotlin table definitions. */
fun validateMigrationArtifacts(artifacts: List<NamedMigrationArtifact>): MigrationChain {
    val chain = MigrationChain.discover(artifacts)
    chain.artifacts.forEach { named ->
        require(named.artifact.steps.isNotEmpty()) { "Migration ${named.filename} contains no executable SQL" }
    }
    return chain
}
