package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Dialect-owned native security intent, independent of table/column DDL. */
@Serializable
sealed interface SecurityMigrationOperation : MigrationOperation

/** Native enableRowLevelSecurity operation. */
@Serializable
@SerialName("enableRowLevelSecurity")
data class EnableRowLevelSecurity(val table: CanonicalTableReference) : SecurityMigrationOperation

/** Native disableRowLevelSecurity operation. */
@Serializable
@SerialName("disableRowLevelSecurity")
data class DisableRowLevelSecurity(
    val table: CanonicalTableReference,
    val state: CanonicalPostgreSqlRlsState? = null,
) : SecurityMigrationOperation

/** Native forceRowLevelSecurity operation. */
@Serializable
@SerialName("forceRowLevelSecurity")
data class ForceRowLevelSecurity(val table: CanonicalTableReference, val force: Boolean) : SecurityMigrationOperation

/** Native createPostgresPolicy operation. */
@Serializable
@SerialName("createPostgresPolicy")
data class CreatePostgresPolicy(val policy: CanonicalPostgreSqlPolicy) : SecurityMigrationOperation

/** Native dropPostgresPolicy operation. */
@Serializable
@SerialName("dropPostgresPolicy")
data class DropPostgresPolicy(val policy: CanonicalSecurityIdentity) : SecurityMigrationOperation {
    constructor(policy: CanonicalPostgreSqlPolicy) : this(policy.identity)
}

/** Native createSecurityPolicy operation. */
@Serializable
@SerialName("createSecurityPolicy")
data class CreateSecurityPolicy(val policy: CanonicalSqlServerSecurityPolicy) : SecurityMigrationOperation

/** Native dropSecurityPolicy operation. */
@Serializable
@SerialName("dropSecurityPolicy")
data class DropSecurityPolicy(val policy: CanonicalSecurityIdentity) : SecurityMigrationOperation {
    constructor(policy: CanonicalSqlServerSecurityPolicy) : this(policy.identity)
}

/** Native setSecurityPolicyEnabled operation. */
@Serializable
@SerialName("setSecurityPolicyEnabled")
data class SetSecurityPolicyEnabled(val policy: CanonicalSecurityIdentity, val enabled: Boolean) :
    SecurityMigrationOperation

/** Native addSecurityPredicate operation. */
@Serializable
@SerialName("addSecurityPredicate")
data class AddSecurityPredicate(
    val policy: CanonicalSecurityIdentity,
    val predicate: CanonicalSqlServerSecurityPredicate,
) : SecurityMigrationOperation

/** Native dropSecurityPredicate operation. */
@Serializable
@SerialName("dropSecurityPredicate")
data class DropSecurityPredicate(
    val policy: CanonicalSecurityIdentity,
    val predicate: CanonicalSqlServerPredicateIdentity,
) : SecurityMigrationOperation {
    constructor(policy: CanonicalSecurityIdentity, predicate: CanonicalSqlServerSecurityPredicate) :
        this(policy, predicate.identity)
}

/** Native createVpdPolicy operation. */
@Serializable
@SerialName("createVpdPolicy")
data class CreateVpdPolicy(val policy: CanonicalOracleVpdPolicy) : SecurityMigrationOperation

/** Native dropVpdPolicy operation. */
@Serializable
@SerialName("dropVpdPolicy")
data class DropVpdPolicy(val policy: CanonicalSecurityIdentity) : SecurityMigrationOperation {
    constructor(policy: CanonicalOracleVpdPolicy) : this(policy.identity)
}

/** Native setVpdPolicyEnabled operation. */
@Serializable
@SerialName("setVpdPolicyEnabled")
data class SetVpdPolicyEnabled(val policy: CanonicalSecurityIdentity, val enabled: Boolean) :
    SecurityMigrationOperation

/** Identifies table-local PostgreSQL RLS operations for ordering and script formatting. */
internal fun SecurityMigrationOperation.postgresTable(): CanonicalTableReference? = when (this) {
    is EnableRowLevelSecurity -> table
    is DisableRowLevelSecurity -> table
    is ForceRowLevelSecurity -> table
    is CreatePostgresPolicy -> policy.identity.tableReference()
    is DropPostgresPolicy -> policy.tableReference()
    else -> null
}
