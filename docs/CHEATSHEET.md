# Kagiroi Migration cheat sheet

Run from the application root. Examples use `:app` for the schema project and `migrations/` for SQL files.

## CLI installation options

Install the latest published CLI release.

macOS / Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/quwac/kagiroi-migration/main/install.sh | sh
```

Windows (PowerShell):

```powershell
irm https://raw.githubusercontent.com/quwac/kagiroi-migration/main/install.ps1 | iex
```

Check the installation with `kagiroi --version`.

To select a version and directory, use these options.

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

For an unreleased plugin or CLI, use the [source checkout setup](../CONTRIBUTING.md#use-a-source-checkout).
After publication to the Gradle Plugin Portal, select the released plugin version:

```kotlin
plugins {
    id("dev.quwac.kagiroi.migration") version "0.1.0"
}
```

Apply the plugin to the schema project. Example `app/build.gradle.kts`:

```kotlin
plugins {
    kotlin("multiplatform")
    id("dev.quwac.kagiroi.migration")
}

kotlin {
    js { nodejs() }
    sourceSets {
        jsMain.dependencies {
            implementation("dev.quwac.kagiroi:kagiroi-schema:0.2.0")
        }
    }
}

kagiroi {
    schema.set("example.AppSchema")
    dialect.set("postgresql")
    migrationsDirectory.set(rootProject.layout.projectDirectory.dir("migrations"))
}
```

`example.AppSchema` is a registered schema, such as `val AppSchema = schema(Users, Orders)`.

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
./gradlew :app:build
```

`build` and `assemble` automatically generate and verify migrations.
Generated names contain only a 14-digit UTC timestamp and `.sql`.
No schema change means no new file. Keep generated files unchanged.

```sh
./gradlew :app:kagiroiExportSchema
```

Export output: `app/build/kagiroi/migration/schema.json`.

### Explicit physical column types

Use `column(name, type)` with your dialect's type factory. Physical declarations are
preserved in table creation, column additions, type changes and casts.

PostgreSQL example:

```kotlin
import dev.quwac.kagiroi.postgres.PostgresTypes
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.primaryKey
import dev.quwac.kagiroi.schema.schema

object Documents : Table("documents") {
    val id = column("id", PostgresTypes.uuid).primaryKey()
    val payload = column("payload", PostgresTypes.jsonb) // SQL: JSONB NOT NULL
    val history = column("history", PostgresTypes.array(PostgresTypes.jsonb)) // SQL: JSONB[] NOT NULL
    val amount = column("amount", PostgresTypes.numeric(20, 5)) // SQL: NUMERIC(20,5) NOT NULL
}

val AppSchema = schema(Documents)
```

| Dialect / import | Physical type families | Factory examples | Generated SQL types |
| --- | --- | --- | --- |
| PostgreSQL/PGlite: `dev.quwac.kagiroi.postgres.PostgresTypes` | Scalars, serials, JSON/JSONB/JSONPATH, network, geometric, text search, catalog, range and multirange types; parameterized character, bit, numeric, time, timestamp and interval types; quoted named types and arrays | `json`, `jsonb`, `jsonPath`, `array(PostgresTypes.uuid)`, `timestampWithTimeZone(6)`, `numeric(20, -2)` | `JSON`, `JSONB`, `JSONPATH`, `UUID[]`, `TIMESTAMP(6) WITH TIME ZONE`, `NUMERIC(20,-2)` |
| MySQL: `dev.quwac.kagiroi.mysql.MySqlTypes` | Signed/unsigned integers, serial, floating point, decimal, character, binary, text/blob, JSON, date/time, spatial, ENUM and SET types | `json`, `integerUnsigned`, `decimal(65, 30)`, `dateTime(6)`, `enum("open", "closed")` | `JSON`, `INT UNSIGNED`, `DECIMAL(65,30)`, `DATETIME(6)`, `ENUM('open', 'closed')` |
| MariaDB: `dev.quwac.kagiroi.mariadb.MariaDbTypes` | Compatible MySQL types plus UUID, JSON, INET4/INET6, XMLTYPE, VECTOR and MariaDB decimal precision | `json`, `uuid`, `inet6`, `vector(1536)`, `decimal(65, 38)` | `JSON`, `UUID`, `INET6`, `VECTOR(1536)`, `DECIMAL(65,38)` |
| SQLite/D1: `dev.quwac.kagiroi.sqlite.SqliteTypes` | INTEGER, REAL, TEXT, BLOB and quoted named types with optional numeric parameters; also supported on D1 | `integer`, `real`, `text`, `blob`, `namedText("DECIMAL", 20, 5)` | `INTEGER`, `REAL`, `TEXT`, `BLOB`, `"DECIMAL"(20,5)` |
| SQL Server: `dev.quwac.kagiroi.sqlserver.SqlServerTypes` | Scalars, Unicode/non-Unicode strings, binary, UUID, date/time, decimal and numeric types | `nVarchar(4000)`, `varBinary(8000)`, `dateTimeOffset(7)`, `numeric(20, 5)` | `NVARCHAR(4000)`, `VARBINARY(8000)`, `DATETIMEOFFSET(7)`, `NUMERIC(20,5)` |
| Oracle: `dev.quwac.kagiroi.oracle.OracleTypes` | Boolean, number, binary float/double, character, LOB, RAW, date and timestamp types | `varchar2(4000)`, `raw(16)`, `number(20, -2)`, `timestampWithLocalTimeZone` | `VARCHAR2(4000 CHAR)`, `RAW(16)`, `NUMBER(20,-2)`, `TIMESTAMP(9) WITH LOCAL TIME ZONE` |

Use the factory for the target dialect. Compatible `MySqlTypes` declarations also work on MariaDB;
MySQL's `json` is excluded, so use `MariaDbTypes.json` there. When using MariaDB factory members
that return MySQL types, include both `kagiroi-mariadb` and `kagiroi-mysql` dependencies.

PostgreSQL arrays support validated built-in, parameterized and quoted named element types,
including nested arrays. Serial pseudo-types cannot be array elements.
`PostgresTypes.namedText("schema", "type")` references an existing named type; it does not create it.
SQLite named types preserve the declared name and parameters without creating a separate type.
Unknown unquoted declarations, invalid parameters, malformed descriptors and incompatible dialects
are rejected. Database version and extension requirements still apply.

## Write data changes directly

Create `migrations/YYYYMMDDHHMMSS.sql`, using 14 UTC digits after existing files.
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

| Command | Scope |
| --- | --- |
| `kagiroiVerifyMigration` | Checks filenames, metadata, dialects, script boundaries, and replayed schema deltas against the current Kotlin schema; does not connect to the database |
| `migration apply --dry-run` | Parses files and checks DB history for hash mismatches, gaps, and incomplete steps; executes no migration SQL and changes no history |
| `migration apply` | Runs preflight under a migration lock, then executes pending steps and records history |

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
