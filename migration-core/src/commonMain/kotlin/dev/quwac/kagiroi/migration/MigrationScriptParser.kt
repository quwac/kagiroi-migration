@file:Suppress(
    "BlankLineBetweenWhenConditions",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "MaximumLineLength",
)

package dev.quwac.kagiroi.migration

data class ParsedExecutionStep(
    val dialect: MigrationDialect,
    val kind: MigrationStepKind,
    val sql: String,
    val executionUnits: List<String>,
)

data class ParsedMigrationScript(val steps: List<ParsedExecutionStep>)

/** A lexer-backed script parser. It recognizes execution boundaries but never reconstructs schema from SQL. */
object MigrationScriptParser {
    fun parse(source: String, dialect: MigrationDialect): ParsedMigrationScript {
        val units = splitScript(source, dialect)
        val steps = mutableListOf<ParsedExecutionStep>()
        val transaction = mutableListOf<String>()
        units.forEach { unit ->
            if (isCommentOnly(unit, dialect)) return@forEach
            when (transactionCommand(unit, dialect)) {
                TransactionCommand.Begin -> {
                    require(transaction.isEmpty()) { "Nested migration transactions are not supported" }
                    transaction += unit
                }
                TransactionCommand.Commit -> {
                    require(transaction.isNotEmpty()) { "COMMIT appears outside a transaction" }
                    transaction += unit
                    steps += ParsedExecutionStep(
                        dialect,
                        MigrationStepKind.Transactional,
                        transaction.joinToString("\n"),
                        transaction.toList(),
                    )
                    transaction.clear()
                }
                TransactionCommand.Rollback -> throw IllegalArgumentException(
                    "ROLLBACK is not valid in a migration",
                )
                else -> if (transaction.isEmpty()) {
                    steps +=
                        ParsedExecutionStep(
                            dialect,
                            MigrationStepKind.NonTransactional,
                            unit,
                            listOf(unit),
                        )
                } else {
                    transaction += unit
                }
            }
        }
        require(transaction.isEmpty()) { "Migration transaction is missing COMMIT" }
        return ParsedMigrationScript(steps.filter { it.sql.isNotBlank() })
    }
}

/** Canonical hash input with keyword case, line ending, and insignificant whitespace normalized. */
fun canonicalizeSql(source: String, dialect: MigrationDialect): String =
    lexSql(source, dialect).joinToString("\u0000") {
        when (it.kind) {
            SqlTokenKind.Keyword -> "K:${it.value.uppercase()}"
            SqlTokenKind.Identifier -> "I:${it.value}"
            SqlTokenKind.QuotedIdentifier -> "QI:${it.value}"
            SqlTokenKind.String -> "S:${it.value}"
            SqlTokenKind.Number -> "N:${it.value}"
            SqlTokenKind.Symbol -> "P:${it.value}"
            SqlTokenKind.Comment -> "C:${it.value.replace("\r\n", "\n").replace('\r', '\n')}"
        }
    }

enum class SqlTokenKind { Keyword, Identifier, QuotedIdentifier, String, Number, Symbol, Comment }
data class SqlToken(val kind: SqlTokenKind, val value: String)

/** Dialect lexical profile leaves quote-mode choices explicit for future connection configuration. */
data class SqlLexicalProfile(
    val dialect: MigrationDialect,
    val mysqlAnsiQuotes: Boolean = false,
    val sqlServerQuotedIdentifier: Boolean = true,
)

fun lexSql(
    source: String,
    dialect: MigrationDialect,
    profile: SqlLexicalProfile = SqlLexicalProfile(dialect),
): List<SqlToken> {
    val result = mutableListOf<SqlToken>()
    var index = 0
    while (index < source.length) {
        val char = source[index]
        if (char.isWhitespace()) {
            index++
            continue
        }
        if (source.startsWith("--", index)) {
            val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
            result += SqlToken(SqlTokenKind.Comment, source.substring(index, end))
            index = end
            continue
        }
        if (source.startsWith("/*", index)) {
            val end = source.indexOf("*/", index + 2)
            require(end >= 0) { "Unclosed SQL block comment" }
            result += SqlToken(SqlTokenKind.Comment, source.substring(index, end + 2))
            index = end + 2
            continue
        }
        val doubleString =
            char == '"' &&
                (
                    (dialect in setOf(MigrationDialect.MySql, MigrationDialect.MariaDb) && !profile.mysqlAnsiQuotes) ||
                        (dialect == MigrationDialect.SqlServer && !profile.sqlServerQuotedIdentifier)
                    )
        if (char == '\'' || doubleString) {
            val quote = char
            val end = quotedEnd(source, index, quote)
            result +=
                SqlToken(SqlTokenKind.String, source.substring(index + 1, end - 1).replace("$quote$quote", "$quote"))
            index = end
            continue
        }
        if (char == '"' || char == '`' || char == '[') {
            val close = if (char == '[') ']' else char
            val end = quotedEnd(source, index, close)
            result +=
                SqlToken(
                    SqlTokenKind.QuotedIdentifier,
                    source.substring(index + 1, end - 1).replace("$close$close", "$close"),
                )
            index = end
            continue
        }
        if (char == '$' && dialect == MigrationDialect.PostgreSql) {
            val tagEnd = source.indexOf('$', index + 1)
            if (tagEnd >= 0 && source.substring(index + 1, tagEnd).all { it.isLetterOrDigit() || it == '_' }) {
                val tag = source.substring(index, tagEnd + 1)
                val end = source.indexOf(tag, tagEnd + 1)
                require(end >= 0) { "Unclosed PostgreSQL dollar string" }
                result += SqlToken(SqlTokenKind.String, source.substring(tagEnd + 1, end))
                index = end + tag.length
                continue
            }
        }
        if (char.isLetter() || char == '_') {
            var end =
                index + 1
            while (end < source.length &&
                (source[end].isLetterOrDigit() || source[end] == '_' || source[end] == '$')
            ) {
                end++
            }
            val word = source.substring(index, end)
            result +=
                SqlToken(if (word.uppercase() in KEYWORDS) SqlTokenKind.Keyword else SqlTokenKind.Identifier, word)
            index = end
            continue
        }
        if (char.isDigit()) {
            var end = index + 1
            while (end < source.length && (source[end].isDigit() || source[end] in ".eE+-")) end++
            result += SqlToken(SqlTokenKind.Number, source.substring(index, end))
            index = end
            continue
        }
        result += SqlToken(SqlTokenKind.Symbol, char.toString())
        index++
    }
    return result
}

private fun quotedEnd(source: String, start: Int, quote: Char): Int {
    var index = start + 1
    while (index < source.length) {
        if (source[index] == quote) {
            if (index + 1 < source.length && source[index + 1] == quote) {
                index += 2
                continue
            }
            return index + 1
        }
        index++
    }
    error("Unclosed SQL quote")
}

private enum class TransactionCommand { Begin, Commit, Rollback, Other }

private fun transactionCommand(unit: String, dialect: MigrationDialect): TransactionCommand {
    val words = lexSql(unit, dialect).filter { it.kind == SqlTokenKind.Keyword }.map { it.value.uppercase() }
    val first = words.firstOrNull() ?: return TransactionCommand.Other
    return when {
        dialect == MigrationDialect.Oracle && first == "BEGIN" -> TransactionCommand.Other
        first == "BEGIN" -> TransactionCommand.Begin
        first == "START" && words.getOrNull(1) == "TRANSACTION" -> TransactionCommand.Begin
        first == "COMMIT" -> TransactionCommand.Commit
        first == "ROLLBACK" && words.getOrNull(1) != "TO" -> TransactionCommand.Rollback
        else -> TransactionCommand.Other
    }
}

private fun isCommentOnly(unit: String, dialect: MigrationDialect): Boolean =
    lexSql(unit, dialect).all { it.kind == SqlTokenKind.Comment }

private fun splitScript(source: String, dialect: MigrationDialect): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var delimiter = ";"
    var index = 0
    var quote: Char? = null
    var blockComment = false
    var lineComment = false
    var dollarTag: String? = null
    fun flush() {
        current.toString().trim().takeIf(String::isNotEmpty)?.let(result::add)
        current.clear()
    }
    while (index < source.length) {
        if (quote == null && !blockComment && !lineComment && dollarTag == null && source.lineStart(index)) {
            val lineEnd = source.indexOf('\n', index).let { if (it < 0) source.length else it }
            val line = source.substring(index, lineEnd).trim()
            if (dialect in setOf(MigrationDialect.MySql, MigrationDialect.MariaDb) &&
                line.startsWith("DELIMITER ", true)
            ) {
                delimiter = line.substringAfter(' ').trim()
                require(delimiter.isNotEmpty()) { "Empty MySQL DELIMITER" }
                index = if (lineEnd == source.length) lineEnd else lineEnd + 1
                continue
            }
            if (dialect == MigrationDialect.SqlServer &&
                line.equals("GO", true)
            ) {
                flush()
                index = if (lineEnd ==
                    source.length
                ) {
                    lineEnd
                } else {
                    lineEnd + 1
                }
                continue
            }
            if (dialect == MigrationDialect.Oracle &&
                line == "/"
            ) {
                flush()
                index = if (lineEnd == source.length) lineEnd else lineEnd + 1
                continue
            }
        }
        if (lineComment) {
            val c = source[index++]
            current.append(c)
            if (c == '\n') lineComment = false
            continue
        }
        if (blockComment) {
            if (source.startsWith("*/", index)) {
                current.append("*/")
                index += 2
                blockComment = false
            } else {
                current.append(source[index++])
            }
            continue
        }
        dollarTag?.let { tag ->
            if (source.startsWith(tag, index)) {
                current.append(tag)
                index += tag.length
                dollarTag =
                    null
            } else {
                current.append(source[index++])
            }
            continue
        }
        quote?.let { q ->
            val c = source[index++]
            current.append(c)
            if (c ==
                q
            ) {
                if (index < source.length &&
                    source[index] == q
                ) {
                    current.append(source[index++])
                } else {
                    quote = null
                }
            }
            continue
        }
        if (source.startsWith("--", index)) {
            current.append("--")
            index += 2
            lineComment = true
            continue
        }
        if (source.startsWith("/*", index)) {
            current.append("/*")
            index += 2
            blockComment = true
            continue
        }
        val c = source[index]
        if (c == '\'' || c == '"' || c == '`' ||
            c == '['
        ) {
            quote = if (c == '[') ']' else c
            current.append(c)
            index++
            continue
        }
        if (c == '$' && dialect == MigrationDialect.PostgreSql) {
            val end = source.indexOf('$', index + 1)
            if (end >= 0 &&
                source.substring(index + 1, end).all { it.isLetterOrDigit() || it == '_' }
            ) {
                dollarTag =
                    source.substring(index, end + 1)
                current.append(dollarTag)
                index = end + 1
                continue
            }
        }
        if (source.startsWith(delimiter, index) && !isOracleScriptBlock(current, dialect)) {
            current.append(delimiter)
            index += delimiter.length
            flush()
            continue
        }
        current.append(c)
        index++
    }
    require(!blockComment && quote == null && dollarTag == null) { "Unclosed SQL lexical construct" }
    flush()
    return result
}

private fun isOracleScriptBlock(current: StringBuilder, dialect: MigrationDialect): Boolean {
    if (dialect != MigrationDialect.Oracle) return false
    val words = lexSql(current.toString(), dialect)
        .filter { it.kind == SqlTokenKind.Keyword || it.kind == SqlTokenKind.Identifier }
        .map { it.value.uppercase() }
    return when {
        words.firstOrNull() in setOf("BEGIN", "DECLARE") -> true
        words.firstOrNull() != "CREATE" -> false
        words.getOrNull(1) in setOf("PROCEDURE", "FUNCTION", "TRIGGER", "PACKAGE") -> true
        words.getOrNull(1) == "OR" && words.getOrNull(2) == "REPLACE" &&
            words.getOrNull(3) in setOf("PROCEDURE", "FUNCTION", "TRIGGER", "PACKAGE") -> true
        else -> false
    }
}

private fun String.lineStart(index: Int): Boolean = index == 0 || this[index - 1] == '\n'

private val KEYWORDS =
    setOf(
        "ALTER",
        "AND",
        "AS",
        "BEGIN",
        "BY",
        "CHECK",
        "COLUMN",
        "COMMIT",
        "CONSTRAINT",
        "CREATE",
        "DEFAULT",
        "DELETE",
        "DELIMITER",
        "DROP",
        "END",
        "FOREIGN",
        "FROM",
        "GO",
        "INDEX",
        "INSERT",
        "INTO",
        "KEY",
        "NOT",
        "NULL",
        "ON",
        "OR",
        "PRIMARY",
        "PROCEDURE",
        "REFERENCES",
        "RELEASE",
        "RENAME",
        "ROLLBACK",
        "SAVEPOINT",
        "SELECT",
        "SET",
        "START",
        "TABLE",
        "TO",
        "TRANSACTION",
        "UNIQUE",
        "UPDATE",
        "VALUES",
        "WHERE",
    )
