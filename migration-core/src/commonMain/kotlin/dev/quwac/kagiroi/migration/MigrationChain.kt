package dev.quwac.kagiroi.migration

/** A migration artifact together with the filename that defines its identity and order. */
data class NamedMigrationArtifact(val filename: String, val artifact: MigrationArtifact) {
    val id: String = MigrationId.fromFilename(filename)
}

/** Filename-ordered migration history. There is no parent relation or migration DAG. */
class MigrationChain private constructor(val artifacts: List<NamedMigrationArtifact>) {
    companion object {
        fun discover(artifacts: List<NamedMigrationArtifact>): MigrationChain {
            require(artifacts.map(NamedMigrationArtifact::filename).distinct().size == artifacts.size) {
                "Migration filenames must be unique"
            }
            artifacts.forEach { MigrationId.fromFilename(it.filename) }
            val ordered = artifacts.sortedBy(NamedMigrationArtifact::filename)
            val dialects = ordered.map { it.artifact.metadata.dialect }.distinct()
            require(dialects.size <= 1) { "Migration artifacts must use one dialect" }
            return MigrationChain(ordered)
        }
    }
}
