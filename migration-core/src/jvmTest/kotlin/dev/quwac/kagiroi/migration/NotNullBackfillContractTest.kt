package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NotNullBackfillContractTest {
    @Test
    fun postgresBackfillsExistingRowsBeforeEnforcingConstraintsAndRollsBackIncompleteBackfills() {
        val container = System.getenv("KAGIROI_TEST_POSTGRES_CONTAINER") ?: return
        val namespace = "backfill_${UUID.randomUUID().toString().replace("-", "") }"
        val arguments = listOf("psql", "-X", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At")
        fun sql(source: String): String = containerCommentSql(container, arguments, source)
        sql("CREATE SCHEMA \"$namespace\";")
        try {
            val fixture = notNullBackfillFixture(namespace)
            sql(
                generateMigrationScript(
                    diffSchema(emptyCanonicalSchema(), fixture.previous),
                    MigrationDialect.PostgreSql,
                ).body,
            )
            sql("INSERT INTO \"$namespace\".a_children VALUES (1), (2);")
            val hint = fixture.desired.evolution.notNullBackfills.single()
            val broken = hint.copy(statements = hint.statements.filterIsInstance<CanonicalBackfillInsert>())
            val invalid = fixture.desired.copy(evolution = CanonicalEvolution(notNullBackfills = listOf(broken)))
            val source = generateMigrationScript(
                diffSchema(fixture.previous, invalid),
                MigrationDialect.PostgreSql,
            ).body
            val process = ProcessBuilder(
                listOf("docker", "exec", "-i", container) + arguments,
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write(source) }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertNotEquals(0, process.waitFor(), output)
            assertEquals(
                "0",
                sql(
                    "SELECT count(*) FROM information_schema.columns WHERE table_schema='$namespace' AND table_name='a_children' AND column_name='parent_id';",
                ),
            )
            assertEquals("", sql("SELECT to_regclass('$namespace.z_parents');"))
            sql(
                generateMigrationScript(
                    diffSchema(fixture.previous, fixture.desired),
                    MigrationDialect.PostgreSql,
                ).body,
            )
            assertEquals("1|7\n2|7", sql("SELECT id || '|' || parent_id FROM \"$namespace\".a_children ORDER BY id;"))
            assertEquals(
                "NO|",
                sql(
                    "SELECT is_nullable || '|' || coalesce(column_default, '') FROM information_schema.columns WHERE table_schema='$namespace' AND table_name='a_children' AND column_name='parent_id';",
                ),
            )
            assertEquals("7", sql("SELECT id FROM \"$namespace\".z_parents;"))
            assertEquals(
                "2",
                sql(
                    "SELECT count(*) FROM pg_constraint WHERE connamespace='$namespace'::regnamespace AND conname IN ('child_parent_fk','child_parent_unique');",
                ),
            )
        } finally {
            sql("DROP SCHEMA \"$namespace\" CASCADE;")
        }
    }

    @Test
    fun mysqlAndMariaDbPreserveTheFinalDefaultAndComment() {
        for ((dialect, environment, client) in listOf(
            Triple(MigrationDialect.MySql, "KAGIROI_TEST_MYSQL_CONTAINER", "mysql"),
            Triple(MigrationDialect.MariaDb, "KAGIROI_TEST_MARIADB_CONTAINER", "mariadb"),
        )) {
            val container = System.getenv(environment) ?: continue
            val namespace = "backfill_${UUID.randomUUID().toString().replace("-", "") }"
            fun sql(source: String): String = containerCommentSql(
                container,
                listOf(client, "-uroot", "-pkagiroi-test", "--raw", "-N", "-B"),
                source,
            ).lineSequence().filterNot { it.startsWith("mysql: [Warning]") }.joinToString("\n").trim()
            sql("CREATE DATABASE `$namespace`;")
            try {
                val fixture = notNullBackfillFixture(namespace, backfillInteger(9))
                sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), fixture.previous), dialect).body)
                sql("INSERT INTO `$namespace`.a_children VALUES (1), (2);")
                sql(generateMigrationScript(diffSchema(fixture.previous, fixture.desired), dialect).body)
                assertEquals(
                    "1|7\n2|7",
                    sql("SELECT concat(id,'|',parent_id) FROM `$namespace`.a_children ORDER BY id;"),
                )
                assertEquals(
                    "NO|9|Parent ID",
                    sql(
                        "SELECT concat(IS_NULLABLE,'|',COLUMN_DEFAULT,'|',COLUMN_COMMENT) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='a_children' AND COLUMN_NAME='parent_id';",
                    ),
                )
                sql(
                    "INSERT INTO `$namespace`.z_parents VALUES (9); INSERT INTO `$namespace`.a_children (id) VALUES (3);",
                )
                assertEquals("9", sql("SELECT parent_id FROM `$namespace`.a_children WHERE id=3;"))
            } finally {
                sql("DROP DATABASE `$namespace`;")
            }
        }
    }

    @Test
    fun sqlServerUsesTypedAlterColumnAndANamedDefaultConstraint() {
        val container = System.getenv("KAGIROI_TEST_SQLSERVER_CONTAINER") ?: return
        fun sql(source: String): String = containerCommentSql(
            container,
            listOf(
                "/opt/mssql-tools18/bin/sqlcmd", "-C", "-S", "localhost", "-U", "sa", "-P",
                "Kagiroi_Test_12345!", "-b", "-h", "-1", "-W",
            ),
            "SET NOCOUNT ON;\nGO\n$source\nGO\n",
        )
        val namespace = "backfill_${UUID.randomUUID().toString().replace("-", "") }"
        sql("CREATE SCHEMA [$namespace];")
        try {
            val fixture = notNullBackfillFixture(namespace, backfillInteger(9))
            sql(
                generateMigrationScript(
                    diffSchema(emptyCanonicalSchema(), fixture.previous),
                    MigrationDialect.SqlServer,
                ).body,
            )
            sql("INSERT INTO [$namespace].a_children VALUES (1), (2);")
            sql(generateMigrationScript(diffSchema(fixture.previous, fixture.desired), MigrationDialect.SqlServer).body)
            assertEquals("1|7\n2|7", sql("SELECT concat(id,'|',parent_id) FROM [$namespace].a_children ORDER BY id;"))
            assertEquals(
                "0",
                sql(
                    "SELECT is_nullable FROM sys.columns WHERE object_id=OBJECT_ID(N'[$namespace].a_children') AND name='parent_id';",
                ),
            )
            sql("INSERT INTO [$namespace].z_parents VALUES (9); INSERT INTO [$namespace].a_children (id) VALUES (3);")
            assertEquals("9", sql("SELECT parent_id FROM [$namespace].a_children WHERE id=3;"))
        } finally {
            sql(
                "DROP TABLE [$namespace].a_children; IF OBJECT_ID(N'[$namespace].z_parents') IS NOT NULL DROP TABLE [$namespace].z_parents; DROP SCHEMA [$namespace];",
            )
        }
    }

    @Test
    fun oracleBackfillsBeforeEnforcingNotNull() {
        val container = System.getenv("KAGIROI_TEST_ORACLE_CONTAINER") ?: return
        fun sql(source: String): String = containerCommentSql(
            container,
            listOf("sqlplus", "-s", "system/Kagiroi_Test_12345@//localhost/FREEPDB1"),
            "WHENEVER SQLERROR EXIT SQL.SQLCODE\nSET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON\n$source\nEXIT;\n",
        )
        val namespace = "backfill_${UUID.randomUUID().toString().replace("-", "") }"
        sql("CREATE USER \"$namespace\" IDENTIFIED BY Kagiroi_Test_12345 QUOTA UNLIMITED ON USERS;")
        try {
            val fixture = notNullBackfillFixture(namespace, backfillInteger(9))
            sql(
                generateMigrationScript(
                    diffSchema(emptyCanonicalSchema(), fixture.previous),
                    MigrationDialect.Oracle,
                ).body,
            )
            sql("INSERT INTO \"$namespace\".\"a_children\" VALUES (1);\nCOMMIT;")
            sql(generateMigrationScript(diffSchema(fixture.previous, fixture.desired), MigrationDialect.Oracle).body)
            assertEquals("1|7", sql("SELECT \"id\" || '|' || \"parent_id\" FROM \"$namespace\".\"a_children\";"))
            assertEquals(
                "N",
                sql(
                    "SELECT NULLABLE FROM ALL_TAB_COLUMNS WHERE OWNER='$namespace' AND TABLE_NAME='a_children' AND COLUMN_NAME='parent_id';",
                ),
            )
            sql(
                "INSERT INTO \"$namespace\".\"z_parents\" VALUES (9);\nINSERT INTO \"$namespace\".\"a_children\" (\"id\") VALUES (3);\nCOMMIT;",
            )
            assertEquals("9", sql("SELECT \"parent_id\" FROM \"$namespace\".\"a_children\" WHERE \"id\"=3;"))
        } finally {
            sql("DROP USER \"$namespace\" CASCADE;")
        }
    }
}
