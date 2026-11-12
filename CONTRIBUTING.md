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
pre-commit hook. Hyperdrive integration dependencies are installed separately
with `npm ci --prefix integration-tests/hyperdrive`.

To build against an unreleased Kagiroi checkout, set `KAGIROI_DIR` to its absolute
path. Gradle substitutes the Kagiroi dependencies with that included build:

```sh
KAGIROI_DIR=/absolute/path/to/kagiroi mise exec -- ./gradlew :migration-cli:jsNodeTest
```

When including Kagiroi Migration in another composite build, include the same
Kagiroi directory there with the build name `kagiroi` so Kotlin/JS can locate its
npm tasks. Without `KAGIROI_DIR`, the version in `gradle.properties` is used.

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

CI runs `hk check --all --profile ci`, Kotlin tests, and the local Hyperdrive
Worker contracts on every pull request.

## GitHub Actions dependencies

After changing workflow or action dependencies, regenerate and verify the lockfile:

```sh
mise exec -- gh-actions-lock --no-narrow
mise exec -- gh-actions-lock --verify
```

Workflow `uses` references are pinned to full commit SHAs with version comments.
Do not edit `.github/workflows/actions.lock` by hand.

## Dependency updates

Dependabot checks GitHub Actions, Gradle, root Bun tooling, and the Hyperdrive
fixture's npm dependencies weekly. Each update entry sets a three-day cooldown
with `cooldown.default-days: 3` in `.github/dependabot.yml`.
Jactionlint and zizmor also require a minimum cooldown of three days.

The release-age settings for mise and Bun dependency resolution are also three
days.

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
