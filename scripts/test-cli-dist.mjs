import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

const executable = path.resolve(process.argv[2] || "");
const version = process.argv[3] || "0.1.0";
assert.ok(process.argv[2], "Usage: node scripts/test-cli-dist.mjs <executable> [version]");
const directory = mkdtempSync(path.join(os.tmpdir(), "kagiroi-distribution-"));
const migrations = path.join(directory, "migrations");
mkdirSync(migrations);
const env = {
  ...process.env,
  // The installed executable must work without a system Node or npm.
  PATH: path.join(directory, "empty-path"),
  KAGIROI_CACHE_DIR: path.join(directory, "cache"),
};
for (const name of ["DATABASE_URL", "ORACLE_USER", "ORACLE_PASSWORD", "CLOUDFLARE_API_TOKEN"]) {
  delete env[name];
}

function run(args, { expectedStatus = 0, extraEnv = {} } = {}) {
  const result = spawnSync(executable, args, {
    cwd: directory,
    env: { ...env, ...extraEnv },
    encoding: "utf8",
    timeout: 120_000,
  });
  assert.equal(result.error, undefined);
  assert.equal(
    result.status,
    expectedStatus,
    `${args.join(" ")}\n${result.stdout}\n${result.stderr}`,
  );
  return `${result.stdout}${result.stderr}`;
}

function migration(provider, action, args = [], options = {}) {
  return run(
    ["migration", action, "--provider", provider, "--dir", "migrations", ...args],
    options,
  );
}

try {
  assert.equal(run(["--version"]).trim(), `kagiroi ${version}`);
  assert.match(run(["--help"]), /Usage: kagiroi migration apply/);
  run(["migration", "apply", "--unknown"], { expectedStatus: 1 });
  run(["migration", "apply"], { expectedStatus: 1 });
  for (const option of ["--config", "--target"]) {
    assert.match(
      migration("sqlite", "apply", [option, "synthetic"], { expectedStatus: 1 }),
      /Unsupported migration option/,
    );
  }
  assert.match(
    migration("unsupported", "apply", [], { expectedStatus: 1 }),
    /No migration connector/,
  );
  for (const provider of ["postgresql", "mysql", "mariadb", "sqlserver", "sqlite", "pglite"]) {
    assert.match(
      migration(provider, "apply", ["--dry-run"], { expectedStatus: 1 }),
      /environment variable is not set: DATABASE_URL/,
    );
  }
  for (const provider of ["sqlite", "pglite"]) {
    const options = { extraEnv: { DATABASE_URL: path.join(directory, `${provider}.db`) } };
    writeFileSync(
      path.join(migrations, "20261112090000_create_sample.sql"),
      "BEGIN;\nCREATE TABLE distribution_sample (id INTEGER PRIMARY KEY);\nINSERT INTO distribution_sample VALUES (1);\nCOMMIT;\n",
    );
    assert.match(migration(provider, "apply", ["--dry-run"], options), /pending/);
    assert.match(migration(provider, "apply", [], options), /pending/);
    assert.equal(migration(provider, "apply", [], options).trim(), "");
    assert.equal(migration(provider, "apply", ["--dry-run"], options).trim(), "");
    // A local SQL edit must still be rejected by the packaged CLI.
    writeFileSync(
      path.join(migrations, "20261112090000_create_sample.sql"),
      "BEGIN;\nCREATE TABLE distribution_sample (id INTEGER PRIMARY KEY);\nINSERT INTO distribution_sample VALUES (2);\nCOMMIT;\n",
    );
    assert.match(
      migration(provider, "apply", [], { ...options, expectedStatus: 1 }),
      /hash|checksum|SHA-256/i,
    );
    assert.match(migration(provider, "repair", ["--dry-run"], options), /would repair/);
    assert.match(migration(provider, "repair", [], options), /repaired/);
    assert.equal(migration(provider, "apply", [], options).trim(), "");
    console.log(`Packaged ${provider} apply, replay, integrity and repair checks passed`);
  }

  // Exercise the remaining providers when disposable test databases are supplied.
  for (const [provider, variable] of [
    ["postgresql", "KAGIROI_TEST_POSTGRES_URL"],
    ["mysql", "KAGIROI_TEST_MYSQL_URL"],
    ["mariadb", "KAGIROI_TEST_MARIADB_URL"],
    ["sqlserver", "KAGIROI_TEST_SQLSERVER_URL"],
  ]) {
    if (!process.env[variable]) continue;
    const options = { extraEnv: { DATABASE_URL: process.env[variable] } };
    writeFileSync(
      path.join(migrations, "20261112090000_create_sample.sql"),
      "CREATE TABLE distribution_sample (id INTEGER PRIMARY KEY);\n",
    );
    migration(provider, "apply", ["--dry-run"], options);
    migration(provider, "apply", [], options);
    assert.equal(migration(provider, "apply", [], options).trim(), "");
    console.log(`Packaged ${provider} contract passed`);
  }
  assert.match(
    migration("oracle", "apply", ["--dry-run"], { expectedStatus: 1 }),
    /environment variable is not set: DATABASE_URL/,
  );
  assert.match(
    migration("oracle", "apply", ["--dry-run"], {
      expectedStatus: 1,
      extraEnv: { DATABASE_URL: "localhost:1521/FREEPDB1" },
    }),
    /environment variable is not set: ORACLE_USER/,
  );
  assert.match(
    migration("oracle", "apply", ["--dry-run"], {
      expectedStatus: 1,
      extraEnv: { DATABASE_URL: "localhost:1521/FREEPDB1", ORACLE_USER: "synthetic" },
    }),
    /environment variable is not set: ORACLE_PASSWORD/,
  );
  if (process.env.KAGIROI_TEST_ORACLE_CONNECT_STRING) {
    writeFileSync(
      path.join(migrations, "20261112090000_create_sample.sql"),
      "CREATE TABLE distribution_sample (id NUMBER PRIMARY KEY)\n",
    );
    const options = {
      extraEnv: {
        DATABASE_URL: process.env.KAGIROI_TEST_ORACLE_CONNECT_STRING,
        ORACLE_USER: process.env.KAGIROI_TEST_ORACLE_USER,
        ORACLE_PASSWORD: process.env.KAGIROI_TEST_ORACLE_PASSWORD,
      },
    };
    migration("oracle", "apply", ["--dry-run"], options);
    migration("oracle", "apply", [], options);
    assert.equal(migration("oracle", "apply", [], options).trim(), "");
    console.log("Packaged oracle contract passed");
  }
  // D1 must validate its token before attempting the remote API.
  assert.match(
    migration("d1", "apply", ["--account-id", "synthetic", "--database-id", "synthetic"], {
      expectedStatus: 1,
    }),
    /environment variable is not set: CLOUDFLARE_API_TOKEN/,
  );
  console.log("Packaged CLI works without Node.js in PATH");
} finally {
  // Windows keeps native DLLs mapped until each child has exited, which it has here.
  rmSync(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}
