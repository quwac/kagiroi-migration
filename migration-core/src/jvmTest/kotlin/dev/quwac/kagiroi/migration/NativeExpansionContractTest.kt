@file:Suppress("LongMethod")

package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeExpansionContractTest {
    @Test
    fun mysqlAndMariaDbHistoryCommentsAndInlineEnumsPreserveExistingRows() {
        for ((dialect, environment, client) in listOf(
            Triple(MigrationDialect.MySql, "KAGIROI_TEST_MYSQL_CONTAINER", "mysql"),
            Triple(MigrationDialect.MariaDb, "KAGIROI_TEST_MARIADB_CONTAINER", "mariadb"),
        )) {
            val container = System.getenv(environment) ?: continue
            val namespace = "expand_${UUID.randomUUID().toString().replace("-", "") }"
            fun sql(source: String) = containerCommentSql(
                container,
                listOf(client, "-uroot", "-pkagiroi-test", "-N", "-B", "--raw"),
                source,
            )
                .lineSequence().filterNot { it.startsWith("mysql: [Warning]") }.joinToString("\n").trim()
            sql("CREATE DATABASE `$namespace`; ")
            try {
                fun run(source: String) = sql("USE `$namespace`; $source")
                run(
                    MigrationHistoryStatements(
                        MigrationHistoryDialect.MySql,
                    ).createTable + "; INSERT INTO kagiroi_migrations VALUES ('kept',0,'hash','applied',NULL);",
                )
                repeat(2) { run(migrationHistoryComments(dialect).joinToString(";\n", postfix = ";")) }
                assertEquals(
                    "kept|hash|applied",
                    run("SELECT concat(migration_id,'|',step_sha256,'|',status) FROM kagiroi_migrations;"),
                )
                assertEquals(
                    "5",
                    run(
                        "SELECT count(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='kagiroi_migrations' AND COLUMN_COMMENT <> '';",
                    ),
                )
                val reference = CanonicalTableReference(namespace, "events")
                val column = CanonicalColumn(
                    "kind",
                    mysqlEnumType("ENUM('in', 'out')"),
                    true,
                    comment = "Kind",
                    default = CanonicalLiteralExpression(
                        CanonicalStringValue("in"),
                        CanonicalColumnType("kagiroi.string"),
                    ),
                )
                fun desired(value: CanonicalColumn) = replaySchemaDeltas(
                    listOf(
                        SchemaDelta(
                            listOf(CreateTable(CanonicalTable(namespace, reference.name, columns = listOf(value)))),
                        ),
                    ),
                )
                val before = desired(column)
                run(generateMigrationScript(diffSchema(emptyCanonicalSchema(), before), dialect).body)
                run("INSERT INTO events VALUES ('in'),(NULL);")
                val after = desired(column.copy(type = mysqlEnumType("ENUM('in', 'out', 'new')")))
                run(generateMigrationScript(diffSchema(before, after), dialect).body)
                run("INSERT INTO events VALUES ('new'); INSERT INTO events () VALUES ();")
                assertEquals(
                    "in\nin\nnew\nNULL",
                    run("SELECT coalesce(kind,'NULL') FROM events ORDER BY kind IS NULL,kind;"),
                )
                assertEquals(
                    "YES|in|Kind",
                    run(
                        "SELECT concat(IS_NULLABLE,'|',REPLACE(COLUMN_DEFAULT,CHAR(39),''),'|',COLUMN_COMMENT) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='events';",
                    ),
                )
            } finally {
                sql("DROP DATABASE `$namespace`;")
            }
        }
    }

    @Test
    fun oracleHistoryCommentsEnumerationDomainsAndUnnamedKeysExecuteNatively() {
        val container = System.getenv("KAGIROI_TEST_ORACLE_CONTAINER") ?: return
        val namespace = "expand_${UUID.randomUUID().toString().replace("-", "") }"
        fun sql(source: String) = containerCommentSql(
            container,
            listOf("sqlplus", "-s", "system/Kagiroi_Test_12345@//localhost/FREEPDB1"),
            "WHENEVER SQLERROR EXIT FAILURE\nSET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 TRIMSPOOL ON\n$source\nEXIT;\n",
        )
        sql("CREATE USER \"$namespace\" IDENTIFIED BY Kagiroi_Test_12345 QUOTA UNLIMITED ON USERS;")
        try {
            val target = CanonicalTableReference(namespace, "events")
            val domain = CanonicalOracleEnumDomain(
                "state",
                listOf(CanonicalOracleEnumEntry("In", "in"), CanonicalOracleEnumEntry("Out", "out")),
                namespace,
            )
            val type = CanonicalColumnType(
                "kagiroi.oracle.enumDomain",
                listOf(CanonicalTypeArgument("schemaName", namespace), CanonicalTypeArgument("name", "state")),
            )
            val desired = replaySchemaDeltas(
                listOf(
                    SchemaDelta(
                        listOf(
                            CreateOracleEnumDomain(domain),
                            CreateTable(
                                CanonicalTable(
                                    namespace,
                                    "events",
                                    columns = listOf(CanonicalColumn("state", type, false)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), desired), MigrationDialect.Oracle).body)
            sql("INSERT INTO \"$namespace\".\"events\" VALUES ('in');")
            assertEquals("in", sql("SELECT \"state\" FROM \"$namespace\".\"events\";"))
            val legacy = CanonicalTableReference(namespace, "legacy_events")
            val text = CanonicalColumn("state", CanonicalColumnType("kagiroi.string"), false)
            sql(
                generateMigrationScript(
                    MigrationPlan.Changes(
                        listOf(
                            CreateTable(
                                CanonicalTable(namespace, legacy.name, columns = listOf(text)),
                            ),
                        ),
                    ),
                    MigrationDialect.Oracle,
                ).body,
            )
            sql("INSERT INTO \"$namespace\".\"legacy_events\" VALUES ('out');")
            val association = MigrationPlan.Changes(
                listOf(
                    AlterColumnType(
                        CanonicalColumnReference(legacy, "state"),
                        text.type,
                        type,
                        definition = text.copy(type = type),
                    ),
                ),
            )
            sql(generateMigrationScript(association, MigrationDialect.Oracle).body)
            assertEquals("out", sql("SELECT \"state\" FROM \"$namespace\".\"legacy_events\";"))
            sql("DROP TABLE \"$namespace\".\"legacy_events\" PURGE;")
            sql(
                "ALTER SESSION SET CURRENT_SCHEMA=\"$namespace\";\nCREATE TABLE kagiroi_migrations(migration_id VARCHAR2(255),step_index NUMBER(10),step_sha256 VARCHAR2(64),status VARCHAR2(16),applied_at TIMESTAMP WITH TIME ZONE);",
            )
            repeat(
                2,
            ) {
                sql(
                    "ALTER SESSION SET CURRENT_SCHEMA=\"$namespace\";\n" + migrationHistoryComments(
                        MigrationDialect.Oracle,
                    ).joinToString(";\n", postfix = ";"),
                )
            }
            assertEquals(
                "5",
                sql(
                    "SELECT count(*) FROM all_col_comments WHERE owner='$namespace' AND table_name='KAGIROI_MIGRATIONS' AND comments IS NOT NULL;",
                ),
            )
            sql(
                "CREATE TABLE \"$namespace\".\"parent\" (\"id\" NUMBER PRIMARY KEY);\nCREATE TABLE \"$namespace\".\"child\" (\"id\" NUMBER PRIMARY KEY, FOREIGN KEY(\"id\") REFERENCES \"$namespace\".\"parent\"(\"id\"));",
            )
            val child = CanonicalTableReference(namespace, "child")
            val fk = CanonicalForeignKey(
                sourceColumns = listOf("id"),
                targetTable = CanonicalTableReference(namespace, "parent"),
                targetColumns = listOf("id"),
            )
            sql(
                generateMigrationScript(
                    MigrationPlan.Changes(listOf(DropForeignKey(child, fk), DropPrimaryKey(child))),
                    MigrationDialect.Oracle,
                ).body,
            )
            assertEquals(
                "0",
                sql(
                    "SELECT count(*) FROM all_constraints WHERE owner='$namespace' AND table_name='child' AND constraint_type IN ('P','R');",
                ),
            )
            sql(generateMigrationScript(diffSchema(desired, emptyCanonicalSchema()), MigrationDialect.Oracle).body)
        } finally {
            sql("DROP USER \"$namespace\" CASCADE;")
        }
    }

    @Test
    fun sqlServerHistoryCommentsAndUnnamedKeysPreserveRowsAndRejectAmbiguity() {
        val container = System.getenv("KAGIROI_TEST_SQLSERVER_CONTAINER") ?: return
        val namespace = "expand_${UUID.randomUUID().toString().replace("-", "") }"
        fun run(source: String): Pair<Int, String> {
            val process = ProcessBuilder(
                "docker", "exec", "-i", container, "/opt/mssql-tools18/bin/sqlcmd", "-C", "-S", "localhost",
                "-U", "sa", "-P", "Kagiroi_Test_12345!", "-b", "-h", "-1", "-W", "-f", "65001",
            ).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { it.write("SET NOCOUNT ON;\nGO\n$source\nGO\n") }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            return process.waitFor() to output
        }
        fun sql(source: String): String {
            val (code, output) = run(source)
            assertEquals(0, code, output)
            return output
        }
        sql("CREATE SCHEMA [$namespace];")
        try {
            sql(
                "CREATE TABLE dbo.kagiroi_migrations(migration_id nvarchar(255) NOT NULL,step_index int NOT NULL,step_sha256 nvarchar(64) NOT NULL,status nvarchar(16) NOT NULL,applied_at datetimeoffset NULL); INSERT INTO dbo.kagiroi_migrations VALUES ('kept',0,'hash','applied',NULL);",
            )
            repeat(2) { sql(migrationHistoryComments(MigrationDialect.SqlServer).joinToString(";\n", postfix = ";")) }
            assertEquals(
                "6",
                sql(
                    "SELECT count(*) FROM sys.extended_properties WHERE major_id=OBJECT_ID('dbo.kagiroi_migrations') AND name='MS_Description';",
                ),
            )
            assertEquals("kept|hash", sql("SELECT migration_id+'|'+step_sha256 FROM dbo.kagiroi_migrations;"))
            sql(
                "CREATE TABLE [$namespace].parent(id int PRIMARY KEY); CREATE TABLE [$namespace].child(id int PRIMARY KEY, FOREIGN KEY(id) REFERENCES [$namespace].parent(id)); INSERT INTO [$namespace].parent VALUES(1); INSERT INTO [$namespace].child VALUES(1);",
            )
            val child = CanonicalTableReference(namespace, "child")
            val fk = CanonicalForeignKey(
                sourceColumns = listOf("id"),
                targetTable = CanonicalTableReference(namespace, "parent"),
                targetColumns = listOf("id"),
            )
            fun removal() = generateMigrationScript(
                MigrationPlan.Changes(listOf(DropForeignKey(child, fk))),
                MigrationDialect.SqlServer,
            ).body
            sql(
                generateMigrationScript(
                    MigrationPlan.Changes(listOf(DropForeignKey(child, fk), DropPrimaryKey(child))),
                    MigrationDialect.SqlServer,
                ).body,
            )
            sql("INSERT INTO [$namespace].child VALUES(1);")
            assertTrue(run(removal()).first != 0)
            sql(
                "ALTER TABLE [$namespace].child ADD CONSTRAINT first_fk FOREIGN KEY(id) REFERENCES [$namespace].parent(id); ALTER TABLE [$namespace].child ADD CONSTRAINT second_fk FOREIGN KEY(id) REFERENCES [$namespace].parent(id);",
            )
            assertTrue(run(removal()).first != 0)
            assertEquals(
                "2",
                sql("SELECT count(*) FROM sys.foreign_keys WHERE parent_object_id=OBJECT_ID('$namespace.child');"),
            )
            assertEquals("2", sql("SELECT count(*) FROM [$namespace].child;"))
        } finally {
            sql(
                "DROP TABLE IF EXISTS [$namespace].child; DROP TABLE IF EXISTS [$namespace].parent; DROP SCHEMA [$namespace]; DROP TABLE IF EXISTS dbo.kagiroi_migrations;",
            )
        }
    }
}
