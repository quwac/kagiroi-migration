package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostgresArrayMigrationTest {
    @Test
    fun rendersArrayPoliciesAndRoundTripsTheirArtifact() {
        val column = CanonicalColumnExpression(
            CanonicalColumnReference(CanonicalTableReference(null, "documents"), "tenant_id"),
        )
        val boolean = CanonicalColumnType("kagiroi.boolean")
        val setting = CanonicalFunctionExpression(
            "postgresCurrentSetting",
            listOf(
                CanonicalLiteralExpression(
                    CanonicalStringValue("app.tenant_ids"),
                    CanonicalColumnType("kagiroi.string"),
                ),
            ),
        )
        val condition = CanonicalValueComparison(
            CanonicalFunctionExpression(
                "postgresAnyEquals",
                listOf(column, CanonicalCastExpression(setting, arrayType("UUID[]"))),
            ),
            CanonicalComparisonOperator.Eq,
            CanonicalBooleanValue(true),
            boolean,
        )
        val table = CanonicalTable(
            name = "documents",
            columns = listOf(CanonicalColumn("tenant_id", CanonicalColumnType("kagiroi.uuid"), false)),
        )
        val rls = CanonicalPostgreSqlPolicy(
            CanonicalSecurityIdentity(name = "documents", policyName = "memberships"),
            CanonicalPostgreSqlPolicyDefinition(using = condition, withCheck = condition),
        )
        val plan = MigrationPlan.Changes(listOf(CreateTable(table), CreatePostgresPolicy(rls)))
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        assertTrue(script.body.contains("= ANY (CAST(current_setting('app.tenant_ids') AS UUID[]))"), script.body)
        val artifact = createMigrationArtifact(script.body, MigrationDialect.PostgreSql, plan)
        assertEquals(artifact, parseMigrationArtifact(writeMigrationArtifact(artifact.metadata, artifact.body)))
    }

    @Test
    fun rejectsNonPostgresAndUnvalidatedArrayDeclarations() {
        fun plan(type: CanonicalColumnType) = MigrationPlan.Changes(
            listOf(
                CreateTable(CanonicalTable(name = "arrays", columns = listOf(CanonicalColumn("values", type, false)))),
            ),
        )
        assertFailsWith<MigrationSqlGenerationException> {
            generateMigrationScript(plan(arrayType("UUID[]")), MigrationDialect.Sqlite)
        }
        for (value in listOf("UUID[]); DROP TABLE documents; --", "UUID", "TEXT[];SELECT 1", "unknown[]")) {
            assertFailsWith<MigrationSqlGenerationException> {
                generateMigrationScript(plan(arrayType(value)), MigrationDialect.PostgreSql)
            }
        }
        assertTrue(
            generateMigrationScript(
                plan(arrayType("INTEGER[]")),
                MigrationDialect.PostgreSql,
            ).body.contains("INTEGER[]"),
        )
    }

    private fun arrayType(declaration: String) = CanonicalColumnType(
        "kagiroi.postgres.declared",
        listOf(CanonicalTypeArgument("declaration", declaration)),
    )
}
