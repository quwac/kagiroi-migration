package dev.quwac.kagiroi.migration

internal data class SecuritySchemaDiff(
    val drop: List<SecurityMigrationOperation>,
    val create: List<SecurityMigrationOperation>,
)

internal fun diffSecurityObjects(before: CanonicalPhysicalSchema, after: CanonicalPhysicalSchema): SecuritySchemaDiff {
    val old = before.normalizedSecurity().securityObjects.associateBy { it.securityKey() }
    val new = after.normalizedSecurity().securityObjects.associateBy { it.securityKey() }
    val changes = (old.keys + new.keys).distinct().sorted().mapNotNull { key ->
        val previous = old[key]
        val desired = new[key]
        when {
            previous == desired -> null
            previous == null -> SecuritySchemaDiff(emptyList(), requireNotNull(desired).createOperations())
            desired == null -> SecuritySchemaDiff(previous.dropOperations(), emptyList())
            else -> changedSecurity(previous, desired)
        }
    }
    return SecuritySchemaDiff(
        changes.flatMap { it.drop }.sortedBy {
            if (it is DisableRowLevelSecurity ||
                it is ForceRowLevelSecurity
            ) {
                1
            } else {
                0
            }
        },
        changes.flatMap { it.create }.sortedBy(::securityCreateOrder),
    )
}

private fun changedSecurity(previous: CanonicalSecurityObject, desired: CanonicalSecurityObject): SecuritySchemaDiff =
    when {
        previous is CanonicalPostgreSqlRlsTable && desired is CanonicalPostgreSqlRlsTable -> changedRls(
            previous,
            desired,
        )

        previous is CanonicalSqlServerSecurityPolicy && desired is CanonicalSqlServerSecurityPolicy -> changedSqlServer(
            previous,
            desired,
        )

        previous is CanonicalOracleVpdPolicy && desired is CanonicalOracleVpdPolicy &&
            previous.definition.copy(enabled = desired.definition.enabled) == desired.definition ->
            SecuritySchemaDiff(emptyList(), listOf(SetVpdPolicyEnabled(desired.identity, desired.definition.enabled)))

        else -> SecuritySchemaDiff(previous.dropOperations(), desired.createOperations())
    }

private fun changedRls(
    previous: CanonicalPostgreSqlRlsTable,
    desired: CanonicalPostgreSqlRlsTable,
): SecuritySchemaDiff {
    val drop = mutableListOf<SecurityMigrationOperation>()
    val create = mutableListOf<SecurityMigrationOperation>()
    if (previous.definition.enabled != desired.definition.enabled) {
        if (desired.definition.enabled) {
            create += EnableRowLevelSecurity(desired.identity.tableReference())
        } else {
            drop += DisableRowLevelSecurity(previous.identity.tableReference(), desired.definition)
        }
    }
    if (previous.definition.force != desired.definition.force) {
        create += ForceRowLevelSecurity(desired.identity.tableReference(), desired.definition.force)
    }
    return SecuritySchemaDiff(drop, create)
}

private fun changedSqlServer(
    previous: CanonicalSqlServerSecurityPolicy,
    desired: CanonicalSqlServerSecurityPolicy,
): SecuritySchemaDiff {
    val old = previous.definition.predicates.associateBy { it.identity }
    val new = desired.definition.predicates.associateBy { it.identity }
    val drop = old.filter { (identity, value) -> new[identity] != value }.values.map {
        DropSecurityPredicate(
            previous.identity,
            it,
        )
    }
    val create = new.filter { (identity, value) -> old[identity] != value }.values.map {
        AddSecurityPredicate(
            desired.identity,
            it,
        )
    }
        .toMutableList<SecurityMigrationOperation>()
    if (previous.definition.enabled != desired.definition.enabled) {
        create += SetSecurityPolicyEnabled(desired.identity, desired.definition.enabled)
    }
    return SecuritySchemaDiff(drop, create)
}

private fun securityCreateOrder(operation: SecurityMigrationOperation): Int = when (operation) {
    is EnableRowLevelSecurity -> 0
    is ForceRowLevelSecurity -> 1
    else -> 2
}

private fun CanonicalSecurityObject.createOperations(): List<SecurityMigrationOperation> = when (this) {
    is CanonicalPostgreSqlRlsTable -> buildList {
        if (definition.enabled) {
            add(EnableRowLevelSecurity(identity.tableReference()))
        } else {
            add(DisableRowLevelSecurity(identity.tableReference(), definition))
        }
        if (definition.force) add(ForceRowLevelSecurity(identity.tableReference(), true))
    }

    is CanonicalPostgreSqlPolicy -> listOf(CreatePostgresPolicy(this))

    is CanonicalSqlServerSecurityPolicy -> listOf(CreateSecurityPolicy(this))

    is CanonicalOracleVpdPolicy -> listOf(CreateVpdPolicy(this))
}

private fun CanonicalSecurityObject.dropOperations(): List<SecurityMigrationOperation> = when (this) {
    is CanonicalPostgreSqlRlsTable -> buildList {
        if (definition.force) add(ForceRowLevelSecurity(identity.tableReference(), false))
        add(DisableRowLevelSecurity(identity.tableReference()))
    }

    is CanonicalPostgreSqlPolicy -> listOf(DropPostgresPolicy(this))

    is CanonicalSqlServerSecurityPolicy -> listOf(DropSecurityPolicy(this))

    is CanonicalOracleVpdPolicy -> listOf(DropVpdPolicy(this))
}
