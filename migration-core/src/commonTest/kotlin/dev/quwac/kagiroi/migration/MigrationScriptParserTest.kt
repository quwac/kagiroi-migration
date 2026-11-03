package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MigrationScriptParserTest {
    @Test
    fun postgres_dollar_quoted_function_stays_in_one_transactional_step() {
        val script = """
            BEGIN;
            CREATE FUNCTION f() RETURNS void AS \$\$
            BEGIN
              PERFORM 'semicolon; inside function';
            END;
            \$\$ LANGUAGE plpgsql;
            COMMIT;
        """.trimIndent()

        val parsed = MigrationScriptParser.parse(script, MigrationDialect.PostgreSql)

        assertEquals(1, parsed.steps.size)
        assertEquals(MigrationStepKind.Transactional, parsed.steps.single().kind)
    }

    @Test
    fun mysql_delimiter_is_not_sent_as_sql_and_procedure_is_one_unit() {
        val script = """
            DELIMITER //
            CREATE PROCEDURE p()
            BEGIN
              SELECT 'semicolon; inside string';
            END//
            DELIMITER ;
        """.trimIndent()

        val parsed = MigrationScriptParser.parse(script, MigrationDialect.MySql)

        assertEquals(1, parsed.steps.size)
        assertEquals(MigrationStepKind.NonTransactional, parsed.steps.single().kind)
        assertEquals(1, parsed.steps.single().executionUnits.size)
        assertEquals(false, parsed.steps.single().sql.contains("DELIMITER"))
    }

    @Test
    fun sql_server_go_creates_execution_units() {
        val parsed = MigrationScriptParser.parse(
            "CREATE TABLE [users] ([id] int);\nGO\nCREATE INDEX [users_id] ON [users]([id]);\nGO\n",
            MigrationDialect.SqlServer,
        )

        assertEquals(2, parsed.steps.size)
        assertEquals(
            listOf("CREATE TABLE [users] ([id] int);", "CREATE INDEX [users_id] ON [users]([id]);"),
            parsed.steps.map(ParsedExecutionStep::sql),
        )
    }

    @Test
    fun oracle_plsql_block_uses_slash_as_script_terminator() {
        val script = """
            CREATE OR REPLACE PROCEDURE p AS
            BEGIN
              NULL;
            END;
            /
            CREATE TABLE users (id NUMBER);
        """.trimIndent()

        val parsed = MigrationScriptParser.parse(script, MigrationDialect.Oracle)

        assertEquals(2, parsed.steps.size)
        assertEquals(true, parsed.steps.first().sql.contains("NULL;"))
        assertEquals(false, parsed.steps.first().sql.contains("\n/"))
    }

    @Test
    fun explicit_transactions_and_nontransactional_statement_become_distinct_steps() {
        val script = """
            BEGIN;
            ALTER TABLE users ADD COLUMN email TEXT;
            COMMIT;
            CREATE INDEX CONCURRENTLY users_email ON users(email);
            BEGIN TRANSACTION;
            ALTER TABLE users ADD COLUMN name TEXT;
            COMMIT;
        """.trimIndent()

        val parsed = MigrationScriptParser.parse(script, MigrationDialect.PostgreSql)

        assertEquals(
            listOf(
                MigrationStepKind.Transactional,
                MigrationStepKind.NonTransactional,
                MigrationStepKind.Transactional,
            ),
            parsed.steps.map(ParsedExecutionStep::kind),
        )
    }

    @Test
    fun savepoints_do_not_create_step_boundaries() {
        val parsed = MigrationScriptParser.parse(
            """
            BEGIN;
            SAVEPOINT before_index;
            CREATE INDEX users_name ON users(name);
            ROLLBACK TO before_index;
            RELEASE before_index;
            COMMIT;
            """.trimIndent(),
            MigrationDialect.PostgreSql,
        )

        assertEquals(listOf(MigrationStepKind.Transactional), parsed.steps.map(ParsedExecutionStep::kind))
    }

    @Test
    fun hash_ignores_layout_and_keyword_case_but_preserves_literals_and_comments() {
        assertEquals(
            stepFingerprint(singleStep("SELECT * FROM users;")),
            stepFingerprint(singleStep("select\n  *\nfrom users;")),
        )
        assertFailsWith<AssertionError> {
            assertEquals(
                stepFingerprint(singleStep("SELECT 'Hello';")),
                stepFingerprint(singleStep("SELECT 'hello';")),
            )
        }
        assertFailsWith<AssertionError> {
            assertEquals(
                stepFingerprint(singleStep("-- meaningful\nSELECT 1;")),
                stepFingerprint(singleStep("-- changed\nSELECT 1;")),
            )
        }
    }

    private fun singleStep(sql: String): ParsedExecutionStep =
        MigrationScriptParser.parse(sql, MigrationDialect.PostgreSql)
            .steps
            .single()
}
