package dev.quwac.kagiroi.migration

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Uses the canonical AST discriminator inside security clauses, independent of artifact operation tags. */
internal object SecurityPredicateSerializer : KSerializer<CanonicalSchemaPredicate> {
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
    }

    // Expose the AST so artifact compaction can traverse its optional arguments.
    override val descriptor: SerialDescriptor = CanonicalSchemaPredicate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: CanonicalSchemaPredicate) {
        require(encoder is JsonEncoder) { "Security predicates require JSON serialization" }
        encoder.encodeJsonElement(json.encodeToJsonElement(CanonicalSchemaPredicate.serializer(), value))
    }

    override fun deserialize(decoder: Decoder): CanonicalSchemaPredicate {
        require(decoder is JsonDecoder) { "Security predicates require JSON serialization" }
        return json.decodeFromJsonElement(CanonicalSchemaPredicate.serializer(), decoder.decodeJsonElement())
    }
}
