package dev.quwac.kagiroi.migration

private const val VARIABLE_SCOPE_HASH_LENGTH = 32

internal fun sqlServerDropUnnamedKey(table: CanonicalTableReference, key: CanonicalForeignKey? = null): String {
    val scope = sha256Hex("$table|$key").take(VARIABLE_SCOPE_HASH_LENGTH)
    val countVariable = "@kagiroi_${scope}_count"
    val nameVariable = "@kagiroi_${scope}_name"
    val lockVariable = "@kagiroi_${scope}_lock"
    val sqlVariable = "@kagiroi_${scope}_sql"
    val target = sqlServerRelation(table)
    val targetLiteral = unicodeLiteral(target)
    val match = if (key == null) {
        "SELECT $countVariable = COUNT(*), $nameVariable = MAX(name) FROM sys.key_constraints " +
            "WHERE parent_object_id = OBJECT_ID($targetLiteral) AND type = 'PK';"
    } else {
        val pairs = key.sourceColumns.zip(key.targetColumns).mapIndexed { index, (source, destination) ->
            "EXISTS (SELECT 1 FROM sys.foreign_key_columns c WHERE c.constraint_object_id=f.object_id " +
                "AND c.constraint_column_id=${index + 1} " +
                "AND COL_NAME(f.parent_object_id,c.parent_column_id)=${unicodeLiteral(source)} " +
                "AND COL_NAME(f.referenced_object_id,c.referenced_column_id)=${unicodeLiteral(destination)})"
        }.joinToString(" AND ")
        "SELECT $countVariable=COUNT(*), $nameVariable=MAX(f.name) FROM sys.foreign_keys f " +
            "WHERE f.parent_object_id=OBJECT_ID($targetLiteral) " +
            "AND f.referenced_object_id=OBJECT_ID(${unicodeLiteral(sqlServerRelation(key.targetTable))}) " +
            "AND f.delete_referential_action=${sqlServerAction(key.onDelete)} " +
            "AND f.update_referential_action=${sqlServerAction(key.onUpdate)} " +
            "AND (SELECT COUNT(*) FROM sys.foreign_key_columns c WHERE c.constraint_object_id=f.object_id)=" +
            "${key.sourceColumns.size} AND $pairs;"
    }
    return """
        DECLARE $countVariable int, $nameVariable sysname, $lockVariable bigint, $sqlVariable nvarchar(max);
        SELECT $lockVariable=COUNT_BIG(*) FROM $target WITH (TABLOCKX, HOLDLOCK);
        $match
        IF $countVariable <> 1 THROW 50001, 'Expected exactly one unnamed key constraint', 1;
        SET $sqlVariable=N'ALTER TABLE ${target.replace("'", "''")} DROP CONSTRAINT ' + QUOTENAME($nameVariable);
        EXEC sys.sp_executesql $sqlVariable;
    """.trimIndent()
}

internal fun oracleDropUnnamedKey(table: CanonicalTableReference, key: CanonicalForeignKey? = null): String {
    val target = oracleRelation(table)
    val owner = table.schemaName?.let(::sqlLiteral) ?: "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')"
    val match = if (key == null) {
        "c.constraint_type='P'"
    } else {
        require(
            key.onUpdate == null || key.onUpdate == CanonicalForeignKeyAction.NoAction,
        ) { "Oracle does not support ON UPDATE actions" }
        val referencedOwner = key.targetTable.schemaName?.let(::sqlLiteral) ?: "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')"
        val pairs = key.sourceColumns.zip(key.targetColumns).mapIndexed { index, (source, destination) ->
            "EXISTS (SELECT 1 FROM all_cons_columns s JOIN all_cons_columns t ON t.owner=c.r_owner " +
                "AND t.constraint_name=c.r_constraint_name AND t.position=s.position " +
                "WHERE s.owner=c.owner AND s.constraint_name=c.constraint_name AND s.position=${index + 1} " +
                "AND s.column_name=${sqlLiteral(source)} AND t.column_name=${sqlLiteral(destination)})"
        }.joinToString(" AND ")
        "c.constraint_type='R' AND c.r_owner=$referencedOwner AND c.delete_rule=${sqlLiteral(
            oracleAction(key.onDelete),
        )} " +
            "AND EXISTS (SELECT 1 FROM all_constraints r WHERE r.owner=c.r_owner " +
            "AND r.constraint_name=c.r_constraint_name AND r.table_name=${sqlLiteral(key.targetTable.name)}) " +
            "AND (SELECT COUNT(*) FROM all_cons_columns s WHERE s.owner=c.owner " +
            "AND s.constraint_name=c.constraint_name)=${key.sourceColumns.size} AND $pairs"
    }
    return """
        DECLARE
            match_count INTEGER;
            constraint_name VARCHAR2(128);
        BEGIN
            LOCK TABLE $target IN EXCLUSIVE MODE;
            SELECT COUNT(*), MAX(c.constraint_name) INTO match_count, constraint_name FROM all_constraints c
            WHERE c.owner=$owner AND c.table_name=${sqlLiteral(table.name)} AND $match;
            IF match_count <> 1 THEN
                RAISE_APPLICATION_ERROR(-20001, 'Expected exactly one unnamed key constraint, found ' || match_count);
            END IF;
            EXECUTE IMMEDIATE ${sqlLiteral(
        "ALTER TABLE $target DROP CONSTRAINT ",
    )} || '"' || REPLACE(constraint_name, '"', '""') || '"';
        END;
    """.trimIndent()
}

@Suppress("MagicNumber") // Native sys.foreign_keys referential-action codes.
private fun sqlServerAction(action: CanonicalForeignKeyAction?): Int = when (action) {
    null, CanonicalForeignKeyAction.NoAction -> 0
    CanonicalForeignKeyAction.Cascade -> 1
    CanonicalForeignKeyAction.SetNull -> 2
    CanonicalForeignKeyAction.SetDefault -> 3
    CanonicalForeignKeyAction.Restrict -> error("SQL Server does not support RESTRICT")
}

private fun oracleAction(action: CanonicalForeignKeyAction?): String = when (action) {
    null, CanonicalForeignKeyAction.NoAction -> "NO ACTION"
    CanonicalForeignKeyAction.Cascade -> "CASCADE"
    CanonicalForeignKeyAction.SetNull -> "SET NULL"
    else -> error("Oracle does not support this referential action")
}
private fun sqlServerRelation(table: CanonicalTableReference) = listOfNotNull(table.schemaName, table.name)
    .joinToString(".") { "[${it.replace("]", "]]")}]" }
private fun oracleRelation(table: CanonicalTableReference) = listOfNotNull(table.schemaName, table.name)
    .joinToString(".") { "\"${it.replace("\"", "\"\"")}\"" }
private fun sqlLiteral(value: String) = "'${value.replace("'", "''")}'"
private fun unicodeLiteral(value: String) = "N${sqlLiteral(value)}"
