package example

import dev.quwac.kagiroi.mysql.MySqlTypes
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema

object Documents : Table("documents") {
    init {
        column("value_0", MySqlTypes.boolean)
        column("value_1", MySqlTypes.tinyInt)
        column("value_2", MySqlTypes.tinyIntUnsigned)
        column("value_3", MySqlTypes.smallInt)
        column("value_4", MySqlTypes.smallIntUnsigned)
        column("value_5", MySqlTypes.mediumInt)
        column("value_6", MySqlTypes.mediumIntUnsigned)
        column("value_7", MySqlTypes.integer)
        column("value_8", MySqlTypes.integerUnsigned)
        column("value_9", MySqlTypes.bigInt)
        column("value_10", MySqlTypes.bigIntUnsigned)
        column("value_11", MySqlTypes.serial)
        column("value_12", MySqlTypes.float)
        column("value_13", MySqlTypes.double)
        column("value_14", MySqlTypes.year)
        column("value_15", MySqlTypes.tinyText)
        column("value_16", MySqlTypes.text)
        column("value_17", MySqlTypes.mediumText)
        column("value_18", MySqlTypes.longText)
        column("value_19", MySqlTypes.tinyBlob)
        column("value_20", MySqlTypes.blob)
        column("value_21", MySqlTypes.mediumBlob)
        column("value_22", MySqlTypes.longBlob)
        column("value_23", MySqlTypes.charUuid)
        column("value_24", MySqlTypes.binaryUuid)
        column("value_25", MySqlTypes.json)
        column("value_26", MySqlTypes.geometry)
        column("value_27", MySqlTypes.point)
        column("value_28", MySqlTypes.lineString)
        column("value_29", MySqlTypes.polygon)
        column("value_30", MySqlTypes.multiPoint)
        column("value_31", MySqlTypes.multiLineString)
        column("value_32", MySqlTypes.multiPolygon)
        column("value_33", MySqlTypes.geometryCollection)
        column("value_34", MySqlTypes.bit(64))
        column("value_35", MySqlTypes.decimal(65,30))
        column("value_36", MySqlTypes.numeric(20,5))
        column("value_37", MySqlTypes.date())
        column("value_38", MySqlTypes.time(6))
        column("value_39", MySqlTypes.dateTime(6))
        column("value_40", MySqlTypes.timestamp(6))
        column("value_41", MySqlTypes.char(20))
        column("value_42", MySqlTypes.varchar(255))
        column("value_43", MySqlTypes.binary(16))
        column("value_44", MySqlTypes.varbinary(255))
        column("value_45", MySqlTypes.enum("open", "closed"))
        column("value_46", MySqlTypes.set("read", "write"))
    }
}

val AppSchema = schema(Documents)
