package dev.quwac.kagiroi.migration

/** A migration artifact together with the filename that defines its identity and order. */
data class NamedMigrationArtifact(val filename: String, val artifact: MigrationArtifact) {
    val id: String = MigrationId.fromFilename(filename)
}

/** Filename-ordered migration history. There is no parent relation or migration DAG. */
class MigrationChain private constructor(val artifacts: List<NamedMigrationArtifact>) {
    val finalized: List<NamedMigrationArtifact> get() = artifacts.filter { it.artifact.isFinalized }
    val drafts: List<NamedMigrationArtifact> get() = artifacts.filterNot { it.artifact.isFinalized }

    companion object {
        fun discover(artifacts: List<NamedMigrationArtifact>): MigrationChain {
            require(artifacts.map(NamedMigrationArtifact::filename).distinct().size == artifacts.size) {
                "Migration filenames must be unique"
            }
            artifacts.forEach { MigrationId.fromFilename(it.filename) }
            val ordered = artifacts.sortedBy(NamedMigrationArtifact::filename)
            val dialects = ordered.map { it.artifact.metadata.dialect }.distinct()
            require(dialects.size <= 1) { "Migration artifacts must use one dialect" }
            val drafts = ordered.filterNot { it.artifact.isFinalized }
            require(drafts.size <= 1) { "Migration history contains multiple unfinalized artifacts" }
            require(drafts.isEmpty() || drafts.single() == ordered.last()) {
                "An unfinalized migration must be the last filename"
            }
            return MigrationChain(ordered)
        }
    }
}
