# Kagiroi Migration

Kagiroi Migration generates and verifies database migrations from registered Kagiroi `Table` definitions.

```text
Kotlin Table definitions
        ↓
Kagiroi Schema Projection
        ↓
Canonical Schema / Schema Delta
        ↓
raw SQL migration artifacts
```

`Table` definitions are the only source of managed schema state.

Migration generation does not inspect a database, read Git history, or persist a standalone schema snapshot.

## Modules

| Module | Purpose |
| --- | --- |
| `migration-core` | Canonical schema integration, deltas, artifacts, SQL lexer/parser, hashes, SQL generation, and verification. |
| `migration-gradle-plugin` | Kotlin/JS schema export and the Generate, Finalize, and Verify Gradle tasks. |
| `migration-cli` | Apply, dry-run, resolve, target, connector, lock, and history interfaces for a Node/Bun host. |

The dependency direction is one-way: this repository depends on Kagiroi.

Kagiroi does not depend on this repository and has no application-startup migration API.

## Gradle workflow

Apply the plugin to the Kotlin Multiplatform project that owns the schema.

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

`AppSchema` is the value returned by `schema(Users, Orders)`.

The plugin writes an exporter below `build/generated/`, adds it to the Kotlin/JS compilation, and runs the resulting Node module.

The generated source calls Kagiroi's public `AppSchema.project().encode()` API and prints only that JSON to stdout.

Gradle converts the projection to Canonical Schema JSON under `build/kagiroi/migration/schema.json`.

No application `main()` is required.

```sh
./gradlew kagiroiGenerateMigration \
  -PkagiroiMigrationName=add_display_name

# Review and edit the SQL body.
./gradlew kagiroiFinalizeMigration

./gradlew kagiroiVerifyMigration
```

Generation replays every finalized artifact in filename order and diffs that schema with the current registered tables.

No change produces `No schema changes.` and does not create an empty migration.

The first migration starts from an empty Canonical Schema.

One unfinalized artifact is allowed, and it must be the last filename.

## Artifact format

A migration is a directly executable `.sql` file.

Its filename is both identity and ordering:

```text
20261101114500_add_email.sql
```

Migration ordering is defined solely by migration filenames.

The filename stem is the migration ID.

The leading block comment is open, versioned JSON defined by [migration-artifact-v1.schema.json](migration-artifact-v1.schema.json).

Draft artifacts have an empty `steps` array.

Finalizing parses transaction and batch boundaries, assigns the generated operations to those boundaries, and writes one Delta and one hash per execution step.

```sql
/*
kagiroi:
{
  "formatVersion": 1,
  "dialect": "postgresql",
  "steps": [
    {
      "index": 0,
      "kind": "transactional",
      "sha256": "…",
      "schemaDelta": {
        "operations": [
          {
            "type": "renameColumn",
            "table": { "name": "users" },
            "from": "name",
            "to": "display_name",
            "risk": { "level": "review", "reason": "…" }
          }
        ]
      }
    }
  ]
}
*/

BEGIN;

-- kagiroi:operation=0:…
ALTER TABLE "users" RENAME COLUMN "name" TO "display_name";

COMMIT;
```

An artifact does not contain a parent, a status, a migration-wide SQL hash, or a full Canonical Schema snapshot.

Schema history is represented as step-level canonical schema deltas.

The next generation reconstructs the previous schema by replaying those deltas from an empty schema.

Migration artifacts are an open, versioned format documented by JSON Schema.

## SQL editing and steps

The body is raw SQL.

You can add DML, functions, procedures, triggers, extensions, and vendor-specific objects.

Objects that have no Kagiroi Canonical Schema representation remain unmanaged.

Transaction boundaries are part of the SQL artifact.

Kagiroi does not implicitly wrap migrations in transactions at apply time.

For example, this is a valid edited PostgreSQL migration:

```sql
BEGIN;

-- kagiroi:operation=0:…
ALTER TABLE "users" ADD COLUMN "display_name" TEXT;

COMMIT;

BEGIN;

UPDATE "users"
SET "display_name" = "name"
WHERE "display_name" IS NULL;

-- kagiroi:operation=1:…
ALTER TABLE "users" ALTER COLUMN "display_name" SET NOT NULL;

COMMIT;
```

The `-- kagiroi:operation=<index>:<sha256>` marker belongs to each generated managed operation.

It follows the statement when you move it between transactions.

Finalization rejects a missing, repeated, or changed marker instead of guessing which step owns the Schema Delta.

DML has no marker and results in an empty `operations` array for a DML-only step.

Finalization does not format the SQL body and does not parse SQL into a schema.

It records the current Table-definition diff as the user's assertion about the SQL result.

Changing managed tables after generation adds or changes the expected operations, so finalization rejects the old draft until it is regenerated.

## Verification

`kagiroiVerifyMigration` does not connect to a database.

It validates the artifact header, format version, filename rule, dialect, script structure, transaction and batch steps, marker assignments, step hashes, and filename-order Delta replay.

The reconstructed physical schema must match the current Kotlin schema.

`migration-core` also provides `MigrationExecutor` for hosts that supply a `MigrationDatabase` adapter.
Its suspend functions validate and apply finalized artifacts, inspect pending steps, and resolve history.
The caller owns the adapter's connection lifecycle.

For a Worker, wrap a Kagiroi Hyperdrive database in `HyperdriveMigrationDatabase` and pass it to
`MigrationExecutor`. Supply a fresh unique lock owner for each invocation and close the adapter
when finished:

```kotlin
val database = HyperdrivePostgresDatabase(env.HYPERDRIVE)
val migrationDatabase = HyperdriveMigrationDatabase(
    database = database,
    dialect = MigrationHistoryDialect.PostgreSql,
    newOwner = { createUniqueLockOwner() },
)
try {
    MigrationExecutor(migrationDatabase).apply(artifacts)
} finally {
    migrationDatabase.close()
}
```

Use `MigrationHistoryDialect.MySql` with `HyperdriveMySqlDatabase` for MySQL and MariaDB.
The Worker supplies finalized artifacts and the unique lock-owner function. Use a Hyperdrive
configuration with query caching disabled so migration history reads are current. Do not use
the database concurrently through another reference while the adapter is running.

Hyperdrive uses a reserved history row as its migration lock. If a Worker stops without releasing
it, inspect the database and call `clearMigrationLock()` explicitly after confirming the lock is
stale. Direct connections use different locks and must not run migrations concurrently with
Hyperdrive against the same database.

The SQL lexer recognizes normal strings, quoted identifiers, comments, PostgreSQL dollar quotes, MySQL/MariaDB `DELIMITER`, SQL Server `GO`, and Oracle PL/SQL `/` terminators.

It is not a SQL AST parser and never derives a Schema Delta from handwritten SQL.

Step hashes use SHA-256 over dialect-aware lexical tokens.

Whitespace, indentation, line endings, and SQL keyword case do not affect the hash.

Identifier case, literal values, SQL structure, and non-header comments do affect it.

## SQL generation

`MigrationPlan` is converted to a semantic DDL model before a dialect renderer produces `MigrationSqlStatement` values.

Unsupported native operations fail generation before a file is written.

SQLite and D1 do not receive an automatic table rebuild.

PostgreSQL, SQLite, D1, and SQL Server drafts use explicit transactions for generated transactional DDL. The D1 adapter submits each such step as one atomic D1 batch.

MySQL, MariaDB, and Oracle drafts do not wrap DDL because their DDL can commit implicitly.

## Apply integration

`migration-cli` keeps database access behind `MigrationDatabase` and `MigrationTargetConnector`.

A connector owns the provider connection, secret lookup, proxy interpretation, database lock, and the physical `kagiroi_migrations` table.

Its logical columns are `migration_id`, `step_index`, `step_sha256`, `status`, and `applied_at`, with `(migration_id, step_index)` as the primary key.

`MigrationExecutor` performs artifact preflight before dry-run and after acquiring the lock for apply.

It rejects unknown local history, hash changes, gaps, and `executing` rows.

Transactional steps write `applied` history before their artifact `COMMIT`.

Non-transactional steps write `executing`, execute SQL, then mark `applied`.

An `executing` row is ambiguous and is never retried automatically.

Resolve operations are explicit: mark an inspected step applied, delete it to make it pending, or accept a reviewed replacement SHA-256.

Connection targets have a provider, optional connection-string environment variable, optional proxy settings, and provider-specific configuration.

The bundled Node/Bun executable includes these connectors:

| Provider | Artifact dialect | Target configuration | Lock and transaction handling |
| --- | --- | --- | --- |
| `postgresql` | PostgreSQL | `connectionStringEnv` | PostgreSQL advisory lock on one leased `node-postgres` connection. |
| `mysql` | MySQL | `connectionStringEnv` | `GET_LOCK` on one `mysql2` connection. |
| `mariadb` | MariaDB | `connectionStringEnv` | `GET_LOCK` on Kagiroi's `mysql2` transport. |
| `oracle` | Oracle | `OracleTargetConfig` | `DBMS_LOCK` on one `oracledb` connection. |
| `pglite` | PostgreSQL | `connectionStringEnv` filesystem path | PostgreSQL-compatible advisory lock on one PGlite database handle. |
| `sqlserver` | SQL Server | `connectionStringEnv` | `sp_getapplock` with `LockOwner = Session` on one checked-out mssql connection. |
| `sqlite` | SQLite | `connectionStringEnv` filesystem path | A reserved `kagiroi_migrations` row. |
| `d1` | D1 | `D1TargetConfig` | A reserved history row; transactional steps are submitted as one D1 HTTP API batch. |

`OracleTargetConfig` uses a connect string plus user/password environment names.

`D1TargetConfig` uses account ID, database ID, and an API-token environment name. Its token needs D1 read/write access.

No target model stores a password or token value.

The `proxy` shape is passed to every connector unchanged. The bundled direct connectors currently reject it; a provider-specific transport adapter must opt into proxy support.

SQLite and D1 reserve `migration_id = '__kagiroi_lock__', step_index = -1` only while applying. It is not a migration history entry. If a process ends after reserving that row, inspect the target before explicitly clearing it with `kagiroi migration resolve --clear-lock`.

```json
{
  "migrationsDirectory": "src/main/resources/kagiroi/migrations",
  "targets": {
    "production": {
      "provider": "postgresql",
      "connectionStringEnv": "DATABASE_URL"
    }
  }
}
```

SQL Server uses the existing `mssql` connection-string format:

```json
{
  "targets": {
    "production": {
      "provider": "sqlserver",
      "connectionStringEnv": "SQLSERVER_URL"
    }
  }
}
```

D1 uses provider-specific identifiers while keeping the API token in the environment:

```json
{
  "targets": {
    "production": {
      "provider": "d1",
      "providerSpecific": {
        "accountId": "<cloudflare-account-id>",
        "databaseId": "<d1-database-id>",
        "apiTokenEnv": "CLOUDFLARE_D1_API_TOKEN"
      }
    }
  }
}
```

```sh
kagiroi migration apply --dry-run --target production
kagiroi migration apply --target production

# Only after inspecting the database state.
kagiroi migration resolve \
  --target production \
  --migration 20261101114500_add_email \
  --step 0 \
  --mark-pending

# Only after confirming that a SQLite or D1 lock reservation is stale.
kagiroi migration resolve --clear-lock --target production
```

The CLI accepts `--config <path>` and otherwise reads `kagiroi.migration.json`.

The Hyperdrive adapter uses Kagiroi's SQL API from the Kotlin/JS target. Other provider-specific connectors stay outside `migration-core`.

The `MigrationTargetConnector` interface receives the full target, including `proxy` and `providerSpecific`, without the CLI interpreting proxy transport.

## Deliberate boundaries

This repository does not add `database.migrate()`, startup migration, database introspection, drift detection, a migration DAG, down-migration generation, SQL-to-schema reconstruction, automatic backfills, automatic repair, or automatic retry of ambiguous non-transactional work.
