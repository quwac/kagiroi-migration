package dev.quwac.kagiroi.migration

internal fun CanonicalPhysicalSchema.applySecurityOperation(
    operation: SecurityMigrationOperation,
): CanonicalPhysicalSchema {
    val values = when (operation) {
        is EnableRowLevelSecurity -> securityObjects.enableRls(operation.table)

        is DisableRowLevelSecurity -> securityObjects.disableRls(operation)

        is ForceRowLevelSecurity -> securityObjects.change<CanonicalPostgreSqlRlsTable>(
            CanonicalSecurityIdentity(operation.table.schemaName, operation.table.name),
        ) { it.copy(definition = it.definition.copy(force = operation.force)) }

        is CreatePostgresPolicy -> securityObjects.addObject(operation.policy)

        is DropPostgresPolicy -> securityObjects.removeObject(operation.policy)

        is CreateSecurityPolicy -> securityObjects.addObject(operation.policy)

        is DropSecurityPolicy -> securityObjects.removeObject(operation.policy)

        is CreateVpdPolicy -> securityObjects.addObject(operation.policy)

        is DropVpdPolicy -> securityObjects.removeObject(operation.policy)

        is SetSecurityPolicyEnabled -> securityObjects.change<CanonicalSqlServerSecurityPolicy>(operation.policy) {
            it.copy(definition = it.definition.copy(enabled = operation.enabled))
        }

        is SetVpdPolicyEnabled -> securityObjects.change<CanonicalOracleVpdPolicy>(operation.policy) {
            it.copy(definition = it.definition.copy(enabled = operation.enabled))
        }

        is AddSecurityPredicate -> securityObjects.change<CanonicalSqlServerSecurityPolicy>(operation.policy) {
            require(it.definition.predicates.none { value -> value.identity == operation.predicate.identity }) {
                "Duplicate security predicate delta"
            }
            it.copy(definition = it.definition.copy(predicates = it.definition.predicates + operation.predicate))
        }

        is DropSecurityPredicate -> securityObjects.change<CanonicalSqlServerSecurityPolicy>(operation.policy) {
            require(it.definition.predicates.any { value -> value.identity == operation.predicate.identity }) {
                "Missing security predicate delta"
            }
            it.copy(
                definition = it.definition.copy(
                    predicates = it.definition.predicates.filterNot { value ->
                        value.identity == operation.predicate.identity
                    },
                ),
            )
        }
    }
    return copy(securityObjects = values).normalizedSecurity()
}

private fun List<CanonicalSecurityObject>.addObject(value: CanonicalSecurityObject): List<CanonicalSecurityObject> {
    require(none { it.securityKey() == value.securityKey() }) { "Duplicate security object delta" }
    return this + value
}

private fun List<CanonicalSecurityObject>.removeObject(value: CanonicalSecurityObject): List<CanonicalSecurityObject> {
    require(any { it.securityKey() == value.securityKey() }) { "Missing security object delta" }
    return filterNot { it.securityKey() == value.securityKey() }
}

private inline fun <reified T : CanonicalSecurityObject> List<CanonicalSecurityObject>.change(
    identity: CanonicalSecurityIdentity,
    transform: (T) -> CanonicalSecurityObject,
): List<CanonicalSecurityObject> {
    val previous = filterIsInstance<T>().single { it.identity == identity }
    return map { if (it.securityKey() == previous.securityKey()) transform(previous) else it }
}

private fun List<CanonicalSecurityObject>.enableRls(table: CanonicalTableReference): List<CanonicalSecurityObject> {
    val identity = CanonicalSecurityIdentity(table.schemaName, table.name)
    val previous = filterIsInstance<CanonicalPostgreSqlRlsTable>().singleOrNull { it.identity == identity }
    return if (previous == null) {
        addObject(CanonicalPostgreSqlRlsTable(identity, CanonicalPostgreSqlRlsState()))
    } else {
        change<CanonicalPostgreSqlRlsTable>(identity) { it.copy(definition = it.definition.copy(enabled = true)) }
    }
}

private fun List<CanonicalSecurityObject>.disableRls(
    operation: DisableRowLevelSecurity,
): List<CanonicalSecurityObject> {
    val remaining = filterNot { it is CanonicalPostgreSqlRlsTable && it.identity.tableReference() == operation.table }
    val state = operation.state ?: return remaining
    require(!state.enabled) { "Disabled RLS state must not enable RLS" }
    return remaining.addObject(
        CanonicalPostgreSqlRlsTable(
            CanonicalSecurityIdentity(operation.table.schemaName, operation.table.name),
            state,
        ),
    )
}
