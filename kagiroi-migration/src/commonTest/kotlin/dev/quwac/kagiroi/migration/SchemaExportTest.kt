package dev.quwac.kagiroi.migration

import dev.quwac.kagiroi.schema.ColumnType
import dev.quwac.kagiroi.schema.ForeignKeyAction
import dev.quwac.kagiroi.schema.GeneratedColumnStorage
import dev.quwac.kagiroi.schema.IntColumnType
import dev.quwac.kagiroi.schema.SchemaTypeArgument
import dev.quwac.kagiroi.schema.SchemaTypeDescriptor
import dev.quwac.kagiroi.schema.StableSchemaColumnType
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.asEnum
import dev.quwac.kagiroi.schema.case
import dev.quwac.kagiroi.schema.cast
import dev.quwac.kagiroi.schema.coalesce
import dev.quwac.kagiroi.schema.comment
import dev.quwac.kagiroi.schema.computed
import dev.quwac.kagiroi.schema.currentTimestamp
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.generated
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.intType
import dev.quwac.kagiroi.schema.literal
import dev.quwac.kagiroi.schema.lower
import dev.quwac.kagiroi.schema.nextValue
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.renamedFrom
import dev.quwac.kagiroi.schema.sequence
import dev.quwac.kagiroi.schema.times
import dev.quwac.kagiroi.schema.transform
import dev.quwac.kagiroi.sql.asc
import dev.quwac.kagiroi.sql.desc
import dev.quwac.kagiroi.sql.gt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import dev.quwac.kagiroi.schema.schema as applicationSchema

class SchemaExportTest {
    @Test
    fun projectsManagedPhysicalSchemaAndSeparateRenameEvolution() {
        val exported = canonicalize(exportSchemaDefinition)
        val orders = exported.schema.tables.single { it.name == "orders" }

        assertEquals(CANONICAL_SCHEMA_FORMAT_VERSION, exported.formatVersion)
        assertOrdersPhysicalSchema(exported, orders)
        assertExportedRenameEvolution(exported)
    }

    private fun assertOrdersPhysicalSchema(exported: CanonicalDesiredSchema, orders: CanonicalTable) {
        assertEquals(
            listOf(
                "id",
                "customer_id",
                "customer_region",
                "email",
                "quantity",
                "price",
                "total",
                "status",
                "note",
                "created_at",
                "number",
            ),
            orders.columns.map(CanonicalColumn::name),
        )
        assertFalse(orders.columns.any { it.name == "runtime_label" })
        assertEquals("kagiroi.int64", orders.columns.first().type.id)
        assertEquals(CanonicalIdentity(), orders.columns.first().identity)
        assertEquals(
            CanonicalGeneratedColumnStorage.Stored,
            orders.columns.single { it.name == "total" }.generated?.storage,
        )
        assertTrue(orders.columns.single { it.name == "note" }.nullable)
        assertIs<CanonicalLiteralExpression>(orders.columns.single { it.name == "note" }.default)
        assertEquals("Price in the order currency", orders.columns.single { it.name == "price" }.comment)
        assertEquals("orders must have positive quantities", orders.comment)
        assertEquals(listOf("id", "customer_id"), orders.primaryKey?.columns)
        assertEquals(listOf("email", "status"), orders.uniqueConstraints.single().columns)
        assertEquals("customers", orders.foreignKeys.single().targetTable.name)
        assertEquals(listOf("customer_id", "customer_region"), orders.foreignKeys.single().sourceColumns)
        assertEquals(listOf("id", "region"), orders.foreignKeys.single().targetColumns)
        assertEquals(CanonicalForeignKeyAction.Cascade, orders.foreignKeys.single().onDelete)
        assertIs<CanonicalValueComparison>(orders.checks.single().expression)

        assertEquals(listOf("order_numbers"), exported.schema.sequences.map(CanonicalSequence::name))
        val simpleIndex = exported.schema.indexes.single { it.name == "customers_region_idx" }
        assertIs<CanonicalColumnIndexKey>(simpleIndex.keys.single())

        val index = exported.schema.indexes.single { it.name == "orders_active_email_idx" }
        assertEquals("orders_active_email_idx", index.name)
        assertEquals(CanonicalIndexDirection.Desc, index.keys.first().direction)
        assertIs<CanonicalExpressionIndexKey>(index.keys[1])
        assertEquals(listOf("id"), index.includedColumns.map(CanonicalColumnReference::name))
        assertIs<CanonicalValueComparison>(index.predicate)
    }

    private fun assertExportedRenameEvolution(exported: CanonicalDesiredSchema) {
        assertEquals(emptyList(), exported.evolution.tableRenames)
        assertEquals(
            listOf(
                CanonicalColumnRename(
                    table = CanonicalTableReference(name = "orders"),
                    current = "email",
                    previous = listOf("address", "email_address"),
                ),
            ),
            exported.evolution.columnRenames,
        )
        assertEquals(
            listOf(
                CanonicalIndexRename(
                    current = CanonicalIndexReference(name = "orders_active_email_idx"),
                    previous = listOf("orders_active_address_idx", "orders_email_idx"),
                ),
            ),
            exported.evolution.indexRenames,
        )
    }

    @Test
    fun includesThePhysicalSchemaHashAsDeterministicMetadata() {
        val exported = canonicalize(exportSchemaDefinition)

        assertEquals(CanonicalHashAlgorithm.Sha256, exported.metadata.physicalSchemaHash.algorithm)
        assertEquals(physicalSchemaHash(exported), exported.metadata.physicalSchemaHash.value)
    }

    @Test
    fun preservesOrderedTableAndColumnRenameHistoryOutsidePhysicalSchema() {
        val exported = canonicalize(applicationSchema(RenamedAccounts))

        assertEquals(listOf("id", "public_name"), exported.schema.tables.single().columns.map(CanonicalColumn::name))
        assertEquals(
            listOf(CanonicalTableRename(CanonicalTableReference(name = "accounts"), listOf("members", "users"))),
            exported.evolution.tableRenames,
        )
        assertEquals(
            listOf(
                CanonicalColumnRename(
                    table = CanonicalTableReference(name = "accounts"),
                    current = "public_name",
                    previous = listOf("display_name", "name"),
                ),
            ),
            exported.evolution.columnRenames,
        )
        assertFalse(encodePhysicalSchema(exported.schema).contains("display_name"))
        assertFalse(encodePhysicalSchema(exported.schema).contains("members"))
    }

    @Test
    fun serializesStructuredDefaultGeneratedCheckAndIndexExpressions() {
        val exported = canonicalize(exportSchemaDefinition)
        val orders = exported.schema.tables.single { it.name == "orders" }

        val statusDefault = requireNotNull(orders.columns.single { it.name == "status" }.default)
        assertIs<CanonicalFunctionExpression>(statusDefault)
        assertEquals("lower", statusDefault.function)
        val createdDefault = requireNotNull(orders.columns.single { it.name == "created_at" }.default)
        assertIs<CanonicalFunctionExpression>(createdDefault)
        assertEquals("currentTimestamp", createdDefault.function)
        assertIs<CanonicalSequenceNextValueExpression>(orders.columns.single { it.name == "number" }.default)
        assertIs<CanonicalBinaryExpression>(orders.columns.single { it.name == "total" }.generated?.expression)

        val activeOrdersIndex = exported.schema.indexes.single { it.name == "orders_active_email_idx" }
        val expressionIndex = activeOrdersIndex.keys[1] as CanonicalExpressionIndexKey
        assertIs<CanonicalCaseExpression>(expressionIndex.expression)
        assertIs<CanonicalValueComparison>(activeOrdersIndex.predicate)
    }

    @Test
    fun isDeterministicRoundTrippableAndIndependentOfRegistryOrder() {
        val first = canonicalize(exportSchemaDefinition)
        val second = canonicalize(exportSchemaDefinition)
        val reordered = canonicalize(
            applicationSchema(ExportOrders, ExportCustomers),
        )
        val encoded = encodeCanonicalSchema(first)

        assertEquals(encoded, encodeCanonicalSchema(second))
        assertEquals(encoded, encodeCanonicalSchema(reordered))
        assertEquals(first, decodeCanonicalSchema(encoded))
        assertEquals(physicalSchemaHash(first), physicalSchemaHash(second))
        assertEquals(64, physicalSchemaHash(first).length)
        assertTrue(encoded.contains("\"physicalSchemaHash\""))
        assertFailsWith<IllegalArgumentException> {
            decodeCanonicalSchema(encoded.replace(first.metadata.physicalSchemaHash.value, "0".repeat(64)))
        }
    }

    @Test
    fun usesStandardSha256ForPhysicalSchemaHashes() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
    }

    @Test
    fun excludesRenameHistoryFromPhysicalSchemaHash() {
        val plain = canonicalize(applicationSchema(PlainNames))
        val renamed = canonicalize(applicationSchema(RenamedNames))

        assertEquals(plain.schema, renamed.schema)
        assertNotEquals(plain.evolution, renamed.evolution)
        assertEquals(physicalSchemaHash(plain), physicalSchemaHash(renamed))
        assertEquals(plain.metadata.physicalSchemaHash, renamed.metadata.physicalSchemaHash)
    }

    @Test
    fun exportsSingleColumnIndexRenameLineageOutsidePhysicalSchema() {
        val plain = canonicalize(applicationSchema(PlainIndexedNames))
        val renamed = canonicalize(applicationSchema(RenamedIndexedNames))

        assertEquals(plain.schema, renamed.schema)
        assertEquals(
            listOf(
                CanonicalIndexRename(
                    current = CanonicalIndexReference(name = "names_display_name_idx"),
                    previous = listOf("names_name_idx", "names_legacy_name_idx"),
                ),
            ),
            renamed.evolution.indexRenames,
        )
        assertEquals(physicalSchemaHash(plain), physicalSchemaHash(renamed))
        assertFalse(encodePhysicalSchema(renamed.schema).contains("names_name_idx"))
    }

    @Test
    fun exportsStorageTypesWithoutRuntimeTransformOrEnumObjects() {
        val exported = canonicalize(applicationSchema(TransformedValues, EnumValues, StableExtensionValues))
        val json = encodeCanonicalSchema(exported)

        assertEquals(
            "kagiroi.string",
            exported.schema.tables.single { it.name == "transformed_values" }.columns.single().type.id,
        )
        assertEquals(
            "kagiroi.string",
            exported.schema.tables.single { it.name == "enum_values" }.columns.single().type.id,
        )
        assertEquals(
            CanonicalColumnType("example.fixedText", listOf(CanonicalTypeArgument("length", "32"))),
            exported.schema.tables.single { it.name == "stable_extension_values" }.columns.single().type,
        )
        assertFalse(json.contains("AccountId"))
        assertFalse(json.contains("ExportStatus"))
    }

    @Test
    fun validatesRegistryMembershipAndDuplicateRegistrations() {
        assertFailsWith<IllegalArgumentException> { applicationSchema(ExportCustomers, ExportCustomers) }
        assertFailsWith<IllegalArgumentException> { applicationSchema(UnregisteredReferenceChild) }
        assertFailsWith<IllegalArgumentException> { applicationSchema(DuplicateNameOne, DuplicateNameTwo) }
        assertFailsWith<IllegalArgumentException> { applicationSchema(UndeclaredSequenceDefault) }
    }

    @Test
    fun sealsTableDefinitionsWhenTheyBecomeMigrationRoots() {
        val table = SealedMigrationTable()

        applicationSchema(table)

        assertFailsWith<IllegalArgumentException> { table.value.nullable() }
        assertFailsWith<IllegalArgumentException> { table.column("additional_value", IntColumnType) }
        assertFailsWith<IllegalArgumentException> { table.addIndexAfterRegistration() }
    }

    @Test
    fun exportsTableDeclaredSequencesEvenWithoutDefaultReferences() {
        val exported = canonicalize(applicationSchema(DeclaredSequenceOnly))

        assertEquals(listOf("declared_sequence_only_numbers"), exported.schema.sequences.map(CanonicalSequence::name))
    }

    @Test
    fun permitsDefaultsThatReferenceSequencesDeclaredByAnotherManagedTable() {
        val exported = canonicalize(applicationSchema(SharedSequenceOwner, SharedSequenceConsumer))

        assertEquals(listOf("shared_sequence"), exported.schema.sequences.map(CanonicalSequence::name))
    }

    @Test
    fun sealsForeignKeyBuildersWithTheirRegisteredTables() {
        val parent = SealedReferenceParent()
        val child = SealedReferenceChild(parent)

        applicationSchema(parent, child)

        assertFailsWith<IllegalArgumentException> { child.parentReference.onDelete(ForeignKeyAction.Cascade) }
    }

    @Test
    fun excludesIndexesDeclaredByUnregisteredTables() {
        assertEquals("unregistered_indexed_table", UnregisteredIndexedTable.tableName)
        val exported = canonicalize(applicationSchema(ExportCustomers))

        assertEquals(listOf("customers_region_idx"), exported.schema.indexes.map(CanonicalIndex::name))
        assertFalse(exported.schema.indexes.any { it.name == "unregistered_indexed_table_value_idx" })
    }

    @Test
    fun attachesSingleColumnIndexesToRenamedWritableColumns() {
        val exported = canonicalize(applicationSchema(IndexedRenamedColumn))
        val indexKey = exported.schema.indexes.single().keys.single() as CanonicalColumnIndexKey

        assertEquals("display_name", indexKey.column.name)
        assertEquals(
            listOf("name"),
            exported.evolution.columnRenames.single().previous,
        )
    }

    @Test
    fun rejectsInvalidRenameLineage() {
        assertFailsWith<IllegalArgumentException> {
            object : Table("invalid") {
                init {
                    renamedFrom(" ")
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("invalid") {
                init {
                    renamedFrom("invalid")
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("invalid") {
                init {
                    renamedFrom("old", "old")
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("invalid") {
                val current = string("current").renamedFrom("current")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("invalid") {
                val current = string("current").renamedFrom("old", "old")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("invalid") {
                val value = string("value")

                init {
                    index("current") {
                        on(value)
                        renamedFrom("current")
                    }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            object : Table("invalid") {
                val value = string("value")

                init {
                    index("current") {
                        on(value)
                        renamedFrom("old", "old")
                    }
                }
            }
        }
    }

    @Test
    fun includesCastAndCoalesceDefaultAstNodes() {
        val exported = canonicalize(applicationSchema(ExpressionDefaults))
        val columns = exported.schema.tables.single().columns

        assertIs<CanonicalCastExpression>(columns.single { it.name == "cast_value" }.default)
        assertIs<CanonicalFunctionExpression>(columns.single { it.name == "coalesced_value" }.default)
    }
}

private object ExportCustomers : Table("customers") {
    val id = long("id")
    val region = string("region").index("customers_region_idx")

    init {
        primaryKey(id, region, name = "pk_customers")
    }
}

private object ExportOrders : Table("orders", comment = "orders must have positive quantities") {
    val orderNumbers = sequence("order_numbers")
    val id = long("id").identity()
    val customerId = long("customer_id")
    val customerRegion = string("customer_region")
    val email = string("email").renamedFrom("address", "email_address")
    val quantity = long("quantity")
    val price = long("price").comment("Price in the order currency")
    val total = long("total").generated(quantity * price, GeneratedColumnStorage.Stored)
    val status = string("status").default(lower(literal("PENDING")))
    val note = string("note").nullable().default("draft")
    val createdAt = instant("created_at").default(currentTimestamp())
    val number = long("number").default(orderNumbers.nextValue())
    val runtimeLabel = string("runtime_label").nullable().computed()

    init {
        primaryKey(id, customerId, name = "pk_orders")
        unique(email, status, name = "uq_orders_email_status", comment = "Only one active address")
        foreignKey(customerId, customerRegion, name = "fk_orders_customer")
            .references(ExportCustomers.id, ExportCustomers.region)
            .onDelete(ForeignKeyAction.Cascade)
        checkConstraint("ck_orders_positive") { quantity gt 0L }
        index("orders_active_email_idx", unique = true) {
            renamedFrom("orders_active_address_idx", "orders_email_idx")
            on(
                email.desc(),
                case<String> {
                    whenThen(quantity gt 0L, literal("positive"))
                    elseValue(literal("empty"))
                }.asc(),
            )
            include(id)
            where(quantity gt 0L)
        }
    }
}

private class SealedMigrationTable : Table("sealed_migration_table") {
    val value = string("value")

    fun addIndexAfterRegistration() {
        value.index("sealed_migration_table_value_idx")
    }
}

private object DeclaredSequenceOnly : Table("declared_sequence_only") {
    val numbers = sequence("declared_sequence_only_numbers")
    val value = string("value")
}

private object SharedSequenceOwner : Table("shared_sequence_owner") {
    val numbers = sequence("shared_sequence")
    val value = string("value")
}

private object SharedSequenceConsumer : Table("shared_sequence_consumer") {
    val value = long("value").default(SharedSequenceOwner.numbers.nextValue())
}

private val undeclaredSequence = sequence("undeclared_sequence")

private object UndeclaredSequenceDefault : Table("undeclared_sequence_default") {
    val value = long("value").default(undeclaredSequence.nextValue())
}

private class SealedReferenceParent : Table("sealed_reference_parent") {
    val id = long("id").primaryKey()
}

private class SealedReferenceChild(parent: SealedReferenceParent) : Table("sealed_reference_child") {
    val parentId = long("parent_id")
    val parentReference = foreignKey(parentId).references(parent.id)
}

private object UnregisteredIndexedTable : Table("unregistered_indexed_table") {
    val value = string("value").index("unregistered_indexed_table_value_idx")
}

private object IndexedRenamedColumn : Table("indexed_renamed_column") {
    val displayName = string("display_name")
        .renamedFrom("name")
        .index("indexed_renamed_column_display_name_idx")
}

private val exportSchemaDefinition = applicationSchema(
    ExportCustomers,
    ExportOrders,
)

private object RenamedAccounts : Table("accounts") {
    val id = long("id").primaryKey()
    val publicName = string("public_name").renamedFrom("display_name", "name")

    init {
        renamedFrom("members", "users")
    }
}

private object PlainNames : Table("names") {
    val value = string("value")
}

private object RenamedNames : Table("names") {
    val value = string("value").renamedFrom("old_value")
}

private object PlainIndexedNames : Table("names") {
    val displayName = string("display_name").index("names_display_name_idx")
}

private object RenamedIndexedNames : Table("names") {
    val displayName = string("display_name").index("names_display_name_idx") {
        renamedFrom("names_name_idx", "names_legacy_name_idx")
    }
}

private data class AccountId(val value: String)

private object TransformedValues : Table("transformed_values") {
    private val rawId = string("id")
    val id = rawId.transform(fromStorage = ::AccountId, toStorage = AccountId::value)
}

private enum class ExportStatus {
    Active,
    Disabled,
}

private object EnumValues : Table("enum_values") {
    private val rawStatus = string("status")
    val status = rawStatus.asEnum(ExportStatus.entries) {
        ExportStatus.Active mapsTo "active"
        ExportStatus.Disabled mapsTo "disabled"
    }
}

private object FixedTextType : ColumnType<String>("Fixed text"), StableSchemaColumnType {
    override val schemaTypeDescriptor = SchemaTypeDescriptor(
        "example.fixedText",
        listOf(SchemaTypeArgument("length", "32")),
    )
}

private object StableExtensionValues : Table("stable_extension_values") {
    val value = column("value", FixedTextType)
}

private object UnregisteredReferenceParent : Table("unregistered_reference_parent") {
    val id = long("id").primaryKey()
}

private object UnregisteredReferenceChild : Table("unregistered_reference_child") {
    val parentId = long("parent_id")

    init {
        foreignKey(parentId).references(UnregisteredReferenceParent.id)
    }
}

private object DuplicateNameOne : Table("duplicate") {
    val id = long("id")
}

private object DuplicateNameTwo : Table("duplicate") {
    val id = long("id")
}

private object ExpressionDefaults : Table("expression_defaults") {
    val castValue = int("cast_value").default(cast(literal("12"), intType()))
    val coalescedValue = string("coalesced_value").default(coalesce(literal("first"), literal("second")))
}
