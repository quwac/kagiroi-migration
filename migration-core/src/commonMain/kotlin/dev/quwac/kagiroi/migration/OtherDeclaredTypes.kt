package dev.quwac.kagiroi.migration

internal fun validSqliteDeclaration(value: String): Boolean = value in setOf("INTEGER", "REAL", "TEXT", "BLOB") ||
    value.matchesDeclaration(DECLARED_QUOTED_IDENTIFIER) ||
    value.declarationParameters("$DECLARED_QUOTED_IDENTIFIER\\(([0-9]+)\\)") { it.single() >= 0 } ||
    value.declarationParameters(
        "$DECLARED_QUOTED_IDENTIFIER\\(([0-9]+),([0-9]+)\\)",
    ) { it.all { number -> number >= 0 } }

internal fun validSqlServerDeclaration(value: String): Boolean =
    value in setOf("BIT", "TINYINT", "SMALLINT", "INT", "BIGINT", "REAL", "FLOAT", "UNIQUEIDENTIFIER", "DATE") ||
        value.matchesDeclaration("(?:NVARCHAR|VARBINARY)\\(MAX\\)") ||
        value.matchesDeclaration("(?:TIME|DATETIME2|DATETIMEOFFSET)\\([0-7]\\)") ||
        value.declarationParameters("NVARCHAR\\(([0-9]+)\\)") { it.single() in 1..SQLSERVER_UNICODE_LENGTH } ||
        value.declarationParameters("(?:VARCHAR|VARBINARY)\\(([0-9]+)\\)") {
            it.single() in 1..SQLSERVER_BINARY_LENGTH
        } || value.declarationParameters("(?:DECIMAL|NUMERIC)\\(([0-9]+),([0-9]+)\\)") {
            it[0] in 1..SQLSERVER_DECIMAL_PRECISION && it[1] in 0..it[0]
        }

internal fun validOracleDeclaration(value: String): Boolean =
    value in setOf("BOOLEAN", "BINARY_FLOAT", "BINARY_DOUBLE", "CLOB", "NCLOB", "BLOB", "DATE") ||
        value.matchesDeclaration("TIMESTAMP\\(9\\)(?: WITH (?:LOCAL )?TIME ZONE)?") ||
        validOracleLength(value) ||
        value.declarationParameters("NUMBER\\(([0-9]+),(-?[0-9]+)\\)") {
            it[0] in 1..ORACLE_NUMBER_PRECISION && it[1] in -ORACLE_NUMBER_PRECISION..ORACLE_NUMBER_PRECISION
        }

private fun validOracleLength(value: String): Boolean =
    value.declarationParameters("VARCHAR2\\(([0-9]+) CHAR\\)") { it.single() in 1..ORACLE_VARCHAR_LENGTH } ||
        value.declarationParameters("CHAR\\(([0-9]+) CHAR\\)") { it.single() in 1..ORACLE_CHAR_LENGTH } ||
        value.declarationParameters("(?:NVARCHAR2|RAW)\\(([0-9]+)\\)") { it.single() in 1..ORACLE_CHAR_LENGTH } ||
        value.declarationParameters("NCHAR\\(([0-9]+)\\)") { it.single() in 1..ORACLE_NCHAR_LENGTH }

private const val SQLSERVER_UNICODE_LENGTH = 4_000
private const val SQLSERVER_BINARY_LENGTH = 8_000
private const val SQLSERVER_DECIMAL_PRECISION = 38
private const val ORACLE_VARCHAR_LENGTH = 4_000
private const val ORACLE_CHAR_LENGTH = 2_000
private const val ORACLE_NCHAR_LENGTH = 1_000
private const val ORACLE_NUMBER_PRECISION = 38
