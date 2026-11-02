package dev.quwac.kagiroi.migration

/** A migration artifact together with the filename that owns it. */
data class NamedMigrationArtifact(val filename: String, val artifact: MigrationArtifact) {
    init {
        require(filename == "${artifact.metadata.id}.sql") {
            "Migration filename $filename must match metadata id ${artifact.metadata.id}"
        }
    }
}

/** A validated, single linear chain of SQL migration artifacts. */
class MigrationChain private constructor(val artifacts: List<NamedMigrationArtifact>) {
    val latest: NamedMigrationArtifact? get() = artifacts.lastOrNull()

    val latestFinalized: NamedMigrationArtifact? get() = artifacts.lastOrNull {
        it.artifact.metadata.status == MigrationArtifactStatus.Final
    }

    val drafts: List<NamedMigrationArtifact> get() = artifacts.filter {
        it.artifact.metadata.status == MigrationArtifactStatus.Draft
    }

    companion object {
        /**
         * Validates IDs, direct-parent ownership, a single root/leaf, and finalized checksums.
         * The chain is derived from parents, not by trusting lexicographically largest filenames.
         */
        fun discover(artifacts: List<NamedMigrationArtifact>): MigrationChain {
            if (artifacts.isEmpty()) return MigrationChain(emptyList())
            val byId = artifacts.associateBy { it.artifact.metadata.id }
            require(byId.size == artifacts.size) { "Migration IDs must be unique" }
            artifacts.forEach { named ->
                require(named.filename.matches(Regex("[0-9]{14}_[a-z][a-z0-9_]*\\.sql"))) {
                    "Invalid migration filename: ${named.filename}"
                }
                named.artifact.validateChecksum().let { validation ->
                    require(validation !is MigrationChecksumValidation.Mismatch) {
                        "Migration ${named.artifact.metadata.id} SQL checksum mismatch"
                    }
                }
                named.artifact.metadata.parent?.let { parent ->
                    require(byId.containsKey(parent)) {
                        "Migration ${named.artifact.metadata.id} references missing parent $parent"
                    }
                    require(parent < named.artifact.metadata.id) {
                        "Migration ${named.artifact.metadata.id} parent must precede its ID"
                    }
                }
            }
            val roots = artifacts.filter { it.artifact.metadata.parent == null }
            require(roots.size == 1) { "Migration history must have exactly one root migration" }
            val children = artifacts.groupBy {
                it.artifact.metadata.parent
            }.mapValues { (_, value) -> value.singleOrNull() }
            require(
                children.values.none { it == null },
            ) { "Migration history branches; each migration has at most one child" }
            val ordered = buildList {
                var current: NamedMigrationArtifact? = roots.single()
                while (current != null) {
                    add(current)
                    current = children[current.artifact.metadata.id]
                }
            }
            require(ordered.size == artifacts.size) { "Migration history contains a cycle or disconnected chain" }
            val drafts = ordered.filter { it.artifact.metadata.status == MigrationArtifactStatus.Draft }
            require(drafts.size <= 1) { "Migration history contains multiple draft migrations" }
            require(drafts.isEmpty() || drafts.single() == ordered.last()) {
                "A draft migration must be the latest migration"
            }
            return MigrationChain(ordered)
        }
    }
}
