package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class OracleCommentsContractTest {
    @Test
    fun persistsTableAndColumnCommentsAndRemovesThemWithEmptyStrings() {
        val container = System.getenv("KAGIROI_TEST_ORACLE_CONTAINER") ?: return
        fun sql(source: String): String = containerCommentSql(
            container,
            listOf(
                "sqlplus",
                "-s",
                "system/Kagiroi_Test_12345@//localhost/FREEPDB1",
            ),
            "WHENEVER SQLERROR EXIT SQL.SQLCODE\nSET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON\n$source\nEXIT;\n",
        )
        val namespace = "comments_${UUID.randomUUID().toString().replace("-", "") }"
        sql("CREATE USER \"$namespace\" IDENTIFIED BY Kagiroi_Test_12345 QUOTA UNLIMITED ON USERS;")
        try {
            val reference = CanonicalTableReference(namespace, "profiles")
            fun schema(comment: String?): CanonicalDesiredSchema = replaySchemaDeltas(
                listOf(
                    SchemaDelta(
                        listOf(
                            CreateTable(commentFixture(reference, comment).schema.tables.single()),
                        ),
                    ),
                ),
            )
            var previous = schema("Owner's 組織")
            sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), previous), MigrationDialect.Oracle).body)
            sql("INSERT INTO \"$namespace\".\"profiles\" (\"code\") VALUES (23);\nCOMMIT;")
            for (comment in listOf("Owner's 組織", "Changed's 説明", null)) {
                val desired = schema(comment)
                val plan = diffSchema(previous, desired)
                if (plan.operations.isNotEmpty()) sql(generateMigrationScript(plan, MigrationDialect.Oracle).body)
                assertEquals(
                    comment.orEmpty(),
                    sql("SELECT COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER='$namespace' AND TABLE_NAME='profiles';"),
                )
                assertEquals(
                    comment.orEmpty(),
                    sql(
                        "SELECT COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER='$namespace' AND TABLE_NAME='profiles' AND COLUMN_NAME='id';",
                    ),
                )
                assertEquals("7|23", sql("SELECT \"id\" || '|' || \"code\" FROM \"$namespace\".\"profiles\";"))
                previous = desired
            }
            sql(
                generateMigrationScript(
                    MigrationPlan.Changes(
                        listOf(
                            AddColumn(
                                reference,
                                CanonicalColumn(
                                    "extra",
                                    CanonicalColumnType("kagiroi.int32"),
                                    true,
                                    comment = "Added column",
                                ),
                            ),
                        ),
                    ),
                    MigrationDialect.Oracle,
                ).body,
            )
            assertEquals(
                "Added column",
                sql(
                    "SELECT COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER='$namespace' AND TABLE_NAME='profiles' AND COLUMN_NAME='extra';",
                ),
            )
        } finally {
            sql("DROP USER \"$namespace\" CASCADE;")
        }
    }
}
