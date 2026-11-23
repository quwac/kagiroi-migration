package dev.quwac.kagiroi.migration

internal fun postgresDropUnnamedPrimaryKey(table: CanonicalTableReference): String =
    postgresDropUnnamedConstraint(table, "PRIMARY KEY", "c.contype = 'p'")

internal fun postgresDropUnnamedForeignKey(table: CanonicalTableReference, key: CanonicalForeignKey): String {
    val target = postgresRelation(key.targetTable)
    val predicate = """
        c.contype = 'f'
          AND c.confrelid = ${postgresLiteral(target)}::pg_catalog.regclass
          AND ${postgresKeyColumns("conkey", "conrelid")} = ${postgresColumnNames(key.sourceColumns)}
          AND ${postgresKeyColumns("confkey", "confrelid")} = ${postgresColumnNames(key.targetColumns)}
          AND c.confdeltype = '${postgresAction(key.onDelete)}'
          AND c.confupdtype = '${postgresAction(key.onUpdate)}'
    """.trimIndent()
    return postgresDropUnnamedConstraint(table, "FOREIGN KEY", predicate)
}

private fun postgresDropUnnamedConstraint(table: CanonicalTableReference, kind: String, predicate: String): String {
    val target = postgresRelation(table)
    val targetLiteral = postgresLiteral(target)
    val body = """
        DECLARE
            constraint_names text[];
        BEGIN
            LOCK TABLE $target IN ACCESS EXCLUSIVE MODE;
            SELECT pg_catalog.array_agg(c.conname::text) INTO constraint_names
            FROM pg_catalog.pg_constraint AS c
            WHERE c.conrelid = $targetLiteral::pg_catalog.regclass
              AND $predicate;
            IF coalesce(pg_catalog.cardinality(constraint_names), 0) <> 1 THEN
                RAISE EXCEPTION 'Expected exactly one $kind constraint on %, found %',
                    $targetLiteral, coalesce(pg_catalog.cardinality(constraint_names), 0);
            END IF;
            EXECUTE pg_catalog.format('ALTER TABLE %s DROP CONSTRAINT %I',
                $targetLiteral::pg_catalog.regclass, constraint_names[1]);
        END;
    """.trimIndent()
    var delimiter = "\$kagiroi_constraint\$"
    while (delimiter in body) delimiter = delimiter.dropLast(1) + "_\$"
    return "DO $delimiter\n$body\n$delimiter"
}

private fun postgresKeyColumns(key: String, relation: String): String = """
    ARRAY(
        SELECT a.attname::text
        FROM pg_catalog.unnest(c.$key) WITH ORDINALITY AS k(attnum, position)
        JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.$relation AND a.attnum = k.attnum
        ORDER BY k.position
    )
""".trimIndent()

private fun postgresColumnNames(columns: List<String>): String =
    "ARRAY[${columns.joinToString(", ", transform = ::postgresLiteral)}]::text[]"

private fun postgresRelation(table: CanonicalTableReference): String =
    listOfNotNull(table.schemaName, table.name).joinToString(".") { "\"${it.replace("\"", "\"\"")}\"" }

private fun postgresLiteral(value: String): String = "'${value.replace("'", "''")}'"

private fun postgresAction(action: CanonicalForeignKeyAction?): String = when (action) {
    null, CanonicalForeignKeyAction.NoAction -> "a"
    CanonicalForeignKeyAction.Restrict -> "r"
    CanonicalForeignKeyAction.Cascade -> "c"
    CanonicalForeignKeyAction.SetNull -> "n"
    CanonicalForeignKeyAction.SetDefault -> "d"
}
