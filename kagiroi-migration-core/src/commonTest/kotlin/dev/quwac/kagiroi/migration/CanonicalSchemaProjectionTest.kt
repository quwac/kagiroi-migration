package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CanonicalSchemaProjectionTest {
    @Test
    fun createsCanonicalMetadataFromTheSchemaProjection() {
        val schema = canonicalizeSchemaProjection(projectionJson(formatVersion = 1), expectedFormatVersion = 1)

        assertEquals(CANONICAL_SCHEMA_FORMAT_VERSION, schema.formatVersion)
        assertEquals(CanonicalPhysicalSchema(), schema.schema)
        assertEquals(physicalSchemaHash(schema), schema.metadata.physicalSchemaHash.value)
    }

    @Test
    fun rejectsAnUnsupportedSchemaProjectionVersion() {
        assertFailsWith<IllegalArgumentException> {
            canonicalizeSchemaProjection(projectionJson(formatVersion = 2), expectedFormatVersion = 1)
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
