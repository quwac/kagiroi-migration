package example

import dev.quwac.kagiroi.sqlserver.SqlServerTypes
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema

object Documents : Table("documents") {
    init {
        column("value_0", SqlServerTypes.bit)
        column("value_1", SqlServerTypes.tinyInt)
        column("value_2", SqlServerTypes.smallInt)
        column("value_3", SqlServerTypes.integer)
        column("value_4", SqlServerTypes.bigInt)
        column("value_5", SqlServerTypes.real)
        column("value_6", SqlServerTypes.float)
        column("value_7", SqlServerTypes.nVarchar)
        column("value_8", SqlServerTypes.varBinary)
        column("value_9", SqlServerTypes.uniqueIdentifier)
        column("value_10", SqlServerTypes.date)
        column("value_11", SqlServerTypes.time)
        column("value_12", SqlServerTypes.dateTime2)
        column("value_13", SqlServerTypes.dateTimeOffset)
        column("value_14", SqlServerTypes.nVarchar(4000))
        column("value_15", SqlServerTypes.varchar(8000))
        column("value_16", SqlServerTypes.varBinary(8000))
        column("value_17", SqlServerTypes.time(0))
        column("value_18", SqlServerTypes.dateTime2(6))
        column("value_19", SqlServerTypes.dateTimeOffset(7))
        column("value_20", SqlServerTypes.decimal(38,38))
        column("value_21", SqlServerTypes.numeric(20,5))
    }
}

val AppSchema = schema(Documents)
