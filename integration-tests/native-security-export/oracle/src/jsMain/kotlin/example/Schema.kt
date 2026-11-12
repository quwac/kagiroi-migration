package example

import dev.quwac.kagiroi.oracle.Insert
import dev.quwac.kagiroi.oracle.Select
import dev.quwac.kagiroi.oracle.oracleVpd
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema

object Documents : Table("DOCUMENTS", "APP") {
    val tenantId = string("TENANT_ID")
    init {
        oracleVpd("TENANT_ISOLATION") {
            function(schema = "SECURITY", packageName = "TENANT_SECURITY", name = "TENANT_PREDICATE")
            forStatements(Select, Insert)
        }
    }
}
val AppSchema = schema(Documents)
