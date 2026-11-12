package example

import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sqlserver.AfterInsert
import dev.quwac.kagiroi.sqlserver.function
import dev.quwac.kagiroi.sqlserver.sqlServerRls

object Documents : Table("documents", "dbo") {
    val tenantId = int("tenant_id")
    init {
        sqlServerRls("tenant_security", schema = "security") {
            filterPredicate(function("security", "tenant_predicate"), tenantId)
        }
    }
}
object Orders : Table("orders", "dbo") {
    val tenantId = int("tenant_id")
    init {
        sqlServerRls("tenant_security", schema = "security") {
            blockPredicate(function("security", "tenant_predicate"), AfterInsert, tenantId)
        }
    }
}
val AppSchema = schema(Documents, Orders)
