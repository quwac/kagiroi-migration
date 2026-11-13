package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlServerCommentsContractTest {
    @Test
    fun persistsAllObjectCommentsInDefaultAndExplicitSchemasWithoutRebuildingObjects() {
        val container = System.getenv("KAGIROI_TEST_SQLSERVER_CONTAINER") ?: return
        fun sql(source: String): String = containerCommentSql(
            container,
            listOf(
                "/opt/mssql-tools18/bin/sqlcmd", "-C", "-S", "localhost", "-U", "sa", "-P", "Kagiroi_Test_12345!",
                "-b", "-h", "-1", "-W", "-f", "65001",
            ),
            "SET NOCOUNT ON;\nGO\n$source\nGO\n",
        )
        val namespace = "comments_${UUID.randomUUID().toString().replace("-", "") }"
        sql("CREATE SCHEMA [$namespace];")
        try {
            verifiesScenario(CanonicalTableReference(namespace, "profiles"), ::sql)
            verifiesScenario(CanonicalTableReference(name = namespace), ::sql)
        } finally {
            sql(
                "DROP SCHEMA [$namespace]; IF OBJECT_ID(N'[dbo].[$namespace]') IS NOT NULL DROP TABLE [dbo].[$namespace];",
            )
        }
    }

    /** Verifies persisted descriptions, stable object IDs, and rows through creation, updates, and removal. */
    private fun verifiesScenario(reference: CanonicalTableReference, sql: (String) -> String) {
        val qualified = "[${reference.schemaName ?: "dbo"}].[${reference.name}]"
        val objectId = "OBJECT_ID(N'$qualified')"
        val constraintId = "(SELECT object_id FROM sys.key_constraints " +
            "WHERE parent_object_id=$objectId AND name='profiles_code_unique')"
        val indexId = "(SELECT index_id FROM sys.indexes WHERE object_id=$objectId AND name='profiles_code_lookup')"
        val comments = "SELECT concat(${property(1, objectId, "0")},N'|'," +
            "${property(1, objectId, "COLUMNPROPERTY($objectId,'id','ColumnId')")},N'|'," +
            "${property(1, constraintId, "0")},N'|',${property(7, objectId, indexId)});"
        val identities = "SELECT concat($objectId,'|',$constraintId,'|',$indexId);"
        var previous = schema(reference, "Owner's 組織")
        sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), previous), MigrationDialect.SqlServer).body)
        sql("INSERT INTO $qualified (code) VALUES (23);")
        val originalIds = sql(identities)
        for (comment in listOf("Owner's 組織", "Updated's 説明", null)) {
            val desired = schema(reference, comment)
            val plan = diffSchema(previous, desired)
            if (plan.operations.isNotEmpty()) sql(generateMigrationScript(plan, MigrationDialect.SqlServer).body)
            assertEquals(List(4) { comment.orEmpty() }.joinToString("|"), sql(comments))
            assertEquals(originalIds, sql(identities))
            assertEquals("7|23", sql("SELECT concat(id,'|',code) FROM $qualified;"))
            previous = desired
        }
        verifiesAddedComments(reference, objectId, sql)
        sql("DROP TABLE $qualified;")
    }

    private fun property(kind: Int, major: String, minor: String): String =
        "coalesce((SELECT convert(nvarchar(4000), value) FROM sys.extended_properties " +
            "WHERE class=$kind AND major_id=$major AND minor_id=$minor AND name=N'MS_Description'),N'')"

    private fun verifiesAddedComments(reference: CanonicalTableReference, objectId: String, sql: (String) -> String) {
        val column = CanonicalColumn("extra", CanonicalColumnType("kagiroi.int32"), true, comment = "Added column")
        val unique = CanonicalUniqueConstraint("profiles_extra_unique", listOf("extra"), "Added unique")
        val plan = MigrationPlan.Changes(listOf(AddColumn(reference, column), AddUnique(reference, unique)))
        sql(generateMigrationScript(plan, MigrationDialect.SqlServer).body)
        val constraintId = "(SELECT object_id FROM sys.key_constraints " +
            "WHERE parent_object_id=$objectId AND name='profiles_extra_unique')"
        assertEquals(
            "Added column|Added unique",
            sql(
                "SELECT concat(${property(1, objectId, "COLUMNPROPERTY($objectId,'extra','ColumnId')")},'|'," +
                    "${property(1, constraintId, "0")});",
            ),
        )
    }

    private fun schema(reference: CanonicalTableReference, comment: String?): CanonicalDesiredSchema {
        val base = commentFixture(reference, comment)
        val physical = base.schema.copy(
            tables = base.schema.tables.map { table ->
                table.copy(uniqueConstraints = table.uniqueConstraints.map { it.copy(comment = comment) })
            },
            indexes = base.schema.indexes.map { it.copy(comment = comment) },
        )
        return base.copy(
            schema = physical,
            metadata = CanonicalSchemaMetadata(
                CanonicalSchemaHash(CanonicalHashAlgorithm.Sha256, physicalSchemaHash(physical)),
            ),
        )
    }
}
