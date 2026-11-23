package example

import dev.quwac.kagiroi.sqlite.SqliteTypes
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema

object Documents : Table("documents") {
    init {
        column("value_0", SqliteTypes.integer)
        column("value_1", SqliteTypes.real)
        column("value_2", SqliteTypes.text)
        column("value_3", SqliteTypes.textUlong)
        column("value_4", SqliteTypes.blob)
        column("value_5", SqliteTypes.namedText("custom\"type", 20, 5))
    }
}

val AppSchema = schema(Documents)
