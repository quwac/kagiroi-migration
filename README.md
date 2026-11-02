# Kagiroi Migration

`kagiroi-schema` owns the explicit application registry and its stable, machine-readable
Schema Projection. `kagiroi-migration` consumes that projection to build a desired Canonical
Schema. `Table` definitions remain the sole schema source of truth. The registry names only the
managed tables; it does not redeclare their columns, constraints, indexes, or table-declared
sequences.

The project has no second schema DSL and no stored snapshot file. Its current flow is:

```text
Table definitions ──schema(...).project()──> Schema Projection ──canonicalize──> Desired Canonical Schema

Previous Canonical Schema ──┐
                            ├──diffSchema──> Typed Migration Plan
Desired Canonical Schema ───┘
                                               │
                                               ├──dialect renderer──> raw SQL draft
                                               └──artifact metadata──> final SQL history
```

`kagiroi-migration-core` owns the portable Canonical Schema, diff, typed plan, dialect SQL
generation, and artifact validation. `kagiroi-migration` adds Canonical Schema conversion and
Kotlin/JS export without inspecting runtime Table, Column, or expression implementation classes.
The `dev.quwac.kagiroi.migration` Gradle plugin orchestrates export and writes SQL files; it does
not connect to, introspect, or apply to a database. It never uses Git to find a previous schema.

The migration directory is the history. There is no snapshot, plan, or metadata file beside
the SQL artifacts. A final artifact embeds the physical Canonical Schema that results from the
migration. The next generation reads that embedded schema after validating the parent chain.

## Define and register schema objects

```kotlin
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.default
import dev.quwac.kagiroi.schema.identity
import dev.quwac.kagiroi.schema.nextValue
import dev.quwac.kagiroi.schema.nullable
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.renamedFrom
import dev.quwac.kagiroi.schema.schema

object Users : Table("users") {
    val ids = sequence("users_id_seq")
    val id = long("id").identity().primaryKey()
    val displayName = string("display_name")
        .renamedFrom("name")
        .index("users_display_name_idx")
    val email = string("email").nullable().default(ids.nextValue())

    init {
        index("users_display_name_email_idx") {
            renamedFrom("users_name_email_idx")
            on(displayName, email)
        }
    }
}

val AppSchema = schema(Users)
```

`schema(...)` is a `kagiroi-schema` API and accepts tables only. Use `column.index(name)` for a direct single-column
index. Use the table-scoped `index(name) { ... }` inside `init` for composite,
expression, partial, or covering indexes. Both forms become table metadata and are
exported with the table. They are protected Table members, so an application cannot declare
migration index metadata from outside the Table body. The generic `index(name, table) { ... }`
factory is not a public API.

Use `createIndexes(Users)` to execute every index declared by `Users` without declaring
them again. `createTable(Users)` creates only the table. The explicit
`createIndex(name, table) { ... }` API remains for one-off DDL and does not declare
migration metadata.

Sequences are declared by a registered Table and may be referenced by any registered
Table. A physical DEFAULT that references an undeclared sequence is rejected. The registry
rejects a repeated table instance, duplicate qualified table/sequence names, duplicate index
names in one schema, and a foreign key to an unregistered table. A successful `schema(...)`
call seals every registered Table, so later column, index, constraint, and sequence declarations
are rejected. This makes every managed relation explicit. External objects are not modeled yet.

`computed()` columns remain in the runtime table shape but are omitted from the
managed Canonical Schema because `createTable` does not manage them.

## Rename lineage

Rename lineage is migration evolution metadata, not a current database name and not
DDL input.

```kotlin
object Accounts : Table("accounts") {
    val id = long("id").primaryKey()
    val publicName = string("public_name").renamedFrom(
        "display_name",
        "name",
    )

    init {
        renamedFrom("members", "users")
        index("accounts_public_name_idx") {
            renamedFrom("users_display_name_idx", "users_name_idx")
            on(publicName)
        }
    }
}
```

`renamedFrom(...)` rejects blank names, the current name, and repeated historical
names. Its list keeps declaration order exactly; export never reorders it. The
physical table has `public_name` and `accounts` only. The prior names occur under
`evolution.columnRenames`, `evolution.tableRenames`, and `evolution.indexRenames`, so they do not alter
`createTable`, `createIndex`, or query rendering and do not affect the physical schema
hash.

## Diff and typed migration plan

`diffSchema(previous, desired)` is Pure Kotlin. It only accepts two versioned Canonical Schema
documents and returns a semantic `MigrationPlan`; it does not read files, run a process, use a
database connection, inspect Git, or reconstruct runtime `Table` objects.

```kotlin
val desired = canonicalize(AppSchema)
val previous = decodeCanonicalSchema(previousCanonicalSchemaJson)

when (val plan = diffSchema(previous, desired)) {
    MigrationPlan.NoChanges -> println("No migration required")
    is MigrationPlan.Changes -> plan.operations.forEach { operation ->
        println("${operation.risk.level}: ${operation.risk.reason}")
    }
}
```

Operations are typed semantic data such as `RenameTable`, `AddColumn`, `SetNotNull`,
`DropForeignKey`, `RenameIndex`, `RecreateIndex`, and `CreateSequence`. They contain Canonical identifiers and
Canonical metadata, never a rendered SQL string or a runtime Kotlin object. `MigrationPlan.summary`
counts added, removed, renamed, and changed operations and reports the highest risk.

The diff validates that both documents use the supported `formatVersion` and have a matching
physical-schema hash. It resolves explicit `renamedFrom(...)` claims before current-name matching.
A resolved claim reserves its previous physical object, so a desired object that reuses that old name
is a new object. For example, `name` to `code` plus a new `name` produces
`RenameColumn(name, code)` followed by `AddColumn(name)`. The previous name must be unique within
the same schema/table; ambiguous claims are rejected. A history name that is not present in the
previous schema is allowed, because a rename may already have been applied. Cross-schema moves are
not rename operations in this version.

Rename history is ordered from newer to older declaration history, but the diff only selects a
candidate when exactly one historical name is present. It never guesses from name similarity. After a
rename, property changes use the new identifier; for example, a type change after `name` to
`display_name` produces `RenameColumn(name, display_name)` followed by an
`AlterColumnType(display_name, ...)`.

The plan orders explicit dependencies: sequences before dependent additions, table renames before
later use of their new name, obsolete foreign keys/indexes/constraints before a destructive column or
table change, parent tables before newly created child tables, and foreign keys before a referenced
table is dropped. Tables, sequences, and indexes are otherwise ordered by qualified name. Column and
composite-key order remain semantic and are never sorted.

`Safe` covers non-destructive additions such as a nullable column without a default, creating a
table/sequence/index, relaxing NOT NULL, and comments. `Review` covers renames, constraints,
defaults, type/identity/generated changes, and index replacement. `Destructive` covers dropped
tables, columns, and sequences. Risk is guidance, not a statement that a selected SQL dialect can
execute an operation.

Current comparison is structural: types, nullability, defaults, identities, generated columns,
comments, primary/unique/foreign/check constraints, indexes, and current sequence shape are compared
as Canonical values. Defaults, generated columns, CHECKs, predicates, and expression-index keys retain
their structured Canonical ASTs. Sequence options have no current Canonical representation, so this
version creates and drops sequences but emits no `AlterSequence`. An explicit Index rename with an
otherwise unchanged Canonical Index emits `RenameIndex`. An Index definition change emits
`RecreateIndex`, even when rename lineage exists. Constraint and sequence rename metadata remain
reserved and are not yet resolved.

## Generate and finalize SQL artifacts

Apply the migration plugin to the Kotlin Multiplatform project that owns the registered schema.
The project needs a Kotlin/JS `js` target and its `jsMain` dependencies must include
`kagiroi-migration`, which provides the generated entrypoint's `NodeSchemaExporter` dependency.

```kotlin
plugins {
    kotlin("multiplatform")
    id("dev.quwac.kagiroi.migration")
}

kagiroi {
    schema.set("com.example.AppSchema")
    dialect.set("postgresql")
    migrationsDirectory.set(
        layout.projectDirectory.dir("src/main/resources/kagiroi/migrations"),
    )
}
```

`schema` is the fully qualified Kotlin expression for the value returned by `schema(...)`.
Configuration belongs to the project to which the plugin is applied, so a multi-project build
selects its schema explicitly by applying and invoking that project's task.

Generate a named draft without an interactive prompt:

```sh
./gradlew kagiroiGenerateMigration \
  -PkagiroiMigrationName=add_display_name
```

The name must be lower snake case. The generated ID is
`yyyyMMddHHmmss_<name>` in UTC, so its file name is both stable and lexicographically ordered:
`20261031101500_add_display_name.sql`.

The task creates `src/main/resources/kagiroi/migrations` by default. It validates every existing
artifact header, finalized checksum, filename, and linear parent chain before it finds the latest
migration. A second draft is rejected. With no artifacts, the previous schema is empty and the
first draft contains create operations. With no schema difference, the task succeeds with
`No schema changes.` and writes no file.

The plugin generates a Kotlin/JS source file below
`build/generated/kagiroiMigration/jsMain/kotlin`, compiles the development executable, then runs
its module with Node. The generated program writes Canonical Schema JSON only to stdout; its
output is kept under `build/kagiroi/migration/schema.json`. Application code does not define a
`main()` or invoke Node directly. The portable schema core has no Node API, so another JavaScript
runtime such as Bun can be supported without changing the model.

Each generated body is raw, directly executable dialect SQL with statement terminators. Kagiroi
does not add a transaction wrapper. Statements remain separate for reviewability; an operation
that is not representable by the selected dialect fails generation before the artifact is written.
SQLite and D1 use only their supported native ALTER operations and never trigger an automatic
table rebuild. SQL Server default-constraint changes fail when their named constraint cannot be
represented safely.

Edit the draft SQL body as needed, then finalize its one draft:

```sh
./gradlew kagiroiFinalizeMigration
```

Finalization exports the current schema again. It rejects the draft if its
`desiredSchemaHashAtGeneration` differs from the current physical-schema hash; regenerate the
draft after changing the table definitions. It does not parse, execute, or otherwise interpret
the SQL body. Finalization is the user's assertion that the edited SQL produces the current
physical schema. Raw vendor objects such as `CREATE EXTENSION` that are absent from the Canonical
Schema remain unmanaged by future diffs.

## SQL artifact format

Every migration is a `.sql` file whose first block comment is the machine-readable artifact
metadata. For a draft it records `formatVersion`, `id`, `parent` (or `null` for the root), stable
`dialect` ID, `status: "draft"`, the desired-schema hash at generation, and the plan risk summary.
A final artifact additionally records `sqlSha256`, `resultSchemaHash`, and `resultSchema`.

```sql
/*
kagiroi:
{
  "formatVersion": 1,
  "id": "20261031101500_add_display_name",
  "parent": "20261030120000_init",
  "dialect": "postgresql",
  "status": "final",
  "desiredSchemaHashAtGeneration": "...",
  "sqlSha256": "...",
  "resultSchemaHash": "...",
  "resultSchema": { "tables": [], "sequences": [], "indexes": [] },
  "risk": { "highest": "review", "reviewRequired": true }
}
*/

ALTER TABLE "users"
    RENAME COLUMN "name" TO "display_name";
```

`resultSchema` is the physical Canonical Schema only: it deliberately excludes
`renamedFrom(...)` and other evolution hints. `resultSchemaHash` is SHA-256 of its deterministic
physical JSON. The artifact parser reads the leading comment only and preserves the SQL body when
rewriting metadata. JSON is written in a stable order and escapes a possible `*/` in user schema
text, so it cannot close the comment early.

`sqlSha256` is SHA-256 of the SQL body UTF-8 bytes after normalizing CRLF and CR line endings to
LF; the metadata header is excluded. The Gradle writer itself writes LF files. Changing a finalized
body therefore produces a checksum mismatch when the chain is next read. Checksum validation is
available in the core now; `kagiroiVerifyMigration` is intentionally a later task.

## Export JSON on Kotlin/JS Node

The Schema Projection and Canonical Schema codec are common Kotlin; the Node adapter is a thin Kotlin/JS API.
The Gradle plugin is the normal export path. For an integration that needs to own its own
JavaScript entrypoint, `NodeSchemaExporter.write(AppSchema)` writes exactly one compact Canonical
Schema JSON document to stdout. It emits no diagnostic output to stdout; callers that add
diagnostics must use stderr. No database credentials, JDBC/JVM reflection, JavaScript class
scanning, or Kotlin-source parsing is involved.

For programmatic use, call `AppSchema.project()` to obtain Kagiroi's versioned projection, then
`canonicalize(AppSchema)`, `exportSchema(AppSchema)`, `decodeCanonicalSchema(json)`, and
`physicalSchemaHash(...)`. The migration adapter rejects an unsupported projection format version.

## Canonical format

The exported document has this top-level form:

```json
{
  "formatVersion": 1,
  "schema": {
    "tables": [],
    "sequences": [],
    "indexes": []
  },
  "evolution": {
    "tableRenames": [],
    "columnRenames": [],
    "indexRenames": [],
    "constraintRenames": [],
    "sequenceRenames": []
  },
  "metadata": {
    "physicalSchemaHash": {
      "algorithm": "sha256",
      "value": "..."
    }
  }
}
```

`schema` contains only the current managed physical shape: tables, columns, comments,
constraints, sequences, and table-declared indexes. Column order, composite constraint
positions, index-key order, and index direction are preserved. Tables, sequences, and
indexes are sorted by qualified name, so registry order does not affect output.

Defaults, generated expressions, checks, partial-index predicates, and expression
keys are represented as a typed canonical expression tree. The format stores neither
rendered SQL nor runtime object identity. Column references use qualified table and
column names. Kotlin transforms and enum instances are reduced to their storage type
and storage values; functions and lambda references are never serialized.

Every column type has a stable `id` plus ordered string arguments. Kagiroi built-ins
use `kagiroi.*` identifiers. An extension type whose storage representation cannot be
reduced to a built-in type must implement `StableSchemaColumnType` and provide a
`SchemaTypeDescriptor`; implementation class names are never part of the format.
Generic types remain dialect-neutral. When an application explicitly selects a
dialect-specific Kagiroi column type, its descriptor uses that dialect's stable
`kagiroi.<dialect>.declared` ID and a validated declaration argument, preserving that
explicit physical choice without relying on a Kotlin implementation name.

`formatVersion` and `metadata.physicalSchemaHash` are mandatory. The JSON codec uses
deterministic property and object ordering, and `decodeCanonicalSchema` round-trips
the value model after verifying the hash. `physicalSchemaHash` uses SHA-256 over the
canonical `schema` JSON only, excluding the `evolution` rename hints. It is a
desired-physical-schema hash, not a migration checksum.
