@file:Suppress("CyclomaticComplexMethod", "LongMethod", "TooManyFunctions")

package dev.quwac.kagiroi.migration

internal fun renderSecuritySql(
    operation: SecurityMigrationOperation,
    dialect: MigrationDialect,
    predicate: (CanonicalSchemaPredicate) -> String,
): String {
    val required = when (operation) {
        is EnableRowLevelSecurity, is DisableRowLevelSecurity, is ForceRowLevelSecurity,
        is CreatePostgresPolicy, is DropPostgresPolicy,
        -> MigrationDialect.PostgreSql

        is CreateSecurityPolicy, is DropSecurityPolicy, is SetSecurityPolicyEnabled,
        is AddSecurityPredicate, is DropSecurityPredicate,
        -> MigrationDialect.SqlServer

        is CreateVpdPolicy, is DropVpdPolicy, is SetVpdPolicyEnabled -> MigrationDialect.Oracle
    }
    if (dialect != required) {
        throw MigrationSqlGenerationException(
            dialect.id,
            "native security",
            "requires ${required.id}",
        )
    }
    return when (operation) {
        is EnableRowLevelSecurity -> "ALTER TABLE ${table(operation.table, dialect)} ENABLE ROW LEVEL SECURITY"

        is DisableRowLevelSecurity -> "ALTER TABLE ${table(operation.table, dialect)} DISABLE ROW LEVEL SECURITY"

        is ForceRowLevelSecurity -> "ALTER TABLE ${table(
            operation.table,
            dialect,
        )} ${if (operation.force) "FORCE" else "NO FORCE"} ROW LEVEL SECURITY"

        is CreatePostgresPolicy -> buildString {
            val policy = operation.policy
            val definition = policy.definition
            append(
                "CREATE POLICY ${id(
                    requireNotNull(policy.identity.policyName),
                    dialect,
                )} ON ${table(policy.identity.tableReference(), dialect)}",
            )
            if (definition.mode == CanonicalPostgreSqlPolicyMode.Restrictive) append(" AS RESTRICTIVE")
            if (definition.command != CanonicalPostgreSqlPolicyCommand.All) {
                append(
                    " FOR ${definition.command.name.uppercase()}",
                )
            }
            if (definition.roles != listOf(CanonicalPostgreSqlRole(CanonicalPostgreSqlPrincipal.Public))) {
                append(" TO ")
                append(
                    definition.roles.map { role ->
                        when (role.kind) {
                            CanonicalPostgreSqlPrincipal.Public -> "PUBLIC"
                            CanonicalPostgreSqlPrincipal.CurrentUser -> "CURRENT_USER"
                            CanonicalPostgreSqlPrincipal.SessionUser -> "SESSION_USER"
                            CanonicalPostgreSqlPrincipal.Named -> id(requireNotNull(role.name), dialect)
                        }
                    }.sorted().joinToString(", "),
                )
            }
            definition.using?.let { append(" USING (${predicate(it)})") }
            definition.withCheck?.let { append(" WITH CHECK (${predicate(it)})") }
        }

        is DropPostgresPolicy -> "DROP POLICY ${id(
            requireNotNull(operation.policy.identity.policyName),
            dialect,
        )} ON ${table(operation.policy.identity.tableReference(), dialect)}"

        is CreateSecurityPolicy -> "CREATE SECURITY POLICY ${name(operation.policy.identity, dialect)} " +
            operation.policy.definition.predicates.sortedBy { it.identity.securityKey() }.joinToString(
                ", ",
            ) { "ADD ${serverPredicate(it, dialect)}" } +
            " WITH (STATE = ${state(operation.policy.definition.enabled)})"

        is DropSecurityPolicy -> "DROP SECURITY POLICY ${name(operation.policy.identity, dialect)}"

        is SetSecurityPolicyEnabled -> "ALTER SECURITY POLICY ${name(
            operation.policy,
            dialect,
        )} WITH (STATE = ${state(operation.enabled)})"

        is AddSecurityPredicate -> "ALTER SECURITY POLICY ${name(
            operation.policy,
            dialect,
        )} ADD ${serverPredicate(operation.predicate, dialect)}"

        is DropSecurityPredicate -> {
            val kind = if (operation.predicate.identity.operation ==
                CanonicalSqlServerPredicateOperation.Filter
            ) {
                "FILTER"
            } else {
                "BLOCK"
            }
            val target = table(operation.predicate.identity.table, dialect)
            "ALTER SECURITY POLICY ${name(operation.policy, dialect)} DROP $kind PREDICATE ON " +
                "$target${timing(operation.predicate.identity.operation)}"
        }

        is CreateVpdPolicy -> {
            val policy = operation.policy
            val function = policy.definition.function
            val member = listOfNotNull(function.packageName, function.name).joinToString(".") { id(it, dialect) }
            "BEGIN DBMS_RLS.ADD_POLICY(${vpdTarget(
                policy.identity,
            )}, function_schema => ${string(function.schemaName)}, " +
                "policy_function => ${string(
                    member,
                )}, statement_types => ${string(
                    policy.definition.statements.map { it.name.uppercase() }.sorted().joinToString(","),
                )}, " +
                "update_check => ${boolean(
                    policy.definition.updateCheck,
                )}, enable => ${boolean(policy.definition.enabled)}); END;"
        }

        is DropVpdPolicy -> "BEGIN DBMS_RLS.DROP_POLICY(${vpdTarget(operation.policy.identity)}); END;"

        is SetVpdPolicyEnabled -> "BEGIN DBMS_RLS.ENABLE_POLICY(${vpdTarget(
            operation.policy,
        )}, enable => ${boolean(operation.enabled)}); END;"
    }
}

private fun serverPredicate(value: CanonicalSqlServerSecurityPredicate, dialect: MigrationDialect): String {
    val filter = value.identity.operation == CanonicalSqlServerPredicateOperation.Filter
    return "${if (filter) "FILTER" else "BLOCK"} PREDICATE ${qualified(
        value.function.schemaName,
        value.function.name,
        dialect,
    )}" +
        "(${value.arguments.joinToString(
            ", ",
        ) { id(it, dialect) }}) ON ${table(value.identity.table, dialect)}${timing(value.identity.operation)}"
}
private fun timing(value: CanonicalSqlServerPredicateOperation): String = when (value) {
    CanonicalSqlServerPredicateOperation.Filter -> ""
    CanonicalSqlServerPredicateOperation.AfterInsert -> " AFTER INSERT"
    CanonicalSqlServerPredicateOperation.AfterUpdate -> " AFTER UPDATE"
    CanonicalSqlServerPredicateOperation.BeforeUpdate -> " BEFORE UPDATE"
    CanonicalSqlServerPredicateOperation.BeforeDelete -> " BEFORE DELETE"
}
private fun vpdTarget(identity: CanonicalSecurityIdentity): String = "object_schema => ${string(
    identity.schemaName,
)}, object_name => ${string(identity.name)}, policy_name => ${string(requireNotNull(identity.policyName))}"
private fun boolean(value: Boolean): String = if (value) "TRUE" else "FALSE"
private fun state(value: Boolean): String = if (value) "ON" else "OFF"
private fun string(value: String?): String = value?.let { "'${it.replace("'", "''")}'" } ?: "NULL"
private fun id(value: String, dialect: MigrationDialect): String =
    if (dialect == MigrationDialect.SqlServer) "[${value.replace("]", "]]")}]" else "\"${value.replace("\"", "\"\"")}\""
private fun qualified(schema: String?, name: String, dialect: MigrationDialect): String =
    listOfNotNull(schema, name).joinToString(".") { id(it, dialect) }
private fun table(value: CanonicalTableReference, dialect: MigrationDialect): String = qualified(
    value.schemaName,
    value.name,
    dialect,
)
private fun name(value: CanonicalSecurityIdentity, dialect: MigrationDialect): String = qualified(
    value.schemaName,
    value.name,
    dialect,
)
