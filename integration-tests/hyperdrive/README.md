# Hyperdrive Worker contract

This fixture runs migration apply through a local Wrangler Worker and its Hyperdrive binding.
Use a disposable PostgreSQL or MySQL database. The fixture creates migration history and a test table.

Build the fixture and supply the local database connection string to Wrangler:

```sh
npm ci --prefix integration-tests/hyperdrive
./gradlew :kotlinNpmInstall :integration-tests:hyperdrive:jsProductionExecutableCompileSync
export CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE="postgres://..."
export WRANGLER_BIN="./integration-tests/hyperdrive/node_modules/.bin/wrangler"
node integration-tests/hyperdrive/test-worker.mjs postgres
```

For MySQL, set the local connection string to the MySQL database and pass `mysql`. The optional second
argument selects the local HTTP port. `WRANGLER_BIN` selects a local Wrangler executable.

The local binding connects to the supplied database; it does not reproduce Cloudflare's remote pooling
or caching behavior. Use a cache-disabled Hyperdrive configuration for real migrations.
