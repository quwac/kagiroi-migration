package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDateNativeContractTest {
    @Test
    @Suppress("CyclomaticComplexMethod")
    fun generatedDateColumnsRoundTripLeapDaysAndNullsAcrossNativeDatabases() {
        for (dialect in MigrationDialect.entries.filterNot { it == MigrationDialect.D1 }) {
            val container = System.getenv("KAGIROI_TEST_${dialect.environmentName()}_CONTAINER")
            val available = if (dialect == MigrationDialect.Sqlite) {
                System.getenv("KAGIROI_TEST_SQLITE_PYTHON") != null
            } else {
                container != null
            }
            if (!available) continue
            val name = "dates_${UUID.randomUUID().toString().replace("-", "") }"
            val quoted = when (dialect) {
                MigrationDialect.MySql, MigrationDialect.MariaDb -> "`$name`"
                MigrationDialect.SqlServer -> "[$name]"
                else -> "\"$name\""
            }
            val column = CanonicalColumn("day", CanonicalColumnType("kagiroi.kotlinx.localDate"), true)
            val table = CanonicalTable(name = name, columns = listOf(column))
            val creation = generateMigrationScript(MigrationPlan.Changes(listOf(CreateTable(table))), dialect).body
            val day = if (dialect == MigrationDialect.SqlServer) {
                "[day]"
            } else if (dialect in listOf(
                    MigrationDialect.MySql,
                    MigrationDialect.MariaDb,
                )
            ) {
                "`day`"
            } else {
                "\"day\""
            }
            val leapDay = if (dialect == MigrationDialect.Oracle) "DATE '2024-02-29'" else "'2024-02-29'"
            val select = when (dialect) {
                MigrationDialect.PostgreSql ->
                    "SELECT COALESCE(CAST($day AS TEXT),'missing') FROM $quoted " +
                        "ORDER BY $day NULLS LAST;"

                MigrationDialect.Oracle ->
                    "SELECT COALESCE(TO_CHAR($day,'YYYY-MM-DD'),'missing') FROM $quoted " +
                        "ORDER BY $day NULLS LAST;"

                MigrationDialect.SqlServer ->
                    "SELECT COALESCE(CONVERT(varchar(10),$day,23),'missing') FROM $quoted " +
                        "ORDER BY CASE WHEN $day IS NULL THEN 1 ELSE 0 END,$day;"

                else -> "SELECT COALESCE($day,'missing') FROM $quoted ORDER BY $day IS NULL,$day;"
            }
            val source =
                creation + "\nINSERT INTO $quoted VALUES ($leapDay);\nINSERT INTO $quoted VALUES (NULL);\n" + select +
                    "\nDROP TABLE $quoted;\n"
            val output = executeDateSql(dialect, container, source)
            assertEquals(
                listOf("2024-02-29", "missing"),
                output.lineSequence().map { it.trim() }
                    .filter { it == "2024-02-29" || it == "missing" }.toList(),
                dialect.id,
            )
        }
    }
}

private fun MigrationDialect.environmentName(): String = when (this) {
    MigrationDialect.PostgreSql -> "POSTGRES"
    MigrationDialect.MySql -> "MYSQL"
    MigrationDialect.MariaDb -> "MARIADB"
    MigrationDialect.SqlServer -> "SQLSERVER"
    MigrationDialect.Oracle -> "ORACLE"
    else -> "SQLITE"
}

private fun executeDateSql(dialect: MigrationDialect, container: String?, source: String): String {
    var input = source
    val command = when (dialect) {
        MigrationDialect.PostgreSql -> listOf(
            "psql", "-X", "-q", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-At",
        )

        MigrationDialect.MySql, MigrationDialect.MariaDb -> listOf(
            if (dialect == MigrationDialect.MySql) "mysql" else "mariadb",
            "-uroot",
            "-pkagiroi-test",
            "-N",
            "-B",
            "--raw",
        ).also {
            input = "CREATE DATABASE IF NOT EXISTS date_contract; USE date_contract;\n$source"
        }

        MigrationDialect.Oracle -> listOf("sqlplus", "-s", "system/Kagiroi_Test_12345@//localhost/FREEPDB1").also {
            input = "WHENEVER SQLERROR EXIT FAILURE\nSET HEADING OFF FEEDBACK OFF PAGESIZE 0\n$source\nEXIT;\n"
        }

        MigrationDialect.SqlServer -> listOf(
            "/opt/mssql-tools18/bin/sqlcmd", "-C", "-S", "localhost", "-U", "sa", "-P",
            "Kagiroi_Test_12345!", "-b", "-h", "-1", "-W",
        ).also {
            input = "SET NOCOUNT ON;\nGO\n$source\nGO\n"
        }

        else -> listOf(
            System.getenv("KAGIROI_TEST_SQLITE_PYTHON") ?: "python3",
            "-c",
            "import sqlite3,sys; c=sqlite3.connect(':memory:'); source=sys.stdin.read();\nfor s in source.split(';'):\n if s.strip():\n  for r in c.execute(s): print(r[0])",
        )
    }
    val process = ProcessBuilder(
        if (container == null) command else listOf("docker", "exec", "-i", container) + command,
    )
        .redirectErrorStream(true).start()
    process.outputStream.bufferedWriter().use { it.write(input) }
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    assertEquals(0, process.waitFor(), output)
    return output
}
