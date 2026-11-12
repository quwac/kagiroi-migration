@file:Suppress("CyclomaticComplexMethod", "LargeClass", "LongMethod", "TooManyFunctions")

package dev.quwac.kagiroi.migration

/** A semantic DDL statement produced from a [MigrationPlan], independent of runtime Table objects. */
sealed interface MigrationDdlStatement {
    data class Security(val operation: SecurityMigrationOperation) : MigrationDdlStatement
    data class CreateTable(val table: CanonicalTable) : MigrationDdlStatement
    data class DropTable(val table: CanonicalTableReference) : MigrationDdlStatement
    data class RenameTable(val from: CanonicalTableReference, val to: CanonicalTableReference) : MigrationDdlStatement
    data class AddColumn(val table: CanonicalTableReference, val column: CanonicalColumn) : MigrationDdlStatement
    data class DropColumn(val column: CanonicalColumnReference) : MigrationDdlStatement
    data class RenameColumn(val table: CanonicalTableReference, val from: String, val to: String) :
        MigrationDdlStatement
    data class AlterColumnType(val column: CanonicalColumnReference, val type: CanonicalColumnType) :
        MigrationDdlStatement
    data class SetNotNull(val column: CanonicalColumnReference) : MigrationDdlStatement
    data class DropNotNull(val column: CanonicalColumnReference) : MigrationDdlStatement
    data class SetDefault(val column: CanonicalColumnReference, val default: CanonicalSchemaExpression) :
        MigrationDdlStatement
    data class DropDefault(val column: CanonicalColumnReference) : MigrationDdlStatement
    data class AddPrimaryKey(val table: CanonicalTableReference, val value: CanonicalPrimaryKey) : MigrationDdlStatement
    data class DropPrimaryKey(val table: CanonicalTableReference, val value: CanonicalPrimaryKey) :
        MigrationDdlStatement
    data class AddUnique(val table: CanonicalTableReference, val value: CanonicalUniqueConstraint) :
        MigrationDdlStatement
    data class DropUnique(val table: CanonicalTableReference, val value: CanonicalUniqueConstraint) :
        MigrationDdlStatement
    data class AddForeignKey(val table: CanonicalTableReference, val value: CanonicalForeignKey) : MigrationDdlStatement
    data class DropForeignKey(val table: CanonicalTableReference, val value: CanonicalForeignKey) :
        MigrationDdlStatement
    data class AddCheck(val table: CanonicalTableReference, val value: CanonicalCheckConstraint) : MigrationDdlStatement
    data class DropCheck(val table: CanonicalTableReference, val value: CanonicalCheckConstraint) :
        MigrationDdlStatement
    data class CreateIndex(val value: CanonicalIndex) : MigrationDdlStatement
    data class DropIndex(val value: CanonicalIndex) : MigrationDdlStatement
    data class RenameIndex(val from: CanonicalIndex, val to: CanonicalIndex) : MigrationDdlStatement
    data class CreateSequence(val value: CanonicalSequence) : MigrationDdlStatement
    data class DropSequence(val value: CanonicalSequence) : MigrationDdlStatement
    data class AlterIdentity(val column: CanonicalColumnReference) : MigrationDdlStatement
    data class AlterGeneratedColumn(val column: CanonicalColumnReference) : MigrationDdlStatement
    data class AlterColumnComment(val column: CanonicalColumnReference, val value: String?) : MigrationDdlStatement
    data class AlterTableComment(val table: CanonicalTableReference, val value: String?) : MigrationDdlStatement
    data class AlterSequence(val sequence: CanonicalSequence) : MigrationDdlStatement
}

/** Converts typed migration intent to immutable DDL semantics without rebuilding runtime schema objects. */
fun MigrationPlan.toDdlStatements(): List<MigrationDdlStatement> = operations.flatMap { operation ->
    when (operation) {
        is SecurityMigrationOperation -> listOf(MigrationDdlStatement.Security(operation))

        is CreateTable -> listOf(MigrationDdlStatement.CreateTable(operation.table))

        is DropTable -> listOf(MigrationDdlStatement.DropTable(operation.table))

        is RenameTable -> listOf(MigrationDdlStatement.RenameTable(operation.from, operation.to))

        is AddColumn -> listOf(MigrationDdlStatement.AddColumn(operation.table, operation.column))

        is DropColumn -> listOf(MigrationDdlStatement.DropColumn(operation.column))

        is RenameColumn -> listOf(MigrationDdlStatement.RenameColumn(operation.table, operation.from, operation.to))

        is AlterColumnType -> listOf(MigrationDdlStatement.AlterColumnType(operation.column, operation.to))

        is SetNotNull -> listOf(MigrationDdlStatement.SetNotNull(operation.column))

        is DropNotNull -> listOf(MigrationDdlStatement.DropNotNull(operation.column))

        is SetDefault -> listOf(MigrationDdlStatement.SetDefault(operation.column, operation.default))

        is DropDefault -> listOf(MigrationDdlStatement.DropDefault(operation.column))

        is AlterDefault -> listOf(MigrationDdlStatement.SetDefault(operation.column, operation.to))

        is AddPrimaryKey -> listOf(MigrationDdlStatement.AddPrimaryKey(operation.table, operation.primaryKey))

        is DropPrimaryKey -> listOf(MigrationDdlStatement.DropPrimaryKey(operation.table, operation.primaryKey))

        is AddUnique -> listOf(MigrationDdlStatement.AddUnique(operation.table, operation.unique))

        is DropUnique -> listOf(MigrationDdlStatement.DropUnique(operation.table, operation.unique))

        is AddForeignKey -> listOf(MigrationDdlStatement.AddForeignKey(operation.table, operation.foreignKey))

        is DropForeignKey -> listOf(MigrationDdlStatement.DropForeignKey(operation.table, operation.foreignKey))

        is AddCheck -> listOf(MigrationDdlStatement.AddCheck(operation.table, operation.check))

        is DropCheck -> listOf(MigrationDdlStatement.DropCheck(operation.table, operation.check))

        is CreateIndex -> listOf(MigrationDdlStatement.CreateIndex(operation.index))

        is DropIndex -> listOf(MigrationDdlStatement.DropIndex(operation.index))

        is RenameIndex -> listOf(MigrationDdlStatement.RenameIndex(operation.from, operation.to))

        is RecreateIndex -> listOf(
            MigrationDdlStatement.DropIndex(operation.previous),
            MigrationDdlStatement.CreateIndex(operation.replacement),
        )

        is CreateSequence -> listOf(MigrationDdlStatement.CreateSequence(operation.sequence))

        is DropSequence -> listOf(MigrationDdlStatement.DropSequence(operation.sequence))

        is AlterIdentity -> listOf(MigrationDdlStatement.AlterIdentity(operation.column))

        is AlterGeneratedColumn -> listOf(MigrationDdlStatement.AlterGeneratedColumn(operation.column))

        is AlterColumnComment -> listOf(MigrationDdlStatement.AlterColumnComment(operation.column, operation.to))

        is AlterTableComment -> listOf(MigrationDdlStatement.AlterTableComment(operation.table, operation.to))

        is AlterSequence -> listOf(MigrationDdlStatement.AlterSequence(operation.to))
    }
}

/** Stable dialect IDs persisted into SQL migration artifacts. */
enum class MigrationDialect(val id: String) {
    PostgreSql("postgresql"),
    MySql("mysql"),
    MariaDb("mariadb"),
    Sqlite("sqlite"),
    D1("d1"),
    SqlServer("sqlserver"),
    Oracle("oracle"),
    ;

    companion object {
        fun parse(value: String): MigrationDialect = entries.singleOrNull { it.id == value }
            ?: throw MigrationSqlGenerationException(value, "dialect", "Unknown Kagiroi migration dialect")
    }
}

/** One direct-execution SQL statement. No artifact wrapper or transaction control is included. */
data class MigrationSqlStatement(val sql: String)

/** A rendered, reviewable result retaining its semantic DDL source. */
data class MigrationSqlGeneration(
    val dialect: MigrationDialect,
    val ddl: List<MigrationDdlStatement>,
    val statements: List<MigrationSqlStatement>,
)

class MigrationSqlGenerationException(val dialect: String, val operation: String, detail: String) :
    IllegalArgumentException("Migration SQL generation is unsupported for $dialect $operation: $detail")

/**
 * Renders semantic migration DDL to complete raw SQL statements. It deliberately does not parse
 * SQL and never changes an operation into a different semantic operation for compatibility.
 */
object MigrationSqlGenerator {
    fun generate(plan: MigrationPlan, dialect: MigrationDialect): MigrationSqlGeneration {
        val ddl = plan.toDdlStatements()
        return MigrationSqlGeneration(
            dialect,
            ddl,
            ddl.map { statement -> MigrationSqlStatement(render(statement, dialect)) },
        )
    }

    private fun render(statement: MigrationDdlStatement, dialect: MigrationDialect): String {
        requireNativeSqliteAlterStatement(statement, dialect)
        return when (statement) {
            is MigrationDdlStatement.Security -> renderSecuritySql(
                statement.operation,
                dialect,
            ) { predicate(it, dialect) }

            is MigrationDdlStatement.CreateTable -> renderCreateTable(statement.table, dialect)

            is MigrationDdlStatement.DropTable -> "DROP TABLE ${table(statement.table, dialect)}"

            is MigrationDdlStatement.RenameTable -> renderRenameTable(statement, dialect)

            is MigrationDdlStatement.AddColumn -> "ALTER TABLE ${table(
                statement.table,
                dialect,
            )} ADD COLUMN ${column(statement.column, dialect)}"

            is MigrationDdlStatement.DropColumn -> "ALTER TABLE ${table(
                statement.column.table,
                dialect,
            )} DROP COLUMN ${id(statement.column.name, dialect)}"

            is MigrationDdlStatement.RenameColumn -> renderRenameColumn(statement, dialect)

            is MigrationDdlStatement.AlterColumnType -> renderAlterColumnType(statement, dialect)

            is MigrationDdlStatement.SetNotNull -> renderNullability(statement.column, false, dialect)

            is MigrationDdlStatement.DropNotNull -> renderNullability(statement.column, true, dialect)

            is MigrationDdlStatement.SetDefault -> renderSetDefault(statement.column, statement.default, dialect)

            is MigrationDdlStatement.DropDefault -> renderDropDefault(statement.column, dialect)

            is MigrationDdlStatement.AddPrimaryKey -> "ALTER TABLE ${table(
                statement.table,
                dialect,
            )} ADD ${primaryKey(statement.value, dialect)}"

            is MigrationDdlStatement.DropPrimaryKey -> renderDropPrimaryKey(statement, dialect)

            is MigrationDdlStatement.AddUnique -> "ALTER TABLE ${table(
                statement.table,
                dialect,
            )} ADD ${unique(statement.value, dialect)}"

            is MigrationDdlStatement.DropUnique -> renderDropConstraint(
                statement.table,
                statement.value.name,
                "UNIQUE",
                dialect,
            )

            is MigrationDdlStatement.AddForeignKey -> "ALTER TABLE ${table(
                statement.table,
                dialect,
            )} ADD ${foreignKey(statement.value, dialect)}"

            is MigrationDdlStatement.DropForeignKey -> renderDropConstraint(
                statement.table,
                statement.value.name,
                "FOREIGN KEY",
                dialect,
            )

            is MigrationDdlStatement.AddCheck -> "ALTER TABLE ${table(
                statement.table,
                dialect,
            )} ADD ${check(statement.value, dialect)}"

            is MigrationDdlStatement.DropCheck -> renderDropConstraint(
                statement.table,
                statement.value.name,
                "CHECK",
                dialect,
            )

            is MigrationDdlStatement.CreateIndex -> renderCreateIndex(statement.value, dialect)

            is MigrationDdlStatement.DropIndex -> renderDropIndex(statement.value, dialect)

            is MigrationDdlStatement.RenameIndex -> renderRenameIndex(statement, dialect)

            is MigrationDdlStatement.CreateSequence -> {
                requireSequences(dialect, "CREATE SEQUENCE")
                "CREATE SEQUENCE ${qualified(statement.value.schemaName, statement.value.name, dialect)}"
            }

            is MigrationDdlStatement.DropSequence -> {
                requireSequences(dialect, "DROP SEQUENCE")
                "DROP SEQUENCE ${qualified(statement.value.schemaName, statement.value.name, dialect)}"
            }

            is MigrationDdlStatement.AlterIdentity -> unsupported(
                dialect,
                "ALTER IDENTITY",
                "identity alteration has no portable native Typed DDL form",
            )

            is MigrationDdlStatement.AlterGeneratedColumn -> unsupported(
                dialect,
                "ALTER GENERATED COLUMN",
                "generated-column alteration has no native Typed DDL form",
            )

            is MigrationDdlStatement.AlterColumnComment -> renderColumnComment(statement, dialect)

            is MigrationDdlStatement.AlterTableComment -> renderTableComment(statement, dialect)

            is MigrationDdlStatement.AlterSequence -> unsupported(
                dialect,
                "ALTER SEQUENCE",
                "sequence options are not yet modeled",
            )
        }
    }

    /** SQLite and D1 never get a synthetic table rebuild for operations outside their native ALTER subset. */
    private fun requireNativeSqliteAlterStatement(statement: MigrationDdlStatement, dialect: MigrationDialect) {
        if (dialect !in setOf(MigrationDialect.Sqlite, MigrationDialect.D1)) return
        when (statement) {
            is MigrationDdlStatement.CreateTable,
            is MigrationDdlStatement.DropTable,
            is MigrationDdlStatement.RenameTable,
            is MigrationDdlStatement.AddColumn,
            is MigrationDdlStatement.DropColumn,
            is MigrationDdlStatement.RenameColumn,
            is MigrationDdlStatement.CreateIndex,
            is MigrationDdlStatement.DropIndex,
            -> Unit

            else -> unsupported(
                dialect,
                "SQLite ALTER TABLE operation",
                "SQLite supports this migration operation only through a table rebuild, which Kagiroi does not perform",
            )
        }
    }

    private fun renderCreateTable(value: CanonicalTable, dialect: MigrationDialect): String {
        if (value.comment != null || value.columns.any { it.comment != null } ||
            value.uniqueConstraints.any { it.comment != null }
        ) {
            unsupported(dialect, "CREATE TABLE comments", "comment DDL must be emitted as explicit comment operations")
        }
        val definitions = value.columns.map { column(it, dialect) }.toMutableList()
        value.primaryKey?.let { definitions += primaryKey(it, dialect) }
        value.uniqueConstraints.forEach { definitions += unique(it, dialect) }
        value.foreignKeys.forEach { definitions += foreignKey(it, dialect) }
        value.checks.forEach { definitions += check(it, dialect) }
        return "CREATE TABLE ${table(
            CanonicalTableReference(value.schemaName, value.name),
            dialect,
        )} (${definitions.joinToString(", ")})"
    }

    private fun renderRenameTable(statement: MigrationDdlStatement.RenameTable, dialect: MigrationDialect): String =
        when (dialect) {
            MigrationDialect.SqlServer -> renameSqlServer(statement.from, statement.to.name, null, dialect)
            else -> "ALTER TABLE ${table(statement.from, dialect)} RENAME TO ${id(statement.to.name, dialect)}"
        }

    private fun renderRenameColumn(statement: MigrationDdlStatement.RenameColumn, dialect: MigrationDialect): String =
        when (dialect) {
            MigrationDialect.SqlServer -> renameSqlServer(statement.table, statement.to, statement.from, dialect)

            else -> "ALTER TABLE ${table(
                statement.table,
                dialect,
            )} RENAME COLUMN ${id(statement.from, dialect)} TO ${id(statement.to, dialect)}"
        }

    private fun renameSqlServer(
        table: CanonicalTableReference,
        newName: String,
        oldColumn: String?,
        dialect: MigrationDialect,
    ): String {
        val current = table(table, dialect) + oldColumn?.let { ".${id(it, dialect)}" }.orEmpty()
        val objectType = if (oldColumn == null) "" else ", N'COLUMN'"
        return "EXEC sp_rename N'${current.replace("'", "''")}', N'${newName.replace("'", "''")}'$objectType"
    }

    private fun renderAlterColumnType(
        statement: MigrationDdlStatement.AlterColumnType,
        dialect: MigrationDialect,
    ): String {
        val reference = statement.column
        val type = type(statement.type, dialect)
        return when (dialect) {
            MigrationDialect.PostgreSql -> "ALTER TABLE ${table(
                reference.table,
                dialect,
            )} ALTER COLUMN ${id(reference.name, dialect)} TYPE $type"

            MigrationDialect.SqlServer -> unsupported(
                dialect,
                "ALTER COLUMN TYPE",
                "nullability cannot be inferred from a canonical type-only change",
            )

            MigrationDialect.Oracle -> "ALTER TABLE ${table(
                reference.table,
                dialect,
            )} MODIFY (${id(reference.name, dialect)} $type)"

            MigrationDialect.MySql, MigrationDialect.MariaDb -> unsupported(
                dialect,
                "ALTER COLUMN TYPE",
                "native Typed DDL requires a complete column definition",
            )

            MigrationDialect.Sqlite, MigrationDialect.D1 -> unsupported(
                dialect,
                "ALTER COLUMN TYPE",
                "SQLite has no native ALTER COLUMN TYPE",
            )
        }
    }

    private fun renderNullability(
        reference: CanonicalColumnReference,
        nullable: Boolean,
        dialect: MigrationDialect,
    ): String = when (dialect) {
        MigrationDialect.PostgreSql -> "ALTER TABLE ${table(
            reference.table,
            dialect,
        )} ALTER COLUMN ${id(reference.name, dialect)} " +
            if (nullable) "DROP NOT NULL" else "SET NOT NULL"

        MigrationDialect.Oracle -> "ALTER TABLE ${table(
            reference.table,
            dialect,
        )} MODIFY (${id(reference.name, dialect)} " +
            if (nullable) "NULL)" else "NOT NULL)"

        MigrationDialect.SqlServer -> unsupported(
            dialect,
            "ALTER COLUMN NULLABILITY",
            "native Typed DDL requires the current column type",
        )

        MigrationDialect.MySql, MigrationDialect.MariaDb -> unsupported(
            dialect,
            "ALTER COLUMN NULLABILITY",
            "native Typed DDL requires a complete column definition",
        )

        MigrationDialect.Sqlite, MigrationDialect.D1 -> unsupported(
            dialect,
            "ALTER COLUMN NULLABILITY",
            "SQLite has no native ALTER COLUMN nullability",
        )
    }

    private fun renderSetDefault(
        reference: CanonicalColumnReference,
        default: CanonicalSchemaExpression,
        dialect: MigrationDialect,
    ): String = when (dialect) {
        MigrationDialect.PostgreSql -> "ALTER TABLE ${table(
            reference.table,
            dialect,
        )} ALTER COLUMN ${id(reference.name, dialect)} SET DEFAULT ${expression(default, dialect)}"

        MigrationDialect.MySql, MigrationDialect.MariaDb -> "ALTER TABLE ${table(
            reference.table,
            dialect,
        )} ALTER COLUMN ${id(reference.name, dialect)} SET DEFAULT ${expression(default, dialect)}"

        MigrationDialect.Oracle -> "ALTER TABLE ${table(
            reference.table,
            dialect,
        )} MODIFY (${id(reference.name, dialect)} DEFAULT ${expression(default, dialect)})"

        MigrationDialect.SqlServer -> unsupported(
            dialect,
            "ALTER COLUMN DEFAULT",
            "SQL Server default constraints require an explicit constraint name",
        )

        MigrationDialect.Sqlite, MigrationDialect.D1 -> unsupported(
            dialect,
            "ALTER COLUMN DEFAULT",
            "SQLite has no native ALTER COLUMN DEFAULT",
        )
    }

    private fun renderDropDefault(reference: CanonicalColumnReference, dialect: MigrationDialect): String =
        when (dialect) {
            MigrationDialect.PostgreSql -> "ALTER TABLE ${table(
                reference.table,
                dialect,
            )} ALTER COLUMN ${id(reference.name, dialect)} DROP DEFAULT"

            MigrationDialect.MySql, MigrationDialect.MariaDb -> "ALTER TABLE ${table(
                reference.table,
                dialect,
            )} ALTER COLUMN ${id(reference.name, dialect)} DROP DEFAULT"

            MigrationDialect.Oracle -> "ALTER TABLE ${table(
                reference.table,
                dialect,
            )} MODIFY (${id(reference.name, dialect)} DEFAULT NULL)"

            MigrationDialect.SqlServer -> unsupported(
                dialect,
                "ALTER COLUMN DEFAULT",
                "SQL Server default constraints require their explicit name",
            )

            MigrationDialect.Sqlite, MigrationDialect.D1 -> unsupported(
                dialect,
                "ALTER COLUMN DEFAULT",
                "SQLite has no native ALTER COLUMN DEFAULT",
            )
        }

    private fun renderDropPrimaryKey(
        statement: MigrationDdlStatement.DropPrimaryKey,
        dialect: MigrationDialect,
    ): String = when (dialect) {
        MigrationDialect.MySql, MigrationDialect.MariaDb -> "ALTER TABLE ${table(
            statement.table,
            dialect,
        )} DROP PRIMARY KEY"

        else -> renderDropConstraint(statement.table, statement.value.name, "PRIMARY KEY", dialect)
    }

    private fun renderDropConstraint(
        table: CanonicalTableReference,
        name: String?,
        kind: String,
        dialect: MigrationDialect,
    ): String {
        val requiredName = requireNotNull(name) {
            "$dialect $kind removal requires an explicitly named constraint"
        }
        return when (dialect) {
            MigrationDialect.MySql, MigrationDialect.MariaDb -> when (kind) {
                "UNIQUE" -> "ALTER TABLE ${table(table, dialect)} DROP INDEX ${id(requiredName, dialect)}"
                "FOREIGN KEY" -> "ALTER TABLE ${table(table, dialect)} DROP FOREIGN KEY ${id(requiredName, dialect)}"
                "CHECK" -> "ALTER TABLE ${table(table, dialect)} DROP CHECK ${id(requiredName, dialect)}"
                else -> unsupported(dialect, "DROP $kind", "named constraint removal is not modeled")
            }

            else -> "ALTER TABLE ${table(table, dialect)} DROP CONSTRAINT ${id(requiredName, dialect)}"
        }
    }

    private fun renderCreateIndex(value: CanonicalIndex, dialect: MigrationDialect): String {
        if (value.comment != null) {
            unsupported(dialect, "CREATE INDEX comment", "comment DDL must be emitted as an explicit comment operation")
        }
        if (value.predicate != null && dialect !in setOf(
                MigrationDialect.PostgreSql,
                MigrationDialect.SqlServer,
                MigrationDialect.Sqlite,
                MigrationDialect.D1,
            )
        ) {
            unsupported(dialect, "CREATE INDEX predicate", "partial indexes are not supported by native Typed DDL")
        }
        if (value.includedColumns.isNotEmpty() && dialect != MigrationDialect.SqlServer) {
            unsupported(dialect, "CREATE INDEX included columns", "included columns are supported only by SQL Server")
        }
        val keys = value.keys.joinToString(", ") { key -> indexKey(key, dialect) }
        return buildString {
            append("CREATE ")
            if (value.unique) append("UNIQUE ")
            append("INDEX ").append(qualified(value.schemaName, value.name, dialect))
            append(" ON ").append(table(value.table, dialect))
            append(" (").append(keys).append(')')
            if (value.includedColumns.isNotEmpty()) {
                append(
                    " INCLUDE (",
                ).append(value.includedColumns.joinToString(", ") { id(it.name, dialect) }).append(')')
            }
            value.predicate?.let { append(" WHERE ").append(predicate(it, dialect)) }
        }
    }

    private fun renderDropIndex(value: CanonicalIndex, dialect: MigrationDialect): String = when (dialect) {
        MigrationDialect.MySql, MigrationDialect.MariaDb -> "DROP INDEX ${id(
            value.name,
            dialect,
        )} ON ${table(value.table, dialect)}"

        MigrationDialect.SqlServer -> "DROP INDEX ${id(value.name, dialect)} ON ${table(value.table, dialect)}"

        else -> "DROP INDEX ${qualified(value.schemaName, value.name, dialect)}"
    }

    private fun renderRenameIndex(statement: MigrationDdlStatement.RenameIndex, dialect: MigrationDialect): String =
        when (dialect) {
            MigrationDialect.PostgreSql -> "ALTER INDEX ${qualified(
                statement.from.schemaName,
                statement.from.name,
                dialect,
            )} RENAME TO ${id(statement.to.name, dialect)}"

            MigrationDialect.SqlServer -> "EXEC sp_rename N'${table(
                statement.from.table,
                dialect,
            )}.${id(statement.from.name, dialect)}', N'${statement.to.name.replace("'", "''")}', N'INDEX'"

            else -> unsupported(dialect, "RENAME INDEX", "native Typed DDL does not support index rename")
        }

    private fun renderColumnComment(
        statement: MigrationDdlStatement.AlterColumnComment,
        dialect: MigrationDialect,
    ): String = when (dialect) {
        MigrationDialect.PostgreSql -> "COMMENT ON COLUMN ${table(
            statement.column.table,
            dialect,
        )}.${id(statement.column.name, dialect)} IS ${nullableString(statement.value, dialect)}"

        MigrationDialect.Oracle -> "COMMENT ON COLUMN ${table(
            statement.column.table,
            dialect,
        )}.${id(statement.column.name, dialect)} IS ${nullableString(statement.value, dialect)}"

        else -> unsupported(dialect, "COLUMN COMMENT", "native Typed DDL does not persist column comments")
    }

    private fun renderTableComment(
        statement: MigrationDdlStatement.AlterTableComment,
        dialect: MigrationDialect,
    ): String = when (dialect) {
        MigrationDialect.PostgreSql, MigrationDialect.Oracle -> "COMMENT ON TABLE ${table(
            statement.table,
            dialect,
        )} IS ${nullableString(statement.value, dialect)}"

        else -> unsupported(dialect, "TABLE COMMENT", "native Typed DDL does not persist table comments")
    }

    private fun column(value: CanonicalColumn, dialect: MigrationDialect): String = buildString {
        append(id(value.name, dialect)).append(' ').append(type(value.type, dialect))
        when {
            value.identity != null -> append(identity(dialect))

            value.generated != null -> append(
                " GENERATED ALWAYS AS (",
            ).append(expression(value.generated.expression, dialect)).append(") ")
                .append(if (value.generated.storage == CanonicalGeneratedColumnStorage.Stored) "STORED" else "VIRTUAL")

            else -> value.default?.let { append(" DEFAULT ").append(expression(it, dialect)) }
        }
        if (!value.nullable) append(" NOT NULL")
        if (value.primaryKey) {
            append(" PRIMARY KEY")
        } else if (value.unique) {
            append(" UNIQUE")
        }
    }

    private fun identity(dialect: MigrationDialect): String = when (dialect) {
        MigrationDialect.PostgreSql -> " GENERATED BY DEFAULT AS IDENTITY"
        MigrationDialect.MySql, MigrationDialect.MariaDb -> " AUTO_INCREMENT"
        MigrationDialect.Sqlite, MigrationDialect.D1 -> " AUTOINCREMENT"
        MigrationDialect.SqlServer -> " IDENTITY(1,1)"
        MigrationDialect.Oracle -> " GENERATED BY DEFAULT AS IDENTITY"
    }

    private fun primaryKey(value: CanonicalPrimaryKey, dialect: MigrationDialect): String = buildString {
        value.name?.let { append("CONSTRAINT ").append(id(it, dialect)).append(' ') }
        append("PRIMARY KEY (").append(value.columns.joinToString(", ") { id(it, dialect) }).append(')')
    }

    private fun unique(value: CanonicalUniqueConstraint, dialect: MigrationDialect): String = buildString {
        value.name?.let { append("CONSTRAINT ").append(id(it, dialect)).append(' ') }
        append("UNIQUE (").append(value.columns.joinToString(", ") { id(it, dialect) }).append(')')
    }

    private fun foreignKey(value: CanonicalForeignKey, dialect: MigrationDialect): String = buildString {
        value.name?.let { append("CONSTRAINT ").append(id(it, dialect)).append(' ') }
        append(
            "FOREIGN KEY (",
        ).append(value.sourceColumns.joinToString(", ") { id(it, dialect) }).append(") REFERENCES ")
        append(
            table(value.targetTable, dialect),
        ).append(" (").append(value.targetColumns.joinToString(", ") { id(it, dialect) }).append(')')
        value.onDelete?.let { append(action("DELETE", it, dialect)) }
        value.onUpdate?.let { append(action("UPDATE", it, dialect)) }
    }

    private fun action(kind: String, action: CanonicalForeignKeyAction, dialect: MigrationDialect): String {
        if (dialect == MigrationDialect.Oracle && kind == "UPDATE") {
            unsupported(dialect, "FOREIGN KEY ON UPDATE", "Oracle does not support ON UPDATE actions")
        }
        if ((dialect == MigrationDialect.MySql || dialect == MigrationDialect.MariaDb) &&
            action == CanonicalForeignKeyAction.SetDefault
        ) {
            unsupported(dialect, "FOREIGN KEY SET DEFAULT", "the default InnoDB engine does not support SET DEFAULT")
        }
        if (dialect == MigrationDialect.SqlServer && action == CanonicalForeignKeyAction.Restrict) {
            unsupported(dialect, "FOREIGN KEY RESTRICT", "SQL Server supports NO ACTION, not RESTRICT")
        }
        val sql = when (action) {
            CanonicalForeignKeyAction.Cascade -> "CASCADE"
            CanonicalForeignKeyAction.Restrict -> "RESTRICT"
            CanonicalForeignKeyAction.NoAction -> "NO ACTION"
            CanonicalForeignKeyAction.SetNull -> "SET NULL"
            CanonicalForeignKeyAction.SetDefault -> "SET DEFAULT"
        }
        return " ON $kind $sql"
    }

    private fun check(value: CanonicalCheckConstraint, dialect: MigrationDialect): String = buildString {
        value.name?.let { append("CONSTRAINT ").append(id(it, dialect)).append(' ') }
        append("CHECK (").append(predicate(value.expression, dialect)).append(')')
    }

    private fun indexKey(value: CanonicalIndexKey, dialect: MigrationDialect): String = when (value) {
        is CanonicalColumnIndexKey -> id(value.column.name, dialect)
        is CanonicalExpressionIndexKey -> "(${expression(value.expression, dialect)})"
    } + when (value.direction) {
        CanonicalIndexDirection.Asc -> " ASC"
        CanonicalIndexDirection.Desc -> " DESC"
        null -> ""
    }

    private fun expression(value: CanonicalSchemaExpression, dialect: MigrationDialect): String = when (value) {
        is CanonicalColumnExpression -> id(value.column.name, dialect)

        is CanonicalLiteralExpression -> literal(value.value, dialect)

        is CanonicalSequenceNextValueExpression -> {
            requireSequences(dialect, "sequence DEFAULT expression")
            when (dialect) {
                MigrationDialect.Oracle -> "${qualified(
                    value.sequence.schemaName,
                    value.sequence.name,
                    dialect,
                )}.NEXTVAL"

                else -> "nextval(${string(value.sequence.name, dialect)})"
            }
        }

        is CanonicalFunctionExpression -> function(value, dialect)

        is CanonicalBinaryExpression -> "(${expression(
            value.left,
            dialect,
        )} ${binaryOperator(value.operator)} ${expression(value.right, dialect)})"

        is CanonicalCastExpression -> "CAST(${expression(
            value.expression,
            dialect,
        )} AS ${type(value.targetType, dialect)})"

        is CanonicalCaseExpression -> buildString {
            append("CASE")
            value.branches.forEach { branch ->
                append(
                    " WHEN ",
                ).append(predicate(branch.condition, dialect))
                    .append(" THEN ")
                    .append(expression(branch.result, dialect))
            }
            append(" ELSE ").append(expression(value.elseResult, dialect)).append(" END")
        }
    }

    private fun predicate(value: CanonicalSchemaPredicate, dialect: MigrationDialect): String = when (value) {
        is CanonicalValueComparison -> "${expression(
            value.expression,
            dialect,
        )} ${comparison(value.operator)} ${literal(value.value, dialect)}"

        is CanonicalExpressionComparison -> "${expression(
            value.left,
            dialect,
        )} ${comparison(value.operator)} ${expression(value.right, dialect)}"

        is CanonicalLikeValuePredicate -> "${expression(
            value.expression,
            dialect,
        )} ${like(value.operator, dialect)} ${string(value.pattern, dialect)}"

        is CanonicalLikeExpressionPredicate -> "${expression(
            value.expression,
            dialect,
        )} ${like(value.operator, dialect)} ${expression(value.pattern, dialect)}"

        is CanonicalNullSafeComparison -> nullSafe(value, dialect)

        is CanonicalNullPredicate -> "${expression(
            value.expression,
            dialect,
        )} ${if (value.operator == CanonicalNullOperator.IsNull) "IS NULL" else "IS NOT NULL"}"

        is CanonicalLogicalPredicate -> "(${predicate(
            value.left,
            dialect,
        )} ${if (value.operator == CanonicalLogicalOperator.And) "AND" else "OR"} ${predicate(value.right, dialect)})"
    }

    private fun nullSafe(value: CanonicalNullSafeComparison, dialect: MigrationDialect): String = when (dialect) {
        MigrationDialect.PostgreSql -> "${expression(
            value.left,
            dialect,
        )} IS ${if (value.distinct) "DISTINCT FROM" else "NOT DISTINCT FROM"} ${expression(value.right, dialect)}"

        MigrationDialect.MySql, MigrationDialect.MariaDb -> if (value.distinct) {
            "NOT (${expression(value.left, dialect)} <=> ${expression(value.right, dialect)})"
        } else {
            "${expression(value.left, dialect)} <=> ${expression(value.right, dialect)}"
        }

        else -> unsupported(
            dialect,
            "NULL-SAFE comparison",
            "the native Typed DDL renderer does not emulate null-safe comparison",
        )
    }

    private fun type(value: CanonicalColumnType, dialect: MigrationDialect): String {
        val standard = when (dialect) {
            MigrationDialect.PostgreSql -> mapOf(
                "kagiroi.string" to "TEXT", "kagiroi.int8" to "SMALLINT", "kagiroi.int16" to "SMALLINT",
                "kagiroi.int32" to "INTEGER", "kagiroi.int64" to "BIGINT", "kagiroi.float32" to "REAL",
                "kagiroi.float64" to "DOUBLE PRECISION", "kagiroi.boolean" to "BOOLEAN", "kagiroi.bytes" to "BYTEA",
                "kagiroi.instant" to "TIMESTAMP WITH TIME ZONE", "kagiroi.duration" to "INTERVAL",
                "kagiroi.uuid" to "UUID",
            )

            MigrationDialect.MySql, MigrationDialect.MariaDb -> mapOf(
                "kagiroi.string" to "TEXT", "kagiroi.int8" to "TINYINT", "kagiroi.int16" to "SMALLINT",
                "kagiroi.int32" to "INT", "kagiroi.int64" to "BIGINT", "kagiroi.float32" to "FLOAT",
                "kagiroi.float64" to "DOUBLE", "kagiroi.boolean" to "BOOLEAN", "kagiroi.bytes" to "BLOB",
                "kagiroi.instant" to "TIMESTAMP", "kagiroi.duration" to "TIME", "kagiroi.uuid" to "CHAR(36)",
            )

            MigrationDialect.Sqlite, MigrationDialect.D1 -> mapOf(
                "kagiroi.string" to "TEXT", "kagiroi.int8" to "INTEGER", "kagiroi.int16" to "INTEGER",
                "kagiroi.int32" to "INTEGER", "kagiroi.int64" to "INTEGER", "kagiroi.float32" to "REAL",
                "kagiroi.float64" to "REAL", "kagiroi.boolean" to "INTEGER", "kagiroi.bytes" to "BLOB",
                "kagiroi.instant" to "TEXT", "kagiroi.duration" to "TEXT", "kagiroi.uuid" to "TEXT",
            )

            MigrationDialect.SqlServer -> mapOf(
                "kagiroi.string" to "NVARCHAR(MAX)", "kagiroi.int8" to "SMALLINT", "kagiroi.int16" to "SMALLINT",
                "kagiroi.int32" to "INT", "kagiroi.int64" to "BIGINT", "kagiroi.float32" to "REAL",
                "kagiroi.float64" to "FLOAT", "kagiroi.boolean" to "BIT", "kagiroi.bytes" to "VARBINARY(MAX)",
                "kagiroi.instant" to "DATETIMEOFFSET", "kagiroi.duration" to "NVARCHAR(MAX)",
                "kagiroi.uuid" to "UNIQUEIDENTIFIER",
            )

            MigrationDialect.Oracle -> mapOf(
                "kagiroi.string" to "VARCHAR2(4000)", "kagiroi.int8" to "NUMBER(3)", "kagiroi.int16" to "NUMBER(5)",
                "kagiroi.int32" to "NUMBER(10)", "kagiroi.int64" to "NUMBER(19)", "kagiroi.float32" to "BINARY_FLOAT",
                "kagiroi.float64" to "BINARY_DOUBLE", "kagiroi.boolean" to "BOOLEAN", "kagiroi.bytes" to "BLOB",
                "kagiroi.instant" to "TIMESTAMP WITH TIME ZONE", "kagiroi.duration" to "INTERVAL DAY TO SECOND",
                "kagiroi.uuid" to "VARCHAR2(36)",
            )
        }
        return standard[value.id] ?: unsupported(
            dialect,
            "column type ${value.id}",
            "no canonical physical type renderer is registered",
        )
    }

    private fun literal(value: CanonicalLiteralValue, dialect: MigrationDialect): String = when (value) {
        CanonicalNullValue -> "NULL"

        is CanonicalBooleanValue -> when (dialect) {
            MigrationDialect.SqlServer, MigrationDialect.Sqlite, MigrationDialect.D1 -> if (value.value) "1" else "0"
            else -> if (value.value) "TRUE" else "FALSE"
        }

        is CanonicalStringValue -> string(value.value, dialect)

        is CanonicalIntegerValue -> value.value

        is CanonicalDecimalValue -> value.value

        is CanonicalBytesValue -> when (dialect) {
            MigrationDialect.PostgreSql -> "E'\\\\x${value.hexadecimal}'"
            MigrationDialect.SqlServer -> "0x${value.hexadecimal}"
            else -> "X'${value.hexadecimal}'"
        }
    }

    private fun function(value: CanonicalFunctionExpression, dialect: MigrationDialect): String {
        val name = when (value.function) {
            "postgresCurrentSetting" -> {
                if (dialect != MigrationDialect.PostgreSql) {
                    unsupported(
                        dialect,
                        "current_setting",
                        "requires PostgreSQL",
                    )
                }
                "current_setting"
            }

            "currentDate" -> "CURRENT_DATE"

            "currentTime" -> "CURRENT_TIME"

            "currentTimestamp" -> "CURRENT_TIMESTAMP"

            else -> value.function.removePrefix("unsafe:")
        }
        return if (name.startsWith("CURRENT_")) {
            name
        } else {
            "$name(${value.arguments.joinToString(", ") { expression(it, dialect) }})"
        }
    }

    private fun comparison(value: CanonicalComparisonOperator): String = when (value) {
        CanonicalComparisonOperator.Eq -> "="
        CanonicalComparisonOperator.Neq -> "<>"
        CanonicalComparisonOperator.Lt -> "<"
        CanonicalComparisonOperator.Lte -> "<="
        CanonicalComparisonOperator.Gt -> ">"
        CanonicalComparisonOperator.Gte -> ">="
    }

    private fun like(value: CanonicalLikeOperator, dialect: MigrationDialect): String = when (value) {
        CanonicalLikeOperator.Like -> "LIKE"

        CanonicalLikeOperator.NotLike -> "NOT LIKE"

        CanonicalLikeOperator.ILike ->
            if (dialect == MigrationDialect.PostgreSql) {
                "ILIKE"
            } else {
                unsupported(
                    dialect,
                    "ILIKE",
                    "native Typed DDL does not emulate ILIKE",
                )
            }

        CanonicalLikeOperator.NotILike ->
            if (dialect == MigrationDialect.PostgreSql) {
                "NOT ILIKE"
            } else {
                unsupported(
                    dialect,
                    "NOT ILIKE",
                    "native Typed DDL does not emulate ILIKE",
                )
            }
    }

    private fun binaryOperator(value: CanonicalBinaryOperator): String = when (value) {
        CanonicalBinaryOperator.Add -> "+"
        CanonicalBinaryOperator.Subtract -> "-"
        CanonicalBinaryOperator.Multiply -> "*"
        CanonicalBinaryOperator.Divide -> "/"
        CanonicalBinaryOperator.Modulo -> "%"
    }

    private fun id(value: String, dialect: MigrationDialect): String = when (dialect) {
        MigrationDialect.MySql, MigrationDialect.MariaDb -> "`${value.replace("`", "``")}`"
        MigrationDialect.SqlServer -> sqlServerIdentifier(value)
        else -> "\"${value.replace("\"", "\"\"")}\""
    }

    private fun table(value: CanonicalTableReference, dialect: MigrationDialect): String = qualified(
        value.schemaName,
        value.name,
        dialect,
    )

    private fun sqlServerIdentifier(value: String): String = buildString {
        append('[')
        value.forEach { character -> if (character == ']') append("]]") else append(character) }
        append(']')
    }

    private fun qualified(schema: String?, name: String, dialect: MigrationDialect): String = listOfNotNull(
        schema,
        name,
    ).joinToString(".") { id(it, dialect) }

    private fun string(value: String, dialect: MigrationDialect): String = when (dialect) {
        MigrationDialect.SqlServer -> "N'${value.replace("'", "''")}'"
        else -> "'${value.replace("'", "''")}'"
    }

    private fun nullableString(value: String?, dialect: MigrationDialect): String = value?.let {
        string(
            it,
            dialect,
        )
    } ?: "NULL"

    private fun requireSequences(dialect: MigrationDialect, operation: String) {
        when (dialect) {
            MigrationDialect.MySql -> unsupported(dialect, operation, "MySQL has no independent sequence objects")

            MigrationDialect.Sqlite, MigrationDialect.D1 -> unsupported(
                dialect,
                operation,
                "SQLite has no independent sequence objects",
            )

            else -> Unit
        }
    }

    private fun unsupported(dialect: MigrationDialect, operation: String, detail: String): Nothing =
        throw MigrationSqlGenerationException(dialect.id, operation, detail)
}

/** SQL rendering with dialect-specific transaction boundaries. */
data class GeneratedMigrationScript(val dialect: MigrationDialect, val body: String)

/**
 * Generates directly executable SQL and keeps transaction policy in the artifact body.
 * MySQL, MariaDB, and Oracle DDL may commit implicitly, so their generated bodies are not wrapped.
 */
fun generateMigrationScript(plan: MigrationPlan, dialect: MigrationDialect): GeneratedMigrationScript {
    require(plan.operations.isNotEmpty()) { "Cannot generate an empty migration script" }
    val pieces = plan.operations.map { operation ->
        val statements = MigrationSqlGenerator.generate(MigrationPlan.Changes(listOf(operation)), dialect).statements
        buildString {
            statements.forEach { statement ->
                if (dialect == MigrationDialect.Oracle && operation is SecurityMigrationOperation) {
                    append(statement.sql.trimEnd().removeSuffix(";")).append(";\n/\n")
                } else {
                    append(statement.sql).append(";\n")
                }
            }
        }.trimEnd()
    }
    val sql = pieces.joinToString("\n\n")
    val body = if (
        dialect in setOf(
            MigrationDialect.PostgreSql,
            MigrationDialect.Sqlite,
            MigrationDialect.D1,
            MigrationDialect.SqlServer,
        )
    ) {
        "BEGIN;\n\n$sql\n\nCOMMIT;\n"
    } else {
        "$sql\n"
    }
    return GeneratedMigrationScript(
        dialect = dialect,
        body = body,
    )
}
