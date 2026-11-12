package dev.quwac.kagiroi.migration.cli

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MigrationConfigurationTest {
    @Test
    fun rejectsConnectionVariableNameOverrides() {
        assertFailsWith<SerializationException> {
            MigrationCliConfiguration.json.decodeFromString<MigrationCliConfiguration>(
                """{"targets":{"default":{"provider":"postgresql","connectionStringEnv":"CUSTOM_URL"}}}""",
            )
        }
    }

    @Test
    fun rejectsProviderCredentialVariableNameOverrides() {
        listOf("userEnv", "passwordEnv").forEach { field ->
            assertFailsWith<SerializationException> {
                MigrationCliConfiguration.json.decodeFromString<OracleTargetConfig>(
                    """{"connectString":"localhost:1521/FREEPDB1","$field":"CUSTOM_VALUE"}""",
                )
            }
        }
        assertFailsWith<SerializationException> {
            MigrationCliConfiguration.json.decodeFromString<D1TargetConfig>(
                """{"accountId":"synthetic","databaseId":"synthetic","apiTokenEnv":"CUSTOM_TOKEN"}""",
            )
        }
    }
}
