package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalSchemaProjectionTest {
    @Test
    fun createsCanonicalMetadataFromTheSchemaProjection() {
        val schema = canonicalizeSchemaProjection(projectionJson(formatVersion = 1))

        assertEquals(CANONICAL_SCHEMA_FORMAT_VERSION, schema.formatVersion)
        assertEquals(CanonicalPhysicalSchema(), schema.schema)
        assertEquals(physicalSchemaHash(schema), schema.metadata.physicalSchemaHash.value)
        assertEquals(schema, decodeCanonicalSchema(encodeCanonicalSchema(schema)))
    }

    @Test
    fun rejectsAnUnsupportedSchemaProjectionVersion() {
        for (version in listOf(0, 2, 3, 4, 5, 6, 7)) {
            assertFailsWith<IllegalArgumentException> { canonicalizeSchemaProjection(projectionJson(version)) }
        }
    }

    @Test
    fun rejectsCanonicalVersionsOtherThanOneWhenEncodingAndDecoding() {
        val schema = canonicalizeSchemaProjection(projectionJson(1))
        val encoded = encodeCanonicalSchema(schema)
        for (version in 2..6) {
            assertFailsWith<IllegalArgumentException> { encodeCanonicalSchema(schema.copy(formatVersion = version)) }
            assertFailsWith<IllegalArgumentException> {
                decodeCanonicalSchema(encoded.replace("\"formatVersion\":1", "\"formatVersion\":$version"))
            }
        }
    }

    private fun projectionJson(formatVersion: Int): String =
        """
        {
          "formatVersion": $formatVersion,
          "schema": {"tables": [], "sequences": [], "indexes": []},
          "evolution": {
            "tableRenames": [],
            "columnRenames": [],
            "indexRenames": [],
            "constraintRenames": [],
            "sequenceRenames": []
          }
        }
        """.trimIndent()
}
