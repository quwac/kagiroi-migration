package dev.quwac.kagiroi.migration

/** Validates finalized artifact structure without requiring current Kotlin table definitions. */
fun validateFinalizedMigrationArtifacts(artifacts: List<NamedMigrationArtifact>): MigrationChain {
    val chain = MigrationChain.discover(artifacts)
    require(chain.drafts.isEmpty()) { "Finalize ${chain.drafts.single().filename} before applying migrations" }
    chain.artifacts.forEach { named ->
        val parsed = MigrationScriptParser.parse(
            named.artifact.body,
            MigrationDialect.parse(named.artifact.metadata.dialect),
        )
        require(parsed.steps.size == named.artifact.metadata.steps.size) {
            "Migration ${named.filename} execution step count does not match metadata"
        }
        parsed.steps.zip(named.artifact.metadata.steps).forEach { (step, metadata) ->
            require(step.kind == metadata.kind) { "Migration ${named.filename} step ${metadata.index} kind mismatch" }
            require(stepFingerprint(step) == metadata.sha256) {
                "Migration ${named.filename} step ${metadata.index} SHA-256 mismatch"
            }
        }
        val expectedMarkers = buildList {
            var index = 0
            named.artifact.metadata.steps.forEach { step ->
                step.schemaDelta.operations.forEach { operation ->
                    add(OperationMarker(index++, operationFingerprint(operation)))
                }
            }
        }
        require(parsed.steps.flatMap(ParsedExecutionStep::operationMarkers) == expectedMarkers) {
            "Migration ${named.filename} operation markers do not match schemaDelta"
        }
    }
    return chain
}
