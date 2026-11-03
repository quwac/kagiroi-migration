# Kagiroi Migration

Kagiroi Migration generates SQL migrations from [Kagiroi](https://github.com/quwac/kagiroi) table definitions. It verifies migration files and applies them to a database in execution steps.

The Kotlin table definitions describe the desired schema. Previous schema state is reconstructed from deltas in the migration files; generation does not connect to a database or use Git history.

## Workflow

1. Change the Kagiroi table definitions.
2. Run `kagiroiGenerateMigration`.
3. Review and edit the generated SQL.
4. Run `kagiroiFinalizeMigration` and `kagiroiVerifyMigration`.
5. Commit the migration file with the table changes.
6. Run `migration apply --dry-run`, review the pending steps, then run `migration apply`.

The project contains `migration-core`, `migration-gradle-plugin`, and `migration-cli`. The core contains the artifact, verification, and execution APIs. The Gradle plugin exports the Kotlin schema and manages migration files. The CLI applies files using the core execution API.

## Generate and verify migrations

Apply `dev.quwac.kagiroi.migration` to the Kotlin Multiplatform project that owns the schema. The project needs a Kotlin/JS target and a dependency on `dev.quwac.kagiroi:kagiroi-schema`.

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
    schema.set("com.example.AppSchema")
    dialect.set("postgresql")
    migrationsDirectory.set(layout.projectDirectory.dir("src/main/resources/kagiroi/migrations"))
}
```

`AppSchema` is a registered schema, such as `val AppSchema = schema(Users, Orders)`. The plugin generates an exporter in `build/generated/`, compiles it with Kotlin/JS, and runs it with Node. You do not need to write a `main()` function. The intermediate schema JSON is written under `build/`.

The plugin defaults to `postgresql` and `src/main/resources/kagiroi/migrations`. Run the tasks in the project where the plugin is applied:

```sh
./gradlew kagiroiGenerateMigration -PkagiroiMigrationName=add_email
./gradlew kagiroiFinalizeMigration
./gradlew kagiroiVerifyMigration
```

Generation starts from an empty schema when there are no migration files. If there are no schema changes, it prints `No schema changes.` and creates no file. Finalize or remove an existing draft before generating another migration.

The plugin is not yet published as a standalone release. Its plugin ID can be used when the plugin is made available to the build, for example through a Gradle composite build.

## Migration files

Each migration is an executable SQL file. Its filename stem is its ID, and filenames determine the execution order:

```text
20261101090000_init.sql
20261101114500_add_email.sql
```

Generated names use a UTC timestamp followed by a lower-case name. Each file starts with a JSON comment. A draft has an empty `steps` array:

```sql
/*
kagiroi:
{
  "formatVersion": 1,
  "dialect": "postgresql",
  "steps": []
}
*/
```

Finalization records each execution step's kind, SHA-256, and schema delta in the header. The format is described by [migration-artifact-v1.schema.json](migration-artifact-v1.schema.json). It does not store a full schema snapshot or a parent migration ID.

The generated SQL includes `-- kagiroi:operation=<index>:<sha256>` markers. Keep each marker with its generated operation when moving statements between transactions. Finalization rejects missing, repeated, or mismatched markers. A step containing only manually added DML has an empty schema delta.

You may edit SQL and transaction boundaries before finalization. Finalization parses script and transaction boundaries, but does not infer a schema from SQL. It records the table-definition diff as your assertion about the result of the SQL. Check the edited SQL before finalizing it. Objects outside Kagiroi's schema model are unmanaged.

Transactions belong to the SQL file: apply does not add an implicit wrapper. Generated PostgreSQL, SQLite, D1, and SQL Server DDL is wrapped in an explicit transaction; MySQL, MariaDB, and Oracle DDL is not. Unsupported operations fail generation, and SQLite/D1 table rebuilds are not generated automatically.

`kagiroiVerifyMigration` checks artifact metadata, filenames, dialect, script steps, hashes, markers, and schema delta replay against the current Kotlin schema. It does not connect to the database or verify the actual database schema. Step hashes use dialect-aware lexical tokens: whitespace and recognized keyword case are normalized, while identifiers, literal values, and comments are retained.

## Apply migrations

Run migrations with a separate database user that has the schema-change permissions required by the migration. Give the application database user only the permissions it needs to run the application. Do not run migrations at application startup.

The CLI is a Kotlin/JS Node executable. It is not currently packaged as an installed `kagiroi` command. From this repository, build it and set the module search path before running it:

```sh
./gradlew :migration-cli:jsDevelopmentExecutableCompileSync :kotlinNpmInstall
export NODE_PATH="$PWD/build/js/node_modules"
node migration-cli/build/compileSync/js/main/developmentExecutable/kotlin/kagiroi-migration-migration-cli.js --help
```

The following examples use `CLI` as a shell variable pointing to that generated JavaScript file:

```sh
CLI="$PWD/migration-cli/build/compileSync/js/main/developmentExecutable/kotlin/kagiroi-migration-migration-cli.js"
```

Create `kagiroi.migration.json` in the working directory, or pass `--config <path>`:

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

Set `DATABASE_URL` in the environment, then inspect and apply pending steps:

```sh
node "$CLI" migration apply --dry-run --target production
node "$CLI" migration apply --target production
```

The target defaults to `default` if `--target` is omitted. The CLI reads `.sql` files from `migrationsDirectory`. A dry run validates artifacts and compares them with database history without executing migration SQL or changing history. Apply acquires a database lock, reads history again, and executes pending steps in order.

| Provider | `provider` | Configuration |
| --- | --- | --- |
| PostgreSQL | `postgresql` | `connectionStringEnv` |
| MySQL | `mysql` | `connectionStringEnv` |
| MariaDB | `mariadb` | `connectionStringEnv` |
| SQL Server | `sqlserver` | `connectionStringEnv` |
| SQLite | `sqlite` | `connectionStringEnv`, containing a filesystem path |
| PGlite | `pglite` | `connectionStringEnv`, containing a filesystem path; uses PostgreSQL artifacts |
| Oracle | `oracle` | `providerSpecific` with `connectString`, `userEnv`, and `passwordEnv` |
| Cloudflare D1 | `d1` | `providerSpecific` with `accountId`, `databaseId`, and `apiTokenEnv` |

The bundled connectors reject `proxy` configuration. D1 uses the Cloudflare HTTP API and requires an API token with D1 access. D1 transactional steps are sent as batches.

### History and recovery

The `kagiroi_migrations` table stores each step's migration ID, index, hash, status, and application time. A transactional step writes its history inside the transaction. A non-transactional step is recorded as `executing` before SQL runs and `applied` afterward. An `executing` step is not retried automatically.

After inspecting the database, resolve an ambiguous step explicitly:

```sh
node "$CLI" migration resolve --target production \
  --migration 20261101114500_add_email --step 0 --mark-pending
```

Use `--mark-applied <sha256>` when the step was applied, or `--accept-sha256 <sha256>` to explicitly reconcile a reviewed history hash. SQLite and D1 use a reserved history row as a lock; after confirming that a lock is stale, remove it with `migration resolve --clear-lock --target production`.

## Cloudflare Workers and Hyperdrive

`migration-core` exposes the suspend `MigrationExecutor` API and a Kotlin/JS `HyperdriveMigrationDatabase` adapter. Use them in a dedicated migration Worker with its own Hyperdrive binding and database credentials, separate from the application Worker. The API itself does not enforce this separation.

The migration Worker supplies finalized `NamedMigrationArtifact` values, a Kagiroi Hyperdrive database, and a fresh unique lock owner for each invocation:

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

For MySQL or MariaDB, use `MigrationHistoryDialect.MySql`. Configure Hyperdrive with query caching disabled, and do not use the wrapped database concurrently. Hyperdrive uses a reserved history row for its lock. Direct PostgreSQL and MySQL connections use different locks, so do not run both paths against the same database concurrently. If a Hyperdrive lock becomes stale, inspect the database before calling `clearMigrationLock()`.

The [local Worker fixture](integration-tests/hyperdrive/README.md) exercises the Hyperdrive adapter against PostgreSQL or MySQL. It does not reproduce Cloudflare's remote pooling or caching.

## License

[MIT](LICENSE)
