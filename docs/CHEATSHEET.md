# Kagiroi Migration cheat sheet

Run application commands from the consuming build's root. These examples use `:app` as the schema-owning Gradle project and `migrations/` as the SQL directory. Replace `:app` with your module path.

## Project setup

Make the unreleased plugin available through a local checkout:

```sh
export KAGIROI_MIGRATION_DIR=/absolute/path/to/kagiroi-migration
```

Merge this into the application's `settings.gradle.kts`:

```kotlin
pluginManagement {
    includeBuild(providers.environmentVariable("KAGIROI_MIGRATION_DIR").get()) {
        name = "kagiroi-migration"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Apply the plugin and add the Kagiroi dependency as shown in [Generate and verify migrations](../README.md#generate-and-verify-migrations). Configure `app/build.gradle.kts` to use your registered schema:

```kotlin
kagiroi {
    schema.set("example.AppSchema")
    dialect.set("postgresql")
    migrationsDirectory.set(rootProject.layout.projectDirectory.dir("migrations"))
}
```

The CLI configuration below uses the same directory. For local Kagiroi source substitution, see [Contributing](../CONTRIBUTING.md#setup).

## Build the CLI

Build from the Kagiroi Migration checkout, using `-p` so the command works from the application directory:

```sh
"$KAGIROI_MIGRATION_DIR/gradlew" -p "$KAGIROI_MIGRATION_DIR" \
  :migration-cli:jsDevelopmentExecutableCompileSync :kotlinNpmInstall

export CLI="$KAGIROI_MIGRATION_DIR/build/js/packages/kagiroi-migration-migration-cli/kotlin/kagiroi-migration-migration-cli.js"
node "$CLI" --help
```

Use the generated Node package path above when running the CLI.

## Configure a database target

Create `kagiroi.migration.json` in the application root:

```json
{
  "migrationsDirectory": "migrations",
  "targets": {
    "default": {
      "provider": "postgresql",
      "connectionStringEnv": "DATABASE_URL"
    }
  }
}
```

Set `DATABASE_URL` to your PostgreSQL connection string. The database must already exist, and the migration user needs permission to execute the SQL.

### CLI providers

| Database | `provider` | Configuration |
| --- | --- | --- |
| PostgreSQL | `postgresql` | `connectionStringEnv` names the environment variable containing the connection string |
| MySQL | `mysql` | `connectionStringEnv` names the environment variable containing the connection string |
| MariaDB | `mariadb` | `connectionStringEnv` names the environment variable containing the connection string |
| SQL Server | `sqlserver` | `connectionStringEnv` names the environment variable containing the connection string |
| SQLite | `sqlite` | `connectionStringEnv` names the environment variable containing the database file path |
| PGlite | `pglite` | `connectionStringEnv` names the environment variable containing the database directory path; uses PostgreSQL migrations |
| Oracle | `oracle` | `providerSpecific` contains `connectString`, `userEnv`, and `passwordEnv` |
| D1 | `d1` | `providerSpecific` contains `accountId`, `databaseId`, and `apiTokenEnv` |

The six providers using `connectionStringEnv` reject `providerSpecific`. Oracle and D1 reject `connectionStringEnv`. All bundled connectors reject `proxy`; the Hyperdrive adapter below is a separate programmatic API.

Keep credentials in environment variables. The database user needs permission to execute the migration SQL and manage the `kagiroi_migrations` history table.

### Oracle

Create an Oracle target with the connection identifier and the names of credential environment variables:

```json
{
  "migrationsDirectory": "migrations",
  "targets": {
    "oracle": {
      "provider": "oracle",
      "providerSpecific": {
        "connectString": "localhost:1521/FREEPDB1",
        "userEnv": "ORACLE_USER",
        "passwordEnv": "ORACLE_PASSWORD"
      }
    }
  }
}
```

Set `ORACLE_USER` and `ORACLE_PASSWORD`, then use `--target oracle`. Generate the migrations with `dialect.set("oracle")` in Gradle.

### D1

The D1 connector uses the Cloudflare HTTP API. Configure an account ID, database ID, and the name of an environment variable holding an API token with D1 access:

```json
{
  "migrationsDirectory": "migrations",
  "targets": {
    "d1": {
      "provider": "d1",
      "providerSpecific": {
        "accountId": "your-account-id",
        "databaseId": "your-database-id",
        "apiTokenEnv": "CLOUDFLARE_API_TOKEN"
      }
    }
  }
}
```

Set `CLOUDFLARE_API_TOKEN`, then use `--target d1`. Generate the migrations with `dialect.set("d1")` in Gradle. Transactional steps are sent as batches.

## Generate schema changes

Change the Kotlin table definitions, then generate and verify:

```sh
./gradlew :app:kagiroiGenerateMigration -PkagiroiMigrationName=add_email
./gradlew :app:kagiroiVerifyMigration
```

The name uses lower snake case. Generation writes a ready-to-apply `.sql` file with format-1 metadata. If the schema has no changes, it prints `No schema changes.` and creates no file.

Keep files headed `DO NOT EDIT THIS FILE` unchanged. Make the next schema change in Kotlin and generate another file.

To inspect the exported Kotlin schema:

```sh
./gradlew :app:kagiroiExportSchema
```

The result is `app/build/kagiroi/migration/schema.json`.

## Write data changes directly

Create `migrations/YYYYMMDDHHMMSS_seed_users.sql` in your editor. Replace the timestamp with 14 UTC digits and choose a filename after the existing migrations. The SQL below assumes your schema defines `users.name`.

```sql
BEGIN;
INSERT INTO users (name) VALUES ('demo');
COMMIT;
```

Handwritten SQL needs no creation task, JSON header, or `finalize`. Its dialect comes from the configured target. Edit it before application; keep applied files unchanged and add a new file for later changes.

SQL controls transactions. The CLI adds no implicit transaction wrapper. Kotlin-managed table structure belongs in generated schema migrations; handwritten SQL contributes no schema delta to future Kotlin schema generation.

## Verify and apply

```sh
./gradlew :app:kagiroiVerifyMigration
node "$CLI" migration apply --dry-run
node "$CLI" migration apply
```

| Command | Checks and effects |
| --- | --- |
| `kagiroiVerifyMigration` | Parses files and script boundaries, checks dialects, and compares replayed schema deltas with the current Kotlin definitions; does not connect to the database |
| `migration apply --dry-run` | Parses files and compares steps with DB history, including hashes, gaps, and incomplete steps; executes no migration SQL and changes no history |
| `migration apply` | Runs preflight under a migration lock, then executes pending steps and records their history |

These checks do not compare the live database schema with Kotlin or prove that handwritten SQL has the intended effect. An applied step with a different SQL hash is rejected; already-applied steps are skipped.

### Select another target or configuration

`--target` names an entry in `targets`, and defaults to `default`. `--config` defaults to `kagiroi.migration.json` in the current working directory.

```sh
node "$CLI" migration apply --dry-run \
  --target staging --config deploy/migrations.json
node "$CLI" migration apply \
  --target staging --config deploy/migrations.json
```

The selected configuration must define the named target.

## Repair database history

Inspect the affected SQL and database first. Undo changes left by an incomplete non-transactional step before retrying it.

```sh
node "$CLI" migration repair --dry-run
node "$CLI" migration repair
node "$CLI" migration apply --dry-run
node "$CLI" migration apply
```

`repair` changes history only: it removes incomplete `executing` records and can align applied-step checksums with local SQL. It does not roll back SQL or fix the database schema. Review checksum changes before accepting them. Even the repair preview acquires a lock and may create the history table.

### Clear a stale reserved-row lock

For SQLite or D1, confirm that no migration is running before clearing a stale lock. For example, if your configuration defines a D1 target named `d1`:

```sh
node "$CLI" migration repair --clear-lock --target d1
```

`--clear-lock` cannot be combined with `--dry-run`. Direct PostgreSQL and MySQL connections use different locks. For the Hyperdrive Worker API, see the [Hyperdrive adapter](#hyperdrive).

## Recover generated JSON metadata

Only the latest migration's metadata can be regenerated. Substitute its actual filename:

```sh
./gradlew :app:kagiroiRegenerateMigrationMetadata \
  -PkagiroiMigrationFile=20261101110000_add_email.sql
```

Recovery uses earlier migration deltas and the current Kotlin definitions, preserving the SQL body. Restore older generated files from version control. This command does not repair database history, and handwritten SQL never requires metadata recovery.

## Hyperdrive

`migration-core` exposes the suspend `MigrationExecutor` API and a Kotlin/JS `HyperdriveMigrationDatabase` adapter for PostgreSQL, MySQL, and MariaDB. Use them in a dedicated migration Worker with its own Hyperdrive binding and database credentials. The API itself does not enforce separation from the application Worker.

Supply the Kagiroi Hyperdrive database as `database` and a list of `NamedMigrationArtifact` values as `artifacts`. The example's `createUniqueLockOwner()` must return a fresh unique owner for each lock acquisition, between 1 and 64 characters long:

```kotlin
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

For MySQL or MariaDB, use `MigrationHistoryDialect.MySql`. Disable Hyperdrive query caching and avoid concurrent use of the wrapped database.

Hyperdrive uses a reserved history row for its lock. Direct PostgreSQL and MySQL connections use different locks, so do not run both paths against the same database concurrently. If a Hyperdrive lock becomes stale, inspect the database and confirm that no migration is running before calling `clearMigrationLock()`.

The [local Worker fixture](../integration-tests/hyperdrive/README.md) exercises this adapter against PostgreSQL or MySQL. It does not reproduce Cloudflare's remote pooling or caching.

## Common failures

| Symptom | Action |
| --- | --- |
| `No schema changes.` | No generated file is needed; create a plain `.sql` file directly for data changes |
| File contains no executable SQL | Add SQL; comments alone are not an executable migration |
| Replayed schema differs from Kotlin | Check the definitions and generated history; handwritten SQL does not update the recorded schema |
| Applied-step hash mismatch | Check the applied file and DB state; restore unintended file edits before considering history repair |
| Step is still `executing` | Inspect and undo incomplete DB changes, preview history repair, then retry |
| Generated metadata is missing or malformed | Restore the generated file, or explicitly recover its metadata if it is the latest migration |

For artifact details and provider limits, see the [README](../README.md). For repository tooling and tests, see [Contributing](../CONTRIBUTING.md).
