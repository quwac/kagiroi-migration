package dev.quwac.kagiroi.migration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Independent native security object decoded from the public Kagiroi projection. */
@Serializable
sealed interface CanonicalSecurityObject {
    val identity: CanonicalSecurityIdentity
}

/** Table/object identity or independent policy identity; policyName is table-local when present. */
@Serializable
data class CanonicalSecurityIdentity(val schemaName: String? = null, val name: String, val policyName: String? = null)

/** Native PostgreSQL table RLS state. */
@Serializable
@SerialName("postgresRlsTable")
data class CanonicalPostgreSqlRlsTable(
    override val identity: CanonicalSecurityIdentity,
    val definition: CanonicalPostgreSqlRlsState,
) : CanonicalSecurityObject

@Serializable
data class CanonicalPostgreSqlRlsState(val enabled: Boolean = true, val force: Boolean = false)

/** PostgreSQL policy, retaining structured expression ASTs and native defaults. */
@Serializable
@SerialName("postgresPolicy")
data class CanonicalPostgreSqlPolicy(
    override val identity: CanonicalSecurityIdentity,
    val definition: CanonicalPostgreSqlPolicyDefinition,
) : CanonicalSecurityObject

@Serializable
data class CanonicalPostgreSqlPolicyDefinition(
    val command: CanonicalPostgreSqlPolicyCommand = CanonicalPostgreSqlPolicyCommand.All,
    val mode: CanonicalPostgreSqlPolicyMode = CanonicalPostgreSqlPolicyMode.Permissive,
    val roles: List<CanonicalPostgreSqlRole> = listOf(CanonicalPostgreSqlRole(CanonicalPostgreSqlPrincipal.Public)),
    @Serializable(with = SecurityPredicateSerializer::class)
    val using: CanonicalSchemaPredicate? = null,
    @Serializable(with = SecurityPredicateSerializer::class)
    val withCheck: CanonicalSchemaPredicate? = null,
)

@Serializable
enum class CanonicalPostgreSqlPolicyCommand {
    @SerialName("all")
    All,

    @SerialName("select")
    Select,

    @SerialName("insert")
    Insert,

    @SerialName("update")
    Update,

    @SerialName("delete")
    Delete,
}

@Serializable
enum class CanonicalPostgreSqlPolicyMode {
    @SerialName("permissive")
    Permissive,

    @SerialName("restrictive")
    Restrictive,
}

@Serializable
data class CanonicalPostgreSqlRole(val kind: CanonicalPostgreSqlPrincipal, val name: String? = null)

@Serializable
enum class CanonicalPostgreSqlPrincipal {
    @SerialName("public")
    Public,

    @SerialName("currentUser")
    CurrentUser,

    @SerialName("sessionUser")
    SessionUser,

    @SerialName("named")
    Named,
}

/** A stable function reference, including optional Oracle package membership. */
@Serializable
data class CanonicalSecurityFunction(val schemaName: String? = null, val packageName: String? = null, val name: String)

/** Independent SQL Server policy, including merged predicates for all managed tables. */
@Serializable
@SerialName("sqlServerSecurityPolicy")
data class CanonicalSqlServerSecurityPolicy(
    override val identity: CanonicalSecurityIdentity,
    val definition: CanonicalSqlServerSecurityPolicyDefinition,
) : CanonicalSecurityObject

@Serializable
data class CanonicalSqlServerSecurityPolicyDefinition(
    val enabled: Boolean = true,
    val predicates: List<CanonicalSqlServerSecurityPredicate>,
)

@Serializable
data class CanonicalSqlServerSecurityPredicate(
    val identity: CanonicalSqlServerPredicateIdentity,
    val function: CanonicalSecurityFunction,
    val arguments: List<String>,
)

@Serializable
data class CanonicalSqlServerPredicateIdentity(
    val table: CanonicalTableReference,
    val operation: CanonicalSqlServerPredicateOperation,
)

@Serializable
enum class CanonicalSqlServerPredicateOperation { Filter, AfterInsert, AfterUpdate, BeforeUpdate, BeforeDelete }

/** Object-local Oracle VPD policy. */
@Serializable
@SerialName("oracleVpdPolicy")
data class CanonicalOracleVpdPolicy(
    override val identity: CanonicalSecurityIdentity,
    val definition: CanonicalOracleVpdDefinition,
) : CanonicalSecurityObject

@Serializable
data class CanonicalOracleVpdDefinition(
    val function: CanonicalSecurityFunction,
    val statements: List<CanonicalOracleVpdStatement>,
    val updateCheck: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
enum class CanonicalOracleVpdStatement {
    @SerialName("select")
    Select,

    @SerialName("insert")
    Insert,

    @SerialName("update")
    Update,

    @SerialName("delete")
    Delete,

    @SerialName("index")
    Index,
}

internal fun CanonicalSecurityObject.normalized(): CanonicalSecurityObject = when (this) {
    is CanonicalPostgreSqlRlsTable -> this

    is CanonicalPostgreSqlPolicy -> copy(
        definition = definition.copy(
            roles = definition.roles.distinct().sortedWith(
                compareBy({ it.kind.name }, { it.name.orEmpty() }),
            ),
        ),
    )

    is CanonicalSqlServerSecurityPolicy -> copy(
        definition = definition.copy(
            predicates = definition.predicates.sortedBy {
                it.identity.securityKey()
            },
        ),
    )

    is CanonicalOracleVpdPolicy -> copy(
        definition = definition.copy(statements = definition.statements.distinct().sortedBy { it.name }),
    )
}

internal fun CanonicalSecurityObject.securityKey(): String = when (this) {
    is CanonicalPostgreSqlRlsTable -> "postgresRlsTable"
    is CanonicalPostgreSqlPolicy -> "postgresPolicy"
    is CanonicalSqlServerSecurityPolicy -> "sqlServerSecurityPolicy"
    is CanonicalOracleVpdPolicy -> "oracleVpdPolicy"
} + listOf(
    identity.schemaName,
    identity.name,
    identity.policyName,
).map { it?.length?.toString() + ":" + it }.joinToString()

internal fun CanonicalSqlServerPredicateIdentity.securityKey(): String =
    listOf(table.schemaName, table.name, operation.name).map { it?.length?.toString() + ":" + it }.joinToString()

internal fun CanonicalPhysicalSchema.normalizedSecurity(): CanonicalPhysicalSchema =
    copy(securityObjects = securityObjects.map { it.normalized() }.sortedBy { it.securityKey() })

internal fun CanonicalSecurityIdentity.tableReference(): CanonicalTableReference = CanonicalTableReference(
    schemaName,
    name,
)
internal fun canonicalSchemaVersion(schema: CanonicalPhysicalSchema): Int =
    if (schema.securityObjects.isEmpty()) CANONICAL_SCHEMA_FORMAT_VERSION else SECURITY_CANONICAL_SCHEMA_FORMAT_VERSION
