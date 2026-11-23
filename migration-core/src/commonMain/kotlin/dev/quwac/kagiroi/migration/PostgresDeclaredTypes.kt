package dev.quwac.kagiroi.migration

internal fun validPostgresDeclaration(declaration: String): Boolean {
    val element = declaration.replace(Regex("(?:\\[\\])+$"), "")
    return when {
        element in postgresScalarDeclarations -> element == declaration || element !in postgresSerialDeclarations
        element.matchesDeclaration("$DECLARED_QUOTED_IDENTIFIER(?:\\.$DECLARED_QUOTED_IDENTIFIER)?") -> true
        else -> validPostgresParameters(element) || validPostgresInterval(element)
    }
}

private val postgresSerialDeclarations = setOf("SMALLSERIAL", "SERIAL", "BIGSERIAL")

private val postgresScalarDeclarations = setOf(
    "BOOLEAN", "SMALLINT", "INTEGER", "BIGINT", "SMALLSERIAL", "SERIAL", "BIGSERIAL", "REAL", "DOUBLE PRECISION",
    "TEXT", "NAME", "BYTEA", "UUID", "DATE", "TIME WITHOUT TIME ZONE", "TIME WITH TIME ZONE",
    "TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP WITH TIME ZONE", "MONEY", "NUMERIC", "CHARACTER VARYING", "BIT VARYING",
    "JSON", "JSONB", "JSONPATH", "INET", "CIDR", "MACADDR", "MACADDR8", "POINT", "LINE", "LSEG", "BOX", "PATH",
    "POLYGON", "CIRCLE", "TSQUERY", "TSVECTOR", "XML", "PG_LSN", "PG_SNAPSHOT", "TXID_SNAPSHOT", "OID", "XID", "CID",
    "XID8", "TID", "INT2VECTOR", "OIDVECTOR", "ACLITEM", "REGCLASS", "REGCOLLATION", "REGCONFIG", "REGDICTIONARY",
    "REGNAMESPACE", "REGOPER", "REGOPERATOR", "REGPROCEDURE", "REGPROC", "REGROLE", "REGTYPE", "INT4RANGE", "INT8RANGE",
    "NUMRANGE", "TSRANGE", "TSTZRANGE", "DATERANGE", "INT4MULTIRANGE", "INT8MULTIRANGE", "NUMMULTIRANGE",
    "TSMULTIRANGE",
    "TSTZMULTIRANGE", "DATEMULTIRANGE",
)

private fun validPostgresParameters(value: String): Boolean =
    value.declarationParameters("(?:CHARACTER(?: VARYING)?|BIT(?: VARYING)?)\\(([0-9]+)\\)") { it.single() > 0 } ||
        value.matchesDeclaration("(?:TIME|TIMESTAMP)\\([0-6]\\) (?:WITH|WITHOUT) TIME ZONE") ||
        value.declarationParameters("NUMERIC\\(([0-9]+),(-?[0-9]+)\\)") {
            it[0] in 1..POSTGRES_NUMERIC_LIMIT && it[1] in -POSTGRES_NUMERIC_LIMIT..POSTGRES_NUMERIC_LIMIT
        }

private fun validPostgresInterval(value: String): Boolean {
    val fields = "(?:YEAR|MONTH|DAY|HOUR|MINUTE|SECOND|YEAR TO MONTH|DAY TO HOUR|DAY TO MINUTE|DAY TO SECOND|" +
        "HOUR TO MINUTE|HOUR TO SECOND|MINUTE TO SECOND)"
    val secondsFields = "(?:SECOND|DAY TO SECOND|HOUR TO SECOND|MINUTE TO SECOND)"
    return value.matchesDeclaration("INTERVAL(?: $fields)?") ||
        value.matchesDeclaration("INTERVAL(?: $secondsFields)?\\([0-6]\\)")
}

private const val POSTGRES_NUMERIC_LIMIT = 1_000
