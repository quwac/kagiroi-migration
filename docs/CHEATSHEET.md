# Kagiroi Migration cheat sheet

Run from the application root. Examples use `:app` for the schema project and `migrations/` for SQL files.

## CLI installation options

Default commands: [Installation](../README.md#installation).

macOS / Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/quwac/kagiroi-migration/main/install.sh \
  | sh -s -- --version 0.1.0 --install-dir "$HOME/.local/bin"
```

Windows (PowerShell):

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/quwac/kagiroi-migration/main/install.ps1))) `
  -Version 0.1.0 -InstallDir "$env:LOCALAPPDATA\Programs\Kagiroi\bin"
```

| Setting | macOS / Linux | Windows |
| --- | --- | --- |
| Default directory | `~/.local/bin` | `$env:LOCALAPPDATA\Programs\Kagiroi\bin` |
| PATH | Add the directory to your shell configuration | Updated automatically; `-NoModifyPath` disables updates |

Both installers accept `KAGIROI_VERSION` and `KAGIROI_INSTALL_DIR` environment variables.

## CLI cache

| OS | Default directory |
| --- | --- |
| macOS / Linux | `$XDG_CACHE_HOME/kagiroi`, or `~/.cache/kagiroi` |
| Windows | `$env:LOCALAPPDATA\Kagiroi\Cache` |

Override with `KAGIROI_CACHE_DIR`. Remove the cache only while the CLI is stopped; it is recreated on next use.

## Project setup

Apply the plugin and add the schema dependency using [Generate and verify migrations](../README.md#generate-and-verify-migrations).
In `app/build.gradle.kts`:

```kotlin
kagiroi {
    schema.set("example.AppSchema")
    dialect.set("postgresql")
    migrationsDirectory.set(rootProject.layout.projectDirectory.dir("migrations"))
}
```

`example.AppSchema` is a registered schema, such as `val AppSchema = schema(Users, Orders)`.
For an unreleased plugin or CLI, see [source checkout setup](../CONTRIBUTING.md#use-a-source-checkout).

## Configure a database target

`kagiroi.migration.json`:

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

Match `migrationsDirectory` to Gradle. Create the PostgreSQL database and set `DATABASE_URL`; grant migration SQL and history-table permissions.

### CLI providers

| Database | `provider` | Gradle `dialect` | Target configuration |
| --- | --- | --- | --- |
| PostgreSQL | `postgresql` | `postgresql` | `connectionStringEnv`: connection-string variable |
| MySQL | `mysql` | `mysql` | `connectionStringEnv`: connection-string variable |
| MariaDB | `mariadb` | `mariadb` | `connectionStringEnv`: connection-string variable |
| SQL Server | `sqlserver` | `sqlserver` | `connectionStringEnv`: connection-string variable |
| SQLite | `sqlite` | `sqlite` | `connectionStringEnv`: database-file variable |
| PGlite | `pglite` | `postgresql` | `connectionStringEnv`: database-directory variable |
| Oracle | `oracle` | `oracle` | `providerSpecific`: `connectString`, `userEnv`, `passwordEnv` |
| D1 | `d1` | `d1` | `providerSpecific`: `accountId`, `databaseId`, `apiTokenEnv` |

Credentials belong in environment variables. Do not combine `connectionStringEnv` and `providerSpecific`; CLI connectors reject `proxy`.

### Oracle

`targets.oracle`:

```json
{
  "provider": "oracle",
  "providerSpecific": {
    "connectString": "localhost:1521/FREEPDB1",
    "userEnv": "ORACLE_USER",
    "passwordEnv": "ORACLE_PASSWORD"
  }
}
```

Set `ORACLE_USER` and `ORACLE_PASSWORD`; select `--target oracle`.

### D1

`targets.d1`:

```json
{
  "provider": "d1",
  "providerSpecific": {
    "accountId": "your-account-id",
    "databaseId": "your-database-id",
    "apiTokenEnv": "CLOUDFLARE_API_TOKEN"
  }
}
```

Set `CLOUDFLARE_API_TOKEN`; select `--target d1`.

## Generate schema changes

Edit the Kotlin table definitions, then run:

```sh
./gradlew :app:kagiroiGenerateMigration -PkagiroiMigrationName=add_email
./gradlew :app:kagiroiVerifyMigration
```

Names use lower snake case. No schema change means no new file. Keep generated files unchanged.

```sh
./gradlew :app:kagiroiExportSchema
```

Export output: `app/build/kagiroi/migration/schema.json`.

## Native row-level security

Declare policies in `Table.init` and include all contributing tables in the registered schema.

| Database | Kagiroi table API |
| --- | --- |
| PostgreSQL / PGlite | `postgresRls { ... }` |
| SQL Server | `sqlServerRls("policy") { ... }` |
| Oracle | `oracleVpd("policy") { ... }` |
| MySQL / MariaDB / SQLite / D1 | Unsupported |

Use the normal generation commands. Predicate functions, roles, and trusted packages must already exist. Oracle VPD operations use implicit commits.
Details: [native row-level security](../README.md#native-row-level-security).

## Write data changes directly

Create `migrations/YYYYMMDDHHMMSS_seed_users.sql`, using 14 UTC digits after existing files.
PostgreSQL example, assuming `users.name` exists:

```sql
BEGIN;
INSERT INTO users (name) VALUES ('demo');
COMMIT;
```

No header or creation task is required. Handwritten SQL contributes no schema delta. Keep applied files unchanged.
Write transactions for your dialect explicitly; the CLI adds no wrapper.

## Verify and apply

```sh
./gradlew :app:kagiroiVerifyMigration
kagiroi migration apply --dry-run
kagiroi migration apply
```

| Command | Checks / effects |
| --- | --- |
| `kagiroiVerifyMigration` | Files against Kotlin schema; no DB connection |
| `migration apply --dry-run` | Files against DB history; no migration SQL or history changes |
| `migration apply` | Acquires a lock, applies pending steps, records history |

Hash mismatches and incomplete steps block application. These commands do not compare the live DB schema with Kotlin.

### Select another target or configuration

| Option | Default |
| --- | --- |
| `--target` | `default` entry in `targets` |
| `--config` | `kagiroi.migration.json` in the working directory |

```sh
kagiroi migration apply --dry-run --target staging --config deploy/migrations.json
kagiroi migration apply --target staging --config deploy/migrations.json
```

The configuration must define the selected target. Both options also apply to `repair`.

## Repair database history

Inspect and correct the database first. `repair` removes incomplete history records and can align checksums; it does not roll back SQL.

```sh
kagiroi migration repair --dry-run
kagiroi migration repair
```

Review checksum changes before accepting them. Even the preview acquires a lock and may create the history table.

### Clear a stale reserved-row lock

SQLite / D1 only. Confirm no migration is running; select the affected target:

```sh
kagiroi migration repair --clear-lock --target d1
```

Cannot combine with `--dry-run`. For Hyperdrive, use the [library API](#hyperdrive).

## Recover generated JSON metadata

Latest file only; SQL is preserved. Restore older files from version control.

```sh
./gradlew :app:kagiroiRegenerateMigrationMetadata \
  -PkagiroiMigrationFile=20261101110000_add_email.sql
```

This repairs file metadata, not database history. Handwritten SQL needs no metadata recovery.

## Hyperdrive

Worker `jsMain` dependency, after publication:

```kotlin
implementation("dev.quwac.kagiroi:migration-core:0.1.0")
```

| Database | `MigrationHistoryDialect` |
| --- | --- |
| PostgreSQL | `PostgreSql` |
| MySQL / MariaDB | `MySql` |

Supply a Kagiroi Hyperdrive `database`, migration `artifacts`, and a fresh lock owner ID (1–64 characters):

```kotlin
val migrationDatabase = HyperdriveMigrationDatabase(
    database = database,
    dialect = MigrationHistoryDialect.PostgreSql,
    newOwner = { createUniqueLockOwner() },
)
try {
    val executor = MigrationExecutor(migrationDatabase)
    executor.dryRun(artifacts)
    executor.apply(artifacts)
} finally {
    migrationDatabase.close()
}
```

Disable Hyperdrive query caching and dedicate the wrapped database instance to migrations.
Hyperdrive and direct CLI connections use different locks; do not run them concurrently against the same DB.
For a stale lock, confirm inactivity before calling `MigrationExecutor(migrationDatabase).clearMigrationLock()`.
Example Worker: [local contract fixture](../integration-tests/hyperdrive/README.md).

## Common failures

| Symptom | Action |
| --- | --- |
| `No schema changes.` | No generated file needed; write data changes as plain SQL |
| No executable SQL | Add SQL; comments alone do not count |
| Replayed schema mismatch | Check Kotlin definitions and generated metadata |
| Applied-step hash mismatch | Compare the applied file and DB; restore unintended edits before repair |
| Step still `executing` | Inspect and undo incomplete DB changes, then preview repair |
| Damaged generated metadata | Restore from version control; regenerate only the latest file |

Reference: [README](../README.md). Tooling: [Contributing](../CONTRIBUTING.md).
