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
Gradle configuration in `app/build.gradle.kts`:

```kotlin
kagiroi {
    schema.set("example.AppSchema")
    dialect.set("postgresql")
    migrationsDirectory.set(rootProject.layout.projectDirectory.dir("migrations"))
}
```

`example.AppSchema` is a registered schema, such as `val AppSchema = schema(Users, Orders)`.
For an unreleased plugin or CLI, see [source checkout setup](../CONTRIBUTING.md#use-a-source-checkout).

## Connect to a database

Set connection credentials through environment variables. Pass the provider and SQL directory to the CLI:

```sh
kagiroi migration apply --provider postgresql --dir migrations --dry-run
```

| Option | Value |
| --- | --- |
| `--provider` | Required; see below |
| `--dir` | SQL directory; defaults to `src/main/resources/kagiroi/migrations` |

Use the same directory as Gradle's `migrationsDirectory`. Gradle reads `build.gradle.kts`; CLI options apply to both `apply` and `repair`.

### CLI providers

| Database | `--provider` | Gradle `dialect` | Environment variables |
| --- | --- | --- | --- |
| PostgreSQL | `postgresql` | `postgresql` | `DATABASE_URL` (connection string) |
| MySQL | `mysql` | `mysql` | `DATABASE_URL` (connection string) |
| MariaDB | `mariadb` | `mariadb` | `DATABASE_URL` (connection string) |
| SQL Server | `sqlserver` | `sqlserver` | `DATABASE_URL` (connection string) |
| SQLite | `sqlite` | `sqlite` | `DATABASE_URL` (file path) |
| PGlite | `pglite` | `postgresql` | `DATABASE_URL` (directory path) |
| Oracle | `oracle` | `oracle` | `DATABASE_URL` (connect string), `ORACLE_USER`, `ORACLE_PASSWORD` |
| D1 | `d1` | `d1` | `CLOUDFLARE_API_TOKEN` |

D1 also requires `--account-id <id>` and `--database-id <id>`.

## Generate schema changes

Edit the Kotlin table definitions, then run:

```sh
./gradlew :app:kagiroiGenerateMigration -PkagiroiMigrationName=add_email
./gradlew :app:kagiroiVerifyMigration
```

Names use lower snake case. No schema change means no new file. Keep generated files unchanged.

RLS policy changes for PostgreSQL/PGlite, SQL Server, and Oracle are included in generated migration SQL.

```sh
./gradlew :app:kagiroiExportSchema
```

Export output: `app/build/kagiroi/migration/schema.json`.

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
kagiroi migration apply --provider postgresql --dir migrations --dry-run
kagiroi migration apply --provider postgresql --dir migrations
```

| Command | Checks / effects |
| --- | --- |
| `kagiroiVerifyMigration` | Files against Kotlin schema; no DB connection |
| `migration apply --dry-run` | Files against DB history; no migration SQL or history changes |
| `migration apply` | Acquires a lock, applies pending steps, records history |

Hash mismatches and incomplete steps block application. These commands do not compare the live DB schema with Kotlin.

## Repair database history

Inspect and correct the database first. `repair` removes incomplete history records and can align checksums; it does not roll back SQL.

```sh
kagiroi migration repair --provider postgresql --dir migrations --dry-run
kagiroi migration repair --provider postgresql --dir migrations
```

Review checksum changes before accepting them. Even the preview acquires a lock and may create the history table.

### Clear a stale reserved-row lock

SQLite / D1 only. Confirm no migration is running and set the affected database connection:

```sh
kagiroi migration repair --provider sqlite --clear-lock
```

Cannot combine with `--dry-run`.

## Common failures

| Symptom | Action |
| --- | --- |
| `No schema changes.` | No generated file needed; write data changes as plain SQL |
| No executable SQL | Add SQL; comments alone do not count |
| Replayed schema mismatch | Check Kotlin definitions and generated metadata |
| Applied-step hash mismatch | Compare the applied file and DB; restore unintended edits before repair |
| Step still `executing` | Inspect and undo incomplete DB changes, then preview repair |
| Damaged generated metadata | Restore from version control; delete and regenerate only if unapplied |

Reference: [README](../README.md). Tooling: [Contributing](../CONTRIBUTING.md).
