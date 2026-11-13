package dev.quwac.kagiroi.migration

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class MySqlCommentsContractTest {
    @Test
    fun persistsInlineCommentsAndPreservesColumnDefinitionAndDataOnChanges() {
        for ((dialect, environment, client) in listOf(
            Triple(MigrationDialect.MySql, "KAGIROI_TEST_MYSQL_CONTAINER", "mysql"),
            Triple(MigrationDialect.MariaDb, "KAGIROI_TEST_MARIADB_CONTAINER", "mariadb"),
        )) {
            val container = System.getenv(environment) ?: continue
            val namespace = "comments_${UUID.randomUUID().toString().replace("-", "") }"
            fun sql(source: String): String = containerCommentSql(
                container,
                listOf(client, "-uroot", "-pkagiroi-test", "--default-character-set=utf8mb4", "--raw", "-N", "-B"),
                source,
            ).lineSequence().filterNot { it.startsWith("mysql: [Warning]") }.joinToString("\n").trim()
            sql("CREATE DATABASE `$namespace` CHARACTER SET utf8mb4;")
            try {
                val reference = CanonicalTableReference(namespace, "profiles")
                var previous = commentFixture(reference, "Owner's \\ 組織", objectComments = true)
                sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), previous), dialect).body)
                sql("INSERT INTO `$namespace`.profiles (code) VALUES (23);")
                val attributes = "SELECT concat(DATA_TYPE, '|', IS_NULLABLE, '|', COLUMN_DEFAULT, '|', COLUMN_KEY) " +
                    "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='profiles' AND COLUMN_NAME='id';"
                val originalAttributes = sql(attributes)
                assertEquals("int|NO|7|PRI", originalAttributes)
                val objectComments =
                    "SELECT concat(INDEX_NAME, '|', INDEX_COMMENT) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='profiles' AND INDEX_NAME <> 'PRIMARY' ORDER BY INDEX_NAME;"
                val originalIndexComments = sql(objectComments)
                assertEquals(
                    "profiles_code_lookup|Index comment\nprofiles_code_unique|Unique comment",
                    originalIndexComments,
                )
                for (comment in listOf("Owner's \\ 組織", "Changed's \\ 説明", null)) {
                    val desired = commentFixture(reference, comment, objectComments = true)
                    val plan = diffSchema(previous, desired)
                    if (plan.operations.isNotEmpty()) sql(generateMigrationScript(plan, dialect).body)
                    assertEquals(
                        comment.orEmpty(),
                        sql(
                            "SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='profiles';",
                        ),
                    )
                    assertEquals(
                        comment.orEmpty(),
                        sql(
                            "SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='profiles' AND COLUMN_NAME='id';",
                        ),
                    )
                    assertEquals(originalAttributes, sql(attributes))
                    assertEquals(originalIndexComments, sql(objectComments))
                    assertEquals("7|23", sql("SELECT concat(id, '|', code) FROM `$namespace`.profiles;"))
                    previous = desired
                }
                verifiesAddedComments(dialect, namespace, ::sql)
                preservesIdentityAndGeneratedColumns(dialect, namespace, ::sql)
            } finally {
                sql("DROP DATABASE `$namespace`;")
            }
        }
    }

    private fun verifiesAddedComments(dialect: MigrationDialect, namespace: String, sql: (String) -> String) {
        val reference = CanonicalTableReference(namespace, "profiles")
        val additions = MigrationPlan.Changes(
            listOf(
                AddColumn(
                    reference,
                    CanonicalColumn(
                        "extra",
                        CanonicalColumnType("kagiroi.int32"),
                        true,
                        comment = "Added column",
                    ),
                ),
                AddUnique(
                    reference,
                    CanonicalUniqueConstraint("profiles_extra_unique", listOf("extra"), "Added unique"),
                ),
            ),
        )
        sql(generateMigrationScript(additions, dialect).body)
        assertEquals(
            "Added column",
            sql(
                "SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='profiles' AND COLUMN_NAME='extra';",
            ),
        )
        assertEquals(
            "Added unique",
            sql(
                "SELECT INDEX_COMMENT FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='profiles' AND INDEX_NAME='profiles_extra_unique';",
            ),
        )
    }

    private fun preservesIdentityAndGeneratedColumns(
        dialect: MigrationDialect,
        namespace: String,
        sql: (String) -> String,
    ) {
        val reference = CanonicalTableReference(namespace, "generated_profiles")
        val integer = CanonicalColumnType("kagiroi.int32")
        fun schema(comment: String?): CanonicalDesiredSchema {
            val table = CanonicalTable(
                schemaName = namespace,
                name = reference.name,
                columns = listOf(
                    CanonicalColumn(
                        "id",
                        integer,
                        false,
                        primaryKey = true,
                        identity = CanonicalIdentity(),
                        comment = comment,
                    ),
                    CanonicalColumn("code", integer, true, unique = true, comment = comment),
                    CanonicalColumn(
                        "computed",
                        integer,
                        true,
                        generated = CanonicalGeneratedColumn(
                            CanonicalBinaryExpression(
                                CanonicalBinaryOperator.Add,
                                CanonicalColumnExpression(CanonicalColumnReference(reference, "code")),
                                CanonicalLiteralExpression(CanonicalIntegerValue("1"), integer),
                            ),
                            CanonicalGeneratedColumnStorage.Stored,
                        ),
                        comment = comment,
                    ),
                ),
            )
            return replaySchemaDeltas(listOf(SchemaDelta(listOf(CreateTable(table)))))
        }
        var previous = schema("Before")
        sql(generateMigrationScript(diffSchema(emptyCanonicalSchema(), previous), dialect).body)
        sql("INSERT INTO `$namespace`.generated_profiles (code) VALUES (10);")
        for (comment in listOf("After", null)) {
            val desired = schema(comment)
            sql(generateMigrationScript(diffSchema(previous, desired), dialect).body)
            previous = desired
        }
        sql("INSERT INTO `$namespace`.generated_profiles (code) VALUES (20);")
        assertEquals(
            "1|10|11\n2|20|21",
            sql("SELECT concat(id,'|',code,'|',computed) FROM `$namespace`.generated_profiles ORDER BY id;"),
        )
        assertEquals(
            "UNI",
            sql(
                "SELECT COLUMN_KEY FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='$namespace' AND TABLE_NAME='generated_profiles' AND COLUMN_NAME='code';",
            ),
        )
    }
}

/** Runs generated SQL in an explicitly selected disposable contract container. */
internal fun containerCommentSql(container: String, arguments: List<String>, source: String): String {
    val process = ProcessBuilder(
        listOf("docker", "exec", "-i", container) + arguments,
    ).redirectErrorStream(true).start()
    process.outputStream.bufferedWriter().use { it.write(source) }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    assertEquals(0, process.waitFor(), output)
    return output.trim()
}
