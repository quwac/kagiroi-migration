package dev.quwac.kagiroi.migration

/** Parses only the validated ENUM/SET literal grammar exported by Kagiroi, never arbitrary SQL. */
@Suppress("CyclomaticComplexMethod")
internal fun CanonicalColumnType.mysqlEnumLabels(): Pair<String, List<String>>? {
    if (id !in setOf("kagiroi.mysql.declared", "kagiroi.mariadb.declared")) return null
    val declaration = arguments.singleOrNull()?.takeIf { it.name == "declaration" }?.value ?: return null
    val kind = when {
        declaration.startsWith("ENUM(") -> "ENUM"
        declaration.startsWith("SET(") -> "SET"
        else -> return null
    }
    var index = kind.length + 1
    val labels = mutableListOf<String>()
    while (true) {
        while (declaration.getOrNull(index)?.isWhitespace() == true) index++
        require(declaration.getOrNull(index++) == '\'') { "Invalid ENUM/SET literal" }
        val label = StringBuilder()
        while (true) {
            val character = declaration.getOrNull(index++) ?: error("Unterminated ENUM/SET label")
            require(
                character != '\\' && character != '\u0000',
            ) { "ENUM/SET labels must be independent of SQL escape mode" }
            if (character == '\'') {
                if (declaration.getOrNull(index) == '\'') {
                    label.append('\'')
                    index++
                } else {
                    break
                }
            } else {
                label.append(character)
            }
        }
        labels += label.toString()
        while (declaration.getOrNull(index)?.isWhitespace() == true) index++
        when (declaration.getOrNull(index++)) {
            ',' -> Unit
            ')' -> break
            else -> error("Invalid ENUM/SET declaration")
        }
    }
    require(
        index == declaration.length && labels.distinct().size == labels.size,
    ) { "Invalid ENUM/SET declaration or duplicate labels" }
    return kind to labels
}
