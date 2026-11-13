package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Dialect-owned native security intent, independent of table/column DDL. */
@Serializable
sealed interface SecurityMigrationOperation : MigrationOperation

/** Native enableRowLevelSecurity operation; security visibility changes require review. */
@Serializable
@SerialName("enableRowLevelSecurity")
data class EnableRowLevelSecurity(
    val table: CanonicalTableReference,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native disableRowLevelSecurity operation; security visibility changes require review. */
@Serializable
@SerialName("disableRowLevelSecurity")
data class DisableRowLevelSecurity(
    val table: CanonicalTableReference,
    val state: CanonicalPostgreSqlRlsState? = null,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native forceRowLevelSecurity operation; security visibility changes require review. */
@Serializable
@SerialName("forceRowLevelSecurity")
data class ForceRowLevelSecurity(
    val table: CanonicalTableReference,
    val force: Boolean,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native createPostgresPolicy operation; security visibility changes require review. */
@Serializable
@SerialName("createPostgresPolicy")
data class CreatePostgresPolicy(
    val policy: CanonicalPostgreSqlPolicy,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native dropPostgresPolicy operation; security visibility changes require review. */
@Serializable
@SerialName("dropPostgresPolicy")
data class DropPostgresPolicy(
    val policy: CanonicalPostgreSqlPolicy,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native createSecurityPolicy operation; security visibility changes require review. */
@Serializable
@SerialName("createSecurityPolicy")
data class CreateSecurityPolicy(
    val policy: CanonicalSqlServerSecurityPolicy,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native dropSecurityPolicy operation; security visibility changes require review. */
@Serializable
@SerialName("dropSecurityPolicy")
data class DropSecurityPolicy(
    val policy: CanonicalSqlServerSecurityPolicy,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native setSecurityPolicyEnabled operation; security visibility changes require review. */
@Serializable
@SerialName("setSecurityPolicyEnabled")
data class SetSecurityPolicyEnabled(
    val policy: CanonicalSecurityIdentity,
    val enabled: Boolean,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native addSecurityPredicate operation; security visibility changes require review. */
@Serializable
@SerialName("addSecurityPredicate")
data class AddSecurityPredicate(
    val policy: CanonicalSecurityIdentity,
    val predicate: CanonicalSqlServerSecurityPredicate,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native dropSecurityPredicate operation; security visibility changes require review. */
@Serializable
@SerialName("dropSecurityPredicate")
data class DropSecurityPredicate(
    val policy: CanonicalSecurityIdentity,
    val predicate: CanonicalSqlServerSecurityPredicate,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native createVpdPolicy operation; security visibility changes require review. */
@Serializable
@SerialName("createVpdPolicy")
data class CreateVpdPolicy(
    val policy: CanonicalOracleVpdPolicy,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native dropVpdPolicy operation; security visibility changes require review. */
@Serializable
@SerialName("dropVpdPolicy")
data class DropVpdPolicy(
    val policy: CanonicalOracleVpdPolicy,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Native setVpdPolicyEnabled operation; security visibility changes require review. */
@Serializable
@SerialName("setVpdPolicyEnabled")
data class SetVpdPolicyEnabled(
    val policy: CanonicalSecurityIdentity,
    val enabled: Boolean,
    override val risk: MigrationRiskAssessment = securityRisk,
) : SecurityMigrationOperation

/** Identifies table-local PostgreSQL RLS operations for ordering and script formatting. */
internal fun SecurityMigrationOperation.postgresTable(): CanonicalTableReference? = when (this) {
    is EnableRowLevelSecurity -> table
    is DisableRowLevelSecurity -> table
    is ForceRowLevelSecurity -> table
    is CreatePostgresPolicy -> policy.identity.tableReference()
    is DropPostgresPolicy -> policy.identity.tableReference()
    else -> null
}

private val securityRisk = MigrationRiskAssessment(
    MigrationRisk.Review,
    "Changing native row security can alter read visibility or block writes.",
)
