package dev.quwac.kagiroi.migration

/** Recognizes the physical declarations exported by Kagiroi's dialect type factories. */
internal fun CanonicalColumnType.declaredSqlType(dialect: MigrationDialect): String? {
    val family = declaredTypeFamily(id) ?: return null
    val declaration = arguments.singleOrNull()?.takeIf { it.name == "declaration" }?.value
    if (!family.accepts(dialect)) {
        throw MigrationSqlGenerationException(dialect.id, "column type $id", "requires ${family.name}")
    }
    if (declaration == null || !family.validDeclaration(declaration, dialect)) {
        throw MigrationSqlGenerationException(dialect.id, "column type $id", "unsupported physical type declaration")
    }
    return declaration
}

private enum class DeclaredTypeFamily {
    PostgreSql,
    MySql,
    MariaDb,
    Sqlite,
    SqlServer,
    Oracle,
    ;

    fun accepts(dialect: MigrationDialect): Boolean = when (this) {
        PostgreSql -> dialect == MigrationDialect.PostgreSql
        MySql -> dialect in setOf(MigrationDialect.MySql, MigrationDialect.MariaDb)
        MariaDb -> dialect == MigrationDialect.MariaDb
        Sqlite -> dialect in setOf(MigrationDialect.Sqlite, MigrationDialect.D1)
        SqlServer -> dialect == MigrationDialect.SqlServer
        Oracle -> dialect == MigrationDialect.Oracle
    }

    fun validDeclaration(value: String, dialect: MigrationDialect): Boolean = when (this) {
        PostgreSql -> validPostgresDeclaration(value)
        MySql -> validMySqlDeclaration(value) && (value != "JSON" || dialect == MigrationDialect.MySql)
        MariaDb -> validMariaDbDeclaration(value)
        Sqlite -> validSqliteDeclaration(value)
        SqlServer -> validSqlServerDeclaration(value)
        Oracle -> validOracleDeclaration(value)
    }
}

private fun declaredTypeFamily(id: String): DeclaredTypeFamily? = when (id) {
    "kagiroi.postgres.declared" -> DeclaredTypeFamily.PostgreSql
    "kagiroi.mysql.declared" -> DeclaredTypeFamily.MySql
    "kagiroi.mariadb.declared" -> DeclaredTypeFamily.MariaDb
    "kagiroi.sqlite.declared" -> DeclaredTypeFamily.Sqlite
    "kagiroi.sqlserver.declared" -> DeclaredTypeFamily.SqlServer
    "kagiroi.oracle.declared" -> DeclaredTypeFamily.Oracle
    else -> null
}

// A quoted identifier may contain SQL punctuation, but never an unescaped quote or NUL.
internal const val DECLARED_QUOTED_IDENTIFIER = "\"(?:[^\"\\u0000]|\"\")+\""

internal fun String.matchesDeclaration(pattern: String): Boolean = Regex("^(?:$pattern)$").matches(this)

/** Matches a complete declaration and validates every numeric parameter without accepting overflow. */
internal fun String.declarationParameters(pattern: String, valid: (List<Int>) -> Boolean): Boolean {
    val match = Regex("^(?:$pattern)$").matchEntire(this) ?: return false
    val numbers = match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
    return valid(numbers)
}
