@file:Suppress("TooManyFunctions")

package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Version of the persisted SQL migration artifact header contract. */
const val MIGRATION_ARTIFACT_FORMAT_VERSION: Int = 1

private const val METADATA_OPEN = "/*\nkagiroi:\n"
private const val METADATA_CLOSE = "*/"

private val migrationArtifactJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
    prettyPrintIndent = "  "
    classDiscriminator = "kind"
}

/** The lifecycle state recorded in an SQL migration artifact. */
@Serializable
enum class MigrationArtifactStatus {
    @SerialName("draft")
    Draft,

    @SerialName("final")
    Final,
}

/** The concise risk record carried by an artifact, without duplicating every plan operation. */
@Serializable
data class MigrationArtifactRisk(val highest: MigrationRisk, val reviewRequired: Boolean)

/**
 * The machine-readable history stored in a leading block comment in a raw SQL migration file.
 *
 * [resultSchema] is deliberately a physical schema only. Evolution hints belong to current Kotlin
 * definitions and must never be made historical database state.
 */
@Serializable
data class MigrationArtifactMetadata(
    val formatVersion: Int = MIGRATION_ARTIFACT_FORMAT_VERSION,
    val id: String,
    val parent: String? = null,
    val dialect: String,
    val status: MigrationArtifactStatus,
    val desiredSchemaHashAtGeneration: String? = null,
    val sqlSha256: String? = null,
    val resultSchemaHash: String? = null,
    val resultSchema: CanonicalPhysicalSchema? = null,
    val risk: MigrationArtifactRisk,
) {
    init {
        require(formatVersion == MIGRATION_ARTIFACT_FORMAT_VERSION) {
            "Unsupported Migration Artifact format version: $formatVersion"
        }
        require(MigrationId.isValid(id)) { "Invalid migration id: $id" }
        require(parent == null || MigrationId.isValid(parent)) { "Invalid migration parent: $parent" }
        require(dialect.isNotBlank()) { "Migration dialect must not be blank" }
        when (status) {
            MigrationArtifactStatus.Draft -> {
                require(desiredSchemaHashAtGeneration != null) {
                    "Draft migration metadata requires desiredSchemaHashAtGeneration"
                }
                require(sqlSha256 == null && resultSchemaHash == null && resultSchema == null) {
                    "Draft migration metadata cannot contain finalized fields"
                }
            }

            MigrationArtifactStatus.Final -> {
                require(sqlSha256 != null) { "Final migration metadata requires sqlSha256" }
                require(resultSchemaHash != null && resultSchema != null) {
                    "Final migration metadata requires resultSchemaHash and resultSchema"
                }
            }
        }
    }
}

/** A parsed SQL artifact. [body] is raw executable SQL and is never parsed by Kagiroi. */
data class MigrationArtifact(val metadata: MigrationArtifactMetadata, val body: String) {
    /** SHA-256 input: body UTF-8 with every line ending normalized to LF. */
    fun sqlSha256(): String = sha256Hex(normalizeSqlBody(body))

    fun validateChecksum(): MigrationChecksumValidation = when (metadata.status) {
        MigrationArtifactStatus.Draft -> MigrationChecksumValidation.NotFinalized

        MigrationArtifactStatus.Final -> {
            val expected = requireNotNull(metadata.sqlSha256)
            val actual = sqlSha256()
            if (expected == actual) {
                MigrationChecksumValidation.Valid
            } else {
                MigrationChecksumValidation.Mismatch(
                    expected,
                    actual,
                )
            }
        }
    }

    /** Replaces only the leading metadata comment and preserves [body] byte-for-byte. */
    fun rewrite(metadata: MigrationArtifactMetadata): String = writeMigrationArtifact(metadata, body)
}

sealed interface MigrationChecksumValidation {
    data object NotFinalized : MigrationChecksumValidation

    data object Valid : MigrationChecksumValidation

    data class Mismatch(val expected: String, val actual: String) : MigrationChecksumValidation
}

/** Stable filesystem migration ID helpers. */
object MigrationId {
    private val pattern = Regex("[0-9]{14}_[a-z][a-z0-9_]*")

    fun isValid(value: String): Boolean = pattern.matches(value)

    fun requireValidName(value: String): String {
        require(Regex("[a-z][a-z0-9_]*").matches(value)) {
            "Migration name must be lower snake case and start with a letter: $value"
        }
        return value
    }
}

/** Parses only the mandatory leading Kagiroi metadata comment, never the SQL body. */
fun parseMigrationArtifact(text: String): MigrationArtifact {
    require(text.startsWith(METADATA_OPEN)) {
        "Migration artifact must begin with a Kagiroi metadata comment"
    }
    val metadataStart = METADATA_OPEN.length
    val metadataEnd = text.indexOf("\n$METADATA_CLOSE", metadataStart)
    require(metadataEnd >= 0) { "Migration artifact metadata comment is not closed" }
    val metadataJson = text.substring(metadataStart, metadataEnd)
    val bodyStart = metadataEnd + METADATA_CLOSE.length + 1
    val body = text.substring(bodyStart).removePrefix("\n\n").removePrefix("\n")
    return MigrationArtifact(
        migrationArtifactJson.decodeFromString(MigrationArtifactMetadata.serializer(), metadataJson),
        body,
    )
}

/**
 * Writes a deterministic leading metadata comment. A slash is escaped when it follows `*`, so
 * user-provided schema comments can never terminate the SQL comment early.
 */
fun writeMigrationArtifact(metadata: MigrationArtifactMetadata, body: String): String {
    val metadataJson = migrationArtifactJson.encodeToString(metadata).replace("*/", "*\\/")
    return buildString {
        append(METADATA_OPEN)
        append(metadataJson)
        append('\n')
        append(METADATA_CLOSE)
        append("\n\n")
        append(body)
    }
}

/** Uses a single LF representation for all checksum calculations while retaining artifact body text. */
fun normalizeSqlBody(body: String): String = body.replace("\r\n", "\n").replace('\r', '\n')

/** Builds the artifact risk summary from a non-empty migration plan. */
fun MigrationPlan.artifactRisk(): MigrationArtifactRisk {
    val highest = summary.highestRisk ?: MigrationRisk.Safe
    return MigrationArtifactRisk(highest = highest, reviewRequired = highest != MigrationRisk.Safe)
}

/** Returns the previous physical schema represented by a final artifact. */
fun MigrationArtifactMetadata.resultSchemaDocument(): CanonicalDesiredSchema {
    require(status == MigrationArtifactStatus.Final) { "Only finalized migrations have a result schema" }
    val physical = requireNotNull(resultSchema)
    val expectedHash = requireNotNull(resultSchemaHash)
    val actualHash = sha256Hex(encodePhysicalSchema(physical))
    require(expectedHash == actualHash) { "Migration resultSchemaHash does not match resultSchema" }
    return CanonicalDesiredSchema(
        formatVersion = CANONICAL_SCHEMA_FORMAT_VERSION,
        schema = physical,
        metadata = CanonicalSchemaMetadata(CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, actualHash)),
    )
}

/** Finalizes an artifact without inspecting or interpreting its SQL body. */
fun finalizeMigrationArtifact(draft: MigrationArtifact, currentSchema: CanonicalDesiredSchema): MigrationArtifact {
    require(draft.metadata.status == MigrationArtifactStatus.Draft) { "Only a draft migration can be finalized" }
    val currentHash = physicalSchemaHash(currentSchema)
    require(draft.metadata.desiredSchemaHashAtGeneration == currentHash) {
        "Current schema differs from the schema used to generate draft ${draft.metadata.id}; regenerate the migration"
    }
    val metadata = draft.metadata.copy(
        status = MigrationArtifactStatus.Final,
        sqlSha256 = draft.sqlSha256(),
        resultSchemaHash = currentHash,
        resultSchema = currentSchema.schema,
    )
    return MigrationArtifact(metadata, draft.body)
}
