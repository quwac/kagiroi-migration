package dev.quwac.kagiroi.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeclaredColumnTypesTest {
    @Test
    fun rendersPostgresTypesAndArraysWithoutChangingDeclarations() {
        val declarations = listOf(
            "BOOLEAN", "SMALLINT", "INTEGER", "BIGINT", "SMALLSERIAL", "SERIAL", "BIGSERIAL", "REAL",
            "DOUBLE PRECISION",
            "TEXT", "\"char\"", "NAME", "BYTEA", "UUID", "DATE", "TIME WITHOUT TIME ZONE", "TIME WITH TIME ZONE",
            "TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP WITH TIME ZONE", "INTERVAL", "MONEY", "NUMERIC",
            "JSON", "JSONB", "JSONPATH", "INET", "CIDR", "MACADDR", "MACADDR8", "POINT", "LINE", "LSEG", "BOX", "PATH",
            "POLYGON", "CIRCLE", "TSQUERY", "TSVECTOR", "XML", "PG_LSN", "PG_SNAPSHOT", "TXID_SNAPSHOT", "OID", "XID",
            "CID", "XID8", "TID", "INT2VECTOR", "OIDVECTOR", "ACLITEM", "REGCLASS", "REGCOLLATION", "REGCONFIG",
            "REGDICTIONARY", "REGNAMESPACE", "REGOPER", "REGOPERATOR", "REGPROCEDURE", "REGPROC", "REGROLE", "REGTYPE",
            "INT4RANGE", "INT8RANGE", "NUMRANGE", "TSRANGE", "TSTZRANGE", "DATERANGE", "INT4MULTIRANGE",
            "INT8MULTIRANGE",
            "NUMMULTIRANGE", "TSMULTIRANGE", "TSTZMULTIRANGE", "DATEMULTIRANGE", "CHARACTER(10)", "CHARACTER VARYING",
            "CHARACTER VARYING(255)", "BIT(8)", "BIT VARYING", "BIT VARYING(128)", "TIME(0) WITHOUT TIME ZONE",
            "TIME(6) WITH TIME ZONE", "TIMESTAMP(6) WITHOUT TIME ZONE", "TIMESTAMP(0) WITH TIME ZONE",
            "NUMERIC(1000,-1000)",
            "NUMERIC(1,1000)", "INTERVAL(6)", "INTERVAL YEAR TO MONTH", "INTERVAL DAY TO SECOND(3)",
            "\"custom type\"", "\"tenant\".\"a\"\"b;--\"",
        )
        assertDeclarations("postgres", MigrationDialect.PostgreSql, declarations)
        // SERIAL pseudo-types have no array type in PostgreSQL, although the factory can spell one.
        assertDeclarations(
            "postgres",
            MigrationDialect.PostgreSql,
            declarations.filterNot { it.endsWith("SERIAL") }.map { "$it[]" } + listOf("JSONB[][]"),
        )
    }

    @Test
    fun rendersMySqlAndMariaDbTypesWithTheirNativeCompatibility() {
        val common = listOf(
            "BOOLEAN", "TINYINT", "TINYINT UNSIGNED", "SMALLINT", "SMALLINT UNSIGNED", "MEDIUMINT",
            "MEDIUMINT UNSIGNED",
            "INT", "INT UNSIGNED", "BIGINT", "BIGINT UNSIGNED", "SERIAL", "FLOAT", "DOUBLE", "YEAR", "TINYTEXT", "TEXT",
            "MEDIUMTEXT", "LONGTEXT", "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "GEOMETRY", "POINT", "LINESTRING",
            "POLYGON", "MULTIPOINT", "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION", "DATE", "BIT(64)",
            "CHAR(36)",
            "VARCHAR(255)", "BINARY(16)", "VARBINARY(255)", "TIME(0)", "DATETIME(6)", "TIMESTAMP(6)", "DECIMAL(65,30)",
        )
        for (dialect in listOf(MigrationDialect.MySql, MigrationDialect.MariaDb)) {
            assertDeclarations("mysql", dialect, common)
        }
        assertDeclarations("mysql", MigrationDialect.MySql, listOf("JSON"))
        assertDeclarations(
            "mariadb",
            MigrationDialect.MariaDb,
            listOf("UUID", "CHAR(36)", "JSON", "INET4", "INET6", "XMLTYPE", "VECTOR(1536)", "DECIMAL(65,38)"),
        )
        assertRejected("mysql", MigrationDialect.MariaDb, listOf("JSON"))
    }

    @Test
    fun rendersSqliteAndD1StorageTypesAndEscapedNamedTypes() {
        val declarations = listOf(
            "INTEGER", "REAL", "TEXT", "BLOB", "\"custom text\"", "\"DECIMAL\"(20,5)", "\"x\"(0)",
            "\"x\"(2147483647,0)", "\"a\"\"b; DROP TABLE x;--\"(12,0)",
        )
        for (dialect in listOf(MigrationDialect.Sqlite, MigrationDialect.D1)) {
            assertDeclarations("sqlite", dialect, declarations)
        }
    }

    @Test
    fun rendersSqlServerAndOraclePhysicalTypes() {
        assertDeclarations(
            "sqlserver",
            MigrationDialect.SqlServer,
            listOf(
                "BIT", "TINYINT", "SMALLINT", "INT", "BIGINT", "REAL", "FLOAT", "NVARCHAR(MAX)", "VARBINARY(MAX)",
                "UNIQUEIDENTIFIER", "DATE", "TIME(7)", "DATETIME2(0)", "DATETIMEOFFSET(7)", "NVARCHAR(4000)",
                "VARCHAR(8000)", "VARBINARY(8000)", "DECIMAL(38,38)", "NUMERIC(1,0)",
            ),
        )
        assertDeclarations(
            "oracle",
            MigrationDialect.Oracle,
            listOf(
                "BOOLEAN", "NUMBER(3,0)", "NUMBER(5,0)", "NUMBER(10,0)", "NUMBER(19,0)", "NUMBER(20,0)",
                "NUMBER(38,-38)", "NUMBER(1,38)", "BINARY_FLOAT", "BINARY_DOUBLE", "VARCHAR2(4000 CHAR)",
                "NVARCHAR2(2000)", "CHAR(2000 CHAR)", "NCHAR(1000)", "CLOB", "NCLOB", "BLOB", "RAW(16)", "RAW(2000)",
                "DATE", "TIMESTAMP(9)", "TIMESTAMP(9) WITH TIME ZONE", "TIMESTAMP(9) WITH LOCAL TIME ZONE",
            ),
        )
    }

    @Test
    fun rejectsCrossDialectTypesAndMalformedDescriptors() {
        val examples = mapOf(
            "postgres" to (MigrationDialect.PostgreSql to "JSONB"),
            "mysql" to (MigrationDialect.MySql to "JSON"),
            "mariadb" to (MigrationDialect.MariaDb to "INET6"),
            "sqlite" to (MigrationDialect.Sqlite to "TEXT"),
            "sqlserver" to (MigrationDialect.SqlServer to "DATETIME2(7)"),
            "oracle" to (MigrationDialect.Oracle to "RAW(16)"),
        )
        for ((family, example) in examples) {
            val (native, declaration) = example
            for (dialect in MigrationDialect.entries) {
                val compatible = dialect == native || (family == "sqlite" && dialect == MigrationDialect.D1)
                if (!compatible) assertRejected(family, dialect, listOf(declaration))
            }
            val type = declared(family, declaration)
            for (arguments in listOf(
                emptyList(),
                type.arguments + type.arguments,
                listOf(CanonicalTypeArgument("sql", declaration)),
            )) {
                assertFailsWith<MigrationSqlGenerationException> {
                    createSql(type.copy(arguments = arguments), native)
                }
            }
        }
    }

    @Test
    fun rejectsSqlInjectionUnknownTypesAndInvalidParameters() {
        val cases = mapOf(
            "postgres" to listOf(
                "JSONB; DROP TABLE x", "JSONB DEFAULT '{}'", "unknown[]", "UUID[];SELECT 1", "\"x\".\"y\".\"z\"",
                "\"x\"\u0000", "\"unterminated", "CHARACTER(0)", "NUMERIC(1001,0)", "NUMERIC(10,-1001)",
                "TIME(7) WITH TIME ZONE", "INTERVAL YEAR(3)", "INTERVAL DAY TO MONTH", "BIT(999999999999999)",
                "SMALLSERIAL[]", "SERIAL[]", "BIGSERIAL[][]",
            ),
            "mysql" to listOf(
                "JSON;SELECT 1",
                "VARCHAR(0)",
                "DECIMAL(66,0)",
                "DECIMAL(10,11)",
                "DECIMAL(40,31)",
                "TIME(7)",
            ),
            "mariadb" to listOf("UUID);SELECT 1", "VECTOR(0)", "DECIMAL(65,39)", "INTEGER"),
            "sqlite" to listOf("TEXT NOT NULL", "\"x\";SELECT 1", "\"x\"(-1)", "\"x\"(1,2,3)", "\"x\"(2147483648)"),
            "sqlserver" to listOf("INT IDENTITY", "NVARCHAR(4001)", "VARBINARY(8001)", "DATETIME2(8)", "NUMERIC(39,0)"),
            "oracle" to listOf("BLOB);SELECT 1", "VARCHAR2(4001 CHAR)", "RAW(2001)", "NCHAR(1001)", "NUMBER(38,-39)"),
        )
        val dialects = listOf(
            MigrationDialect.PostgreSql,
            MigrationDialect.MySql,
            MigrationDialect.MariaDb,
            MigrationDialect.Sqlite,
            MigrationDialect.SqlServer,
            MigrationDialect.Oracle,
        )
        for ((index, entry) in cases.entries.withIndex()) assertRejected(entry.key, dialects[index], entry.value)
    }

    @Test
    fun usesTheSameTypesInAddAlterAndCastAndReplaysArtifactsDeterministically() {
        val reference = CanonicalTableReference(name = "documents")
        val before = CanonicalColumnType("kagiroi.string")
        val after = declared("postgres", "JSONB")
        val column = CanonicalColumn("payload", after, true)
        val plan = MigrationPlan.Changes(
            listOf(
                AddColumn(reference, column),
                AlterColumnType(
                    CanonicalColumnReference(reference, "payload"),
                    before,
                    after,
                    using = CanonicalCastExpression(
                        CanonicalColumnExpression(CanonicalColumnReference(reference, "payload")),
                        after,
                    ),
                    definition = column,
                ),
            ),
        )
        val script = generateMigrationScript(plan, MigrationDialect.PostgreSql)
        assertTrue(script.body.contains("ADD COLUMN \"payload\" JSONB"), script.body)
        assertTrue(script.body.contains("TYPE JSONB USING CAST(\"payload\" AS JSONB)"), script.body)
        val artifact = createMigrationArtifact(script.body, MigrationDialect.PostgreSql, plan)
        val encoded = writeMigrationArtifact(artifact.metadata, artifact.body)
        assertEquals(artifact, parseMigrationArtifact(encoded))
        assertEquals(encoded, writeMigrationArtifact(artifact.metadata, artifact.body))
    }

    private fun assertDeclarations(family: String, dialect: MigrationDialect, declarations: List<String>) {
        for (declaration in declarations) {
            val sql = try {
                createSql(declared(family, declaration), dialect)
            } catch (failure: MigrationSqlGenerationException) {
                throw AssertionError("$dialect: $declaration", failure)
            }
            assertTrue(sql.contains(" $declaration NOT NULL"), "$dialect: $declaration\n$sql")
        }
    }

    private fun assertRejected(family: String, dialect: MigrationDialect, declarations: List<String>) {
        for (declaration in declarations) {
            assertFailsWith<MigrationSqlGenerationException>("$dialect: $declaration") {
                createSql(declared(family, declaration), dialect)
            }
        }
    }

    private fun createSql(type: CanonicalColumnType, dialect: MigrationDialect): String = generateMigrationScript(
        MigrationPlan.Changes(
            listOf(
                CreateTable(
                    CanonicalTable(name = "documents", columns = listOf(CanonicalColumn("payload", type, false))),
                ),
            ),
        ),
        dialect,
    ).body

    private fun declared(family: String, declaration: String) = CanonicalColumnType(
        "kagiroi.$family.declared",
        listOf(CanonicalTypeArgument("declaration", declaration)),
    )
}
