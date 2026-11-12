package dev.quwac.kagiroi.migration

internal fun validateSecurityObjects(schema: CanonicalPhysicalSchema) {
    val security = schema.securityObjects
    require(
        security.map { it.securityKey() }.distinct().size == security.size,
    ) { "Duplicate canonical security object identity" }
    val tables = schema.tables.associateBy { CanonicalTableReference(it.schemaName, it.name) }
    security.forEach { value ->
        require(value.identity.name.isNotBlank()) { "Security identity name must not be blank" }
        require(value.identity.schemaName?.isNotBlank() != false) { "Invalid security schema" }
        when (value) {
            is CanonicalPostgreSqlRlsTable -> {
                require(value.identity.policyName == null) { "RLS activation is keyed only by its target table" }
                require(value.identity.tableReference() in tables) { "RLS target table is unmanaged" }
            }

            is CanonicalPostgreSqlPolicy -> {
                require(value.identity.tableReference() in tables) { "Policy target table is unmanaged" }
                require(!value.identity.policyName.isNullOrBlank()) { "Policy name is required" }
                val definition = value.definition
                require(definition.roles.isNotEmpty()) { "PostgreSQL policy requires principals" }
                definition.roles.forEach(::validateRole)
                require(
                    definition.command != CanonicalPostgreSqlPolicyCommand.Insert || definition.using == null,
                ) { "INSERT cannot have USING" }
                require(
                    definition.command !in setOf(
                        CanonicalPostgreSqlPolicyCommand.Select,
                        CanonicalPostgreSqlPolicyCommand.Delete,
                    ) || definition.withCheck == null,
                ) { "SELECT/DELETE cannot have WITH CHECK" }
            }

            is CanonicalSqlServerSecurityPolicy -> {
                require(value.identity.policyName == null) { "SQL Server policy identity is independent of tables" }
                val predicates = value.definition.predicates
                require(predicates.isNotEmpty()) { "SQL Server policy requires predicates" }
                require(
                    predicates.map { it.identity }.distinct().size == predicates.size,
                ) { "Duplicate predicate identity" }
                predicates.forEach { predicate ->
                    val table = requireNotNull(
                        tables[predicate.identity.table],
                    ) { "Security predicate target is unmanaged" }
                    require(
                        predicate.function.schemaName != null && predicate.function.packageName == null,
                    ) { "SQL Server function requires schema and has no package" }
                    validateFunction(predicate.function)
                    require(
                        predicate.arguments.all { arg -> table.columns.any { it.name == arg } },
                    ) { "Unknown predicate argument column" }
                }
            }

            is CanonicalOracleVpdPolicy -> validateOracle(value, tables.keys)
        }
    }
}

private fun validateFunction(function: CanonicalSecurityFunction) {
    require(function.name.isNotBlank()) { "Function name must not be blank" }
    require(function.schemaName == null || function.schemaName.isNotBlank()) { "Invalid function schema" }
    require(function.packageName == null || function.packageName.isNotBlank()) { "Invalid function package" }
}

private fun validateRole(role: CanonicalPostgreSqlRole) {
    val valid = if (role.kind == CanonicalPostgreSqlPrincipal.Named) !role.name.isNullOrBlank() else role.name == null
    require(valid) { "Invalid policy principal" }
}

private fun validateOracle(value: CanonicalOracleVpdPolicy, tables: Set<CanonicalTableReference>) {
    require(
        value.identity.tableReference() in tables && !value.identity.policyName.isNullOrBlank(),
    ) { "Invalid VPD identity" }
    validateFunction(value.definition.function)
    val statements = value.definition.statements
    require(statements.isNotEmpty()) { "VPD requires statement types" }
    require(
        CanonicalOracleVpdStatement.Insert !in statements || value.definition.updateCheck,
    ) { "INSERT VPD requires update_check" }
    require(
        !value.definition.updateCheck || statements.any {
            it in setOf(
                CanonicalOracleVpdStatement.Insert,
                CanonicalOracleVpdStatement.Update,
            )
        },
    ) { "update_check requires INSERT/UPDATE" }
}
