package dev.quwac.kagiroi.migration

internal fun validMySqlDeclaration(value: String): Boolean = value in mysqlScalarDeclarations ||
    value.declarationParameters("(?:BIT|CHAR|VARCHAR|BINARY|VARBINARY)\\(([0-9]+)\\)") { it.single() > 0 } ||
    value.matchesDeclaration("(?:TIME|DATETIME|TIMESTAMP)\\([0-6]\\)") ||
    validMySqlDecimal(value, MYSQL_DECIMAL_SCALE)

internal fun validMariaDbDeclaration(value: String): Boolean = value in setOf(
    "UUID",
    "JSON",
    "INET4",
    "INET6",
    "XMLTYPE",
) ||
    value.declarationParameters("VECTOR\\(([0-9]+)\\)") { it.single() > 0 } ||
    validMySqlDecimal(value, MARIADB_DECIMAL_SCALE) || value == "CHAR(36)"

private val mysqlScalarDeclarations = setOf(
    "BOOLEAN", "TINYINT", "TINYINT UNSIGNED", "SMALLINT", "SMALLINT UNSIGNED", "MEDIUMINT", "MEDIUMINT UNSIGNED",
    "INT", "INT UNSIGNED", "BIGINT", "BIGINT UNSIGNED", "SERIAL", "FLOAT", "DOUBLE", "YEAR", "TINYTEXT", "TEXT",
    "MEDIUMTEXT", "LONGTEXT", "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "JSON", "GEOMETRY", "POINT", "LINESTRING",
    "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION", "DATE",
)

private fun validMySqlDecimal(value: String, maxScale: Int): Boolean =
    value.declarationParameters("DECIMAL\\(([0-9]+),([0-9]+)\\)") {
        it[0] in 1..MYSQL_DECIMAL_PRECISION && it[1] in 0..minOf(it[0], maxScale)
    }

private const val MYSQL_DECIMAL_PRECISION = 65
private const val MYSQL_DECIMAL_SCALE = 30
private const val MARIADB_DECIMAL_SCALE = 38
