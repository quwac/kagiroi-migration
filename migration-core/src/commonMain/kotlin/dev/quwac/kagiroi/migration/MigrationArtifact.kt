@file:Suppress("TooManyFunctions")

package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val MIGRATION_ARTIFACT_FORMAT_VERSION: Int = 1

private const val METADATA_OPEN = "/*\nkagiroi:\n"
private const val METADATA_CLOSE = "*/"

internal val migrationArtifactJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
    prettyPrintIndent = "  "
    classDiscriminator = "type"
}

@Serializable
enum class MigrationStepKind {
    @SerialName("transactional")
    Transactional,

    @SerialName("nonTransactional")
    NonTransactional,
}

/** A final execution checkpoint. Draft artifacts have no steps. */
@Serializable
data class MigrationArtifactStep(
    val index: Int,
    val kind: MigrationStepKind,
    val sha256: String,
    val schemaDelta: SchemaDelta,
) {
    init {
        require(index >= 0) { "Migration step index must not be negative" }
        require(SHA256.matches(sha256)) { "Migration step sha256 must be lowercase SHA-256" }
    }
}

/** Public, versioned metadata embedded in a leading SQL block comment. */
@Serializable
data class MigrationArtifactMetadata(
    val formatVersion: Int = MIGRATION_ARTIFACT_FORMAT_VERSION,
    val dialect: String,
    val steps: List<MigrationArtifactStep> = emptyList(),
) {
    init {
        require(formatVersion == MIGRATION_ARTIFACT_FORMAT_VERSION) {
            "Unsupported Migration Artifact format version: $formatVersion"
        }
        require(MigrationDialect.entries.any { it.id == dialect }) { "Unknown migration dialect: $dialect" }
        require(steps.map(MigrationArtifactStep::index) == steps.indices.toList()) {
            "Migration step indexes must start at zero and be contiguous"
        }
    }
}

/** A raw executable SQL migration plus its parsed Kagiroi header. */
data class MigrationArtifact(val metadata: MigrationArtifactMetadata, val body: String) {
    val isFinalized: Boolean get() = metadata.steps.isNotEmpty()

    fun rewrite(metadata: MigrationArtifactMetadata): String = writeMigrationArtifact(metadata, body)
}

object MigrationId {
    private val filename = Regex("[0-9]{14}_[a-z][a-z0-9_]*\\.sql")
    private val name = Regex("[a-z][a-z0-9_]*")

    fun isValidFilename(value: String): Boolean = filename.matches(value)

    fun fromFilename(value: String): String {
        require(isValidFilename(value)) { "Invalid migration filename: $value" }
        return value.removeSuffix(".sql")
    }

    fun requireValidName(value: String): String {
        require(name.matches(value)) { "Migration name must be lower snake case and start with a letter: $value" }
        return value
    }
}

/** Parses only the leading metadata comment. SQL body parsing is a separate component. */
fun parseMigrationArtifact(text: String): MigrationArtifact {
    val opening = requireNotNull(METADATA_OPEN_PATTERN.find(text)) {
        "Migration artifact must begin with a Kagiroi metadata comment"
    }
    val metadataStart = opening.range.last + 1
    val metadataEnd = text.indexOf(METADATA_CLOSE, metadataStart)
    require(metadataEnd >= 0) { "Migration artifact metadata comment is not closed" }
    val body = text.substring(metadataEnd + METADATA_CLOSE.length).removeHeaderSeparator()
    return MigrationArtifact(
        metadata = migrationArtifactJson.decodeFromString(
            MigrationArtifactMetadata.serializer(),
            text.substring(metadataStart, metadataEnd).trimEnd('\r', '\n'),
        ),
        body = body,
    )
}

/** Writes deterministic JSON metadata while preserving the raw SQL body exactly. */
fun writeMigrationArtifact(metadata: MigrationArtifactMetadata, body: String): String = buildString {
    append(METADATA_OPEN)
    append(migrationArtifactJson.encodeToString(MigrationArtifactMetadata.serializer(), metadata).replace("*/", "*\\/"))
    append('\n')
    append(METADATA_CLOSE)
    append("\n\n")
    append(body)
}

internal fun operationFingerprint(operation: MigrationOperation): String = sha256Hex(
    migrationArtifactJson.encodeToString(MigrationOperation.serializer(), operation),
)

/** Stable SHA-256 for an extracted execution step under the artifact v1 lexical rules. */
fun stepFingerprint(step: ParsedExecutionStep): String = sha256Hex(canonicalizeSql(step.sql, step.dialect))

/** Assigns generated operations to the edited script's extracted execution steps. */
fun finalizeMigrationArtifact(draft: MigrationArtifact, plan: MigrationPlan): MigrationArtifact {
    require(!draft.isFinalized) { "Migration artifact is already finalized" }
    val dialect = MigrationDialect.parse(draft.metadata.dialect)
    val parsed = MigrationScriptParser.parse(draft.body, dialect)
    require(parsed.steps.isNotEmpty()) { "Migration SQL contains no execution steps" }
    val expected = plan.operations.mapIndexed { index, operation ->
        OperationMarker(index, operationFingerprint(operation)) to operation
    }.toMap()
    val seen = linkedMapOf<OperationMarker, Int>()
    val steps = parsed.steps.mapIndexed { stepIndex, step ->
        val operations = step.operationMarkers.map { marker ->
            val operation = expected[marker]
                ?: throw IllegalArgumentException(
                    "Operation marker $marker does not match the generated migration plan",
                )
            require(seen.put(marker, stepIndex) == null) { "Operation marker $marker appears more than once" }
            operation
        }
        MigrationArtifactStep(stepIndex, step.kind, stepFingerprint(step), SchemaDelta(operations))
    }
    require(seen.keys == expected.keys) { "A generated schema operation marker is missing from the SQL body" }
    return MigrationArtifact(draft.metadata.copy(steps = steps), draft.body)
}

private val SHA256 = Regex("[0-9a-f]{64}")
private val METADATA_OPEN_PATTERN = Regex("^/\\*\\r?\\nkagiroi:\\r?\\n")

private fun String.removeHeaderSeparator(): String = when {
    startsWith("\r\n\r\n") -> drop("\r\n\r\n".length)
    startsWith("\n\n") -> drop("\n\n".length)
    startsWith("\r\n") -> drop("\r\n".length)
    startsWith("\n") -> drop("\n".length)
    else -> this
}
