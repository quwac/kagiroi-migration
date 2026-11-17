# Declared type exporter contract

This standalone composite build compiles real Kagiroi `Table` definitions using
the PostgreSQL, MySQL, MariaDB, SQLite, SQL Server and Oracle type factories.
It exports canonical format version 1, generates SQL for every declared column,
verifies artifact replay, and checks that repeated generation reports no changes.

Run from the Kagiroi Migration repository root with a matching Kagiroi checkout:

```sh
node integration-tests/declared-types-export/test-export.mjs /absolute/path/to/kagiroi
```

The contract clears only the fixture projects' `build/contract-migrations`
directories. It does not connect to a database. Common tests cover D1's shared
SQLite declarations, rejected declarations, dialect compatibility, and casts.
