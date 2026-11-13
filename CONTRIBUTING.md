# Contributing to Kagiroi Migration

## Setup

Install the tools pinned in `mise.toml` and `mise.lock`, then register the Git hooks:

```sh
mise trust
mise install --locked java bun node hk rumdl pkl lychee gh zizmor betterleaks \
    github:github/gh-actions-lock github:jdx/jactionlint
mise run setup
```

`mise run setup` installs locked JavaScript quality tools and registers the hk
pre-commit hook.

To build against an unreleased Kagiroi checkout, set `KAGIROI_DIR` to its absolute
path. Gradle substitutes the Kagiroi dependencies with that included build:

```sh
KAGIROI_DIR=/absolute/path/to/kagiroi mise exec -- ./gradlew :migration-cli:jsNodeTest
```

When including Kagiroi Migration in another composite build, include the same
Kagiroi directory there with the build name `kagiroi` so Kotlin/JS can locate its
npm tasks. Without `KAGIROI_DIR`, the version in `gradle.properties` is used.

### Use a source checkout

For an unreleased migration plugin, set `KAGIROI_MIGRATION_DIR` to the checkout's
absolute path and add this to the application's `settings.gradle.kts`:

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

Build and run the CLI from source:

```sh
"$KAGIROI_MIGRATION_DIR/gradlew" -p "$KAGIROI_MIGRATION_DIR" \
  :migration-cli:jsDevelopmentExecutableCompileSync :kotlinNpmInstall
export CLI="$KAGIROI_MIGRATION_DIR/build/js/packages/kagiroi-migration-migration-cli/kotlin/kagiroi-migration-migration-cli.js"
node "$CLI" --help
```

When following the cheat sheet from a source build, replace `kagiroi` with `node "$CLI"`.

## Checks

Run commands from a mise-activated shell, or prefix them with `mise exec --`:

```sh
mise exec -- hk check --all
mise exec -- hk check --all --profile ci
mise exec -- hk fix --all
mise run markdown
```

The checks cover Kotlin with Detekt, JavaScript with Oxlint, JavaScript and JSON
formatting with Oxfmt, unused JavaScript dependencies with Knip, Markdown with
rumdl, links, secrets, and GitHub Actions. The `ci` profile also checks online
links and Actions dependencies. Online Actions verification requires GitHub
authentication (`gh auth login` or `GITHUB_TOKEN`).

The pre-commit hook checks staged files without automatically fixing or staging
them. It hides unstaged edits while checking and restores them afterward.
`hk fix --all` applies supported fixes to the working tree. Generated build
outputs, dependency directories, and Gradle caches are excluded from checks;
Oxfmt also excludes `package-lock.json` files.

CI runs `hk check --all --profile ci`, Kotlin tests, and packaged CLI database
contracts on every pull request.

## GitHub Actions dependencies

After changing workflow or action dependencies, regenerate and verify the lockfile:

```sh
mise exec -- gh-actions-lock --no-narrow
mise exec -- gh-actions-lock --verify
```

Workflow `uses` references are pinned to full commit SHAs with version comments.
Do not edit `.github/workflows/actions.lock` by hand.

## Dependency updates

Dependabot checks GitHub Actions, Gradle, and root Bun tooling weekly. Each update entry sets a three-day cooldown
with `cooldown.default-days: 3` in `.github/dependabot.yml`.
Jactionlint and zizmor also require a minimum cooldown of three days.

The release-age settings for mise and Bun dependency resolution are also three
days.

## Release artifacts

Build the standalone CLI for the current host using the pinned Java, Node.js,
and Bun tools:

```sh
mise run deps
mise exec -- node scripts/build-cli.mjs 0.1.0
mise exec -- node scripts/test-cli-dist.mjs \
  build/distributions/kagiroi-0.1.0-darwin-arm64/kagiroi 0.1.0
mise exec -- node --test scripts/test-installers.mjs
```

Use your host's distribution directory in the test command. Windows uses
`windows-x64/kagiroi.exe` as the suffix. Each host builds its own executable;
the artifact workflow runs on all five target hosts and tests SQLite and PGlite
from the packaged executable with Node.js absent from PATH. It also verifies
already-applied steps and checksum mismatch rejection. The shell installer tests
simulate all four Unix targets and corrupt downloads; PowerShell tests run on
the Windows host with user PATH updates disabled.
The Linux contract job also runs the packaged CLI against separate disposable
PostgreSQL and MySQL databases.

`build-cli.mjs` first runs the production Kotlin/JS compile and Gradle-owned npm
installation. It copies the runtime dependency closure, validates the shipped
SQLite native addon with the embedded Node.js runtime, and uses Node's single executable application
support plus Postject. Dependencies remain separate modules inside an embedded
compressed payload so native addons and PGlite's Wasm/data assets retain their
normal file layout. The output directory is `build/distributions`.
The extracted payload is cached by its SHA-256 and includes Node.js and dependency
licenses. See the cheat sheet for [cache settings](docs/CHEATSHEET.md#cli-cache)
and [installer options](docs/CHEATSHEET.md#cli-installation-options).

Additional packaged-driver contracts accept `KAGIROI_TEST_POSTGRES_URL`,
`KAGIROI_TEST_MYSQL_URL`, `KAGIROI_TEST_MARIADB_URL`, and
`KAGIROI_TEST_SQLSERVER_URL`. Oracle uses `KAGIROI_TEST_ORACLE_CONNECT_STRING`,
`KAGIROI_TEST_ORACLE_USER`, and `KAGIROI_TEST_ORACLE_PASSWORD`.
Use separate disposable databases: these contracts create and retain migration
history and a `distribution_sample` table. D1 coverage in the packaged smoke test
checks configuration and missing-token handling; it does not call the remote D1 API.

Stage the core publications and plugin implementation/marker locally without
uploading anything:

```sh
./gradlew :migration-core:publishAllPublicationsToStagingRepository \
  :migration-gradle-plugin:publishAllPublicationsToStagingRepository
mise exec -- node scripts/test-publications.mjs
```

The default snapshot publications are written to `build/staging-repository`.
Core publications include JVM, JS, Wasm, Kotlin metadata, sources, and Javadoc
archives. The plugin implementation depends on the core JVM variant; its plugin
marker uses `dev.quwac.kagiroi.migration`. Only `migration-core` is published to
Maven Central; the plugin is published to the Gradle Plugin Portal.
The publication contract consumes these files in an isolated build through their
published coordinates, generates and verifies SQL through the plugin marker, and
compiles core consumers for JVM, JS, and Wasm with strict dependency verification.

An annotated `v<major>.<minor>.<patch>` tag on a commit contained in `main` starts
the Publish workflow. It runs Verify, including all five CLI hosts, then publishes
the signed core to Central. It waits for the plugin's core dependency to become
publicly downloadable before publishing the plugin, and creates the GitHub Release
with CLI downloads after those jobs succeed. CLI release versions are supplied
to the packaging script; npm workspace versions remain at their normal snapshot
version so release builds do not require lockfile changes.

Configure the following GitHub repository secrets before pushing a release tag:

| Secret | Use |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | Central Portal publishing token |
| `SIGNING_KEY`, `SIGNING_PASSWORD` | Armored signing key and its passphrase |
| `GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal publishing credentials |

The workflow supplies these through environment variables. For a manual upload,
the Gradle tasks are `:migration-core:publishAndReleaseToMavenCentral` and
`:migration-gradle-plugin:publishPlugins`, with `-Pversion=<release-version>`.
The Central Gradle properties are `mavenCentralUsername`, `mavenCentralPassword`,
`signingInMemoryKey`, and `signingInMemoryKeyPassword`; supply them through
`ORG_GRADLE_PROJECT_<property>`. The plugin accepts `GRADLE_PUBLISH_KEY` and
`GRADLE_PUBLISH_SECRET`. A remote publishing result is separate from local staging;
check actual public artifacts and the Plugin Portal entry before announcing availability.

## PostgreSQL catalog contracts

The PostgreSQL unnamed UNIQUE removal contract runs against a disposable container:

```sh
docker run --rm -d --name kagiroi-unique-contract -e POSTGRES_PASSWORD=local-test postgres:17-alpine
docker exec kagiroi-unique-contract pg_isready -U postgres
KAGIROI_TEST_POSTGRES_CONTAINER=kagiroi-unique-contract ./gradlew :migration-core:jvmTest --rerun-tasks
docker stop kagiroi-unique-contract
```

Wait for `pg_isready` to succeed before running Gradle. The opt-in JVM test checks
arbitrary constraint names, composite keys, quoted identifiers, preserved rows,
and rejection of missing or ambiguous matches. Without the environment variable,
the native contract does not execute SQL; the common tests still run on all targets.
The comment contracts apply generated DDL and query the stored comments. PostgreSQL also
checks that comment edits preserve UNIQUE constraint and index identities.

Additional opt-in JVM comment contracts use disposable containers selected by
`KAGIROI_TEST_MYSQL_CONTAINER`, `KAGIROI_TEST_MARIADB_CONTAINER`,
`KAGIROI_TEST_SQLSERVER_CONTAINER`, and `KAGIROI_TEST_ORACLE_CONTAINER`.
MySQL/MariaDB use root with password `kagiroi-test`; SQL Server uses `sa` with
password `Kagiroi_Test_12345!`; Oracle uses `system` with password `Kagiroi_Test_12345`
at `localhost/FREEPDB1`. These are synthetic credentials for disposable test containers.
The tests invoke the database clients inside each container through `docker exec`, create
isolated namespaces, and remove them afterward. MySQL/MariaDB check column attributes and
rows; SQL Server checks extended properties, object identities, and rows; Oracle checks
table/column comment creation, updates, and removal. Without the corresponding environment
variable, each native contract returns without executing SQL.

## Native security contract

The core tests cover projection versions, deterministic security hashes,
security-only diffs, DDL ordering, unsupported dialects, artifact generation,
and delta replay on JVM, JS, and Wasm. Run them with:

```sh
./gradlew :migration-core:jvmTest :migration-core:jsNodeTest :migration-core:wasmJsNodeTest
```

For the real `Table.init` to Gradle plugin export path, use the standalone
[native security exporter contract](integration-tests/native-security-export/README.md).
It takes an explicit Kagiroi source checkout and uses composite-build dependency
substitution, without publishing local artifacts or changing dependency versions.
