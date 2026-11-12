package example

import dev.quwac.kagiroi.postgres.currentSetting
import dev.quwac.kagiroi.postgres.postgresRls
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.schema
import dev.quwac.kagiroi.sql.eq
import kotlin.uuid.Uuid

object Documents : Table("documents") {
    val tenantId = uuid("tenant_id")
    init {
        postgresRls {
            force()
            policy("tenant") {
                using { tenantId eq currentSetting("app.tenant_id").cast<Uuid>() }
                withCheck { tenantId eq currentSetting("app.tenant_id").cast<Uuid>() }
            }
        }
    }
}
val AppSchema = schema(Documents)
