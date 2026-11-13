package dev.quwac.kagiroi.migration

/**
 * Adds, updates, or removes MS_Description on a table or one of its named children.
 * Each dynamic batch scopes its default-schema variable independently, so multiple comments
 * can run in one migration batch without duplicate DECLARE statements or a hardcoded dbo schema.
 */
internal fun sqlServerComment(
    table: CanonicalTableReference,
    childType: String?,
    childName: String?,
    value: String?,
): String {
    fun literal(text: String): String = "N'${text.replace("'", "''")}'"
    val schema = table.schemaName?.let(::literal) ?: "@kagiroi_schema"
    val childLevels = if (childType == null) {
        "NULL, NULL"
    } else {
        "${literal(
            childType,
        )}, ${literal(requireNotNull(childName))}"
    }
    val lookup = "sys.fn_listextendedproperty(N'MS_Description', N'SCHEMA', $schema, " +
        "N'TABLE', ${literal(table.name)}, $childLevels)"
    val levels = "@level0type=N'SCHEMA', @level0name=$schema, " +
        "@level1type=N'TABLE', @level1name=${literal(table.name)}" +
        if (childType == null) {
            ""
        } else {
            ", @level2type=${literal(
                childType,
            )}, @level2name=${literal(requireNotNull(childName))}"
        }
    val name = "@name=N'MS_Description'"
    val batch = buildString {
        if (table.schemaName == null) append("DECLARE @kagiroi_schema sysname = SCHEMA_NAME(); ")
        append("IF EXISTS (SELECT 1 FROM $lookup) ")
        if (value == null) {
            append("EXEC sys.sp_dropextendedproperty $name, $levels;")
        } else {
            val arguments = "$name, @value=${literal(value)}, $levels;"
            append("EXEC sys.sp_updateextendedproperty $arguments ")
            append("ELSE EXEC sys.sp_addextendedproperty $arguments")
        }
    }
    return "EXEC sys.sp_executesql ${literal(batch)}"
}
