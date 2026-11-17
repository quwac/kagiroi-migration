package example

import dev.quwac.kagiroi.oracle.OracleTypes
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema

object Documents : Table("documents") {
    init {
        column("value_0", OracleTypes.boolean)
        column("value_1", OracleTypes.number3)
        column("value_2", OracleTypes.number5)
        column("value_3", OracleTypes.number10)
        column("value_4", OracleTypes.number19)
        column("value_5", OracleTypes.number20)
        column("value_6", OracleTypes.binaryFloat)
        column("value_7", OracleTypes.binaryDouble)
        column("value_8", OracleTypes.varchar2)
        column("value_9", OracleTypes.nVarchar2)
        column("value_10", OracleTypes.clob)
        column("value_11", OracleTypes.nClob)
        column("value_12", OracleTypes.raw)
        column("value_13", OracleTypes.blob)
        column("value_14", OracleTypes.rawUuid)
        column("value_15", OracleTypes.date)
        column("value_16", OracleTypes.timestamp)
        column("value_17", OracleTypes.timestampWithTimeZone)
        column("value_18", OracleTypes.timestampWithLocalTimeZone)
        column("value_19", OracleTypes.varchar2(4000))
        column("value_20", OracleTypes.nVarchar2(2000))
        column("value_21", OracleTypes.char(2000))
        column("value_22", OracleTypes.nChar(1000))
        column("value_23", OracleTypes.raw(2000))
        column("value_24", OracleTypes.number(38,-38))
    }
}

val AppSchema = schema(Documents)
