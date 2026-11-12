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

function configuration(provider, connectionString, extra = {}) {
  writeFileSync(
    path.join(directory, "kagiroi.migration.json"),
    JSON.stringify({
      migrationsDirectory: "migrations",
      targets: { default: { provider, connectionStringEnv: "TEST_DATABASE_URL", ...extra } },
    }),
  );
  return { extraEnv: { TEST_DATABASE_URL: connectionString } };
}

try {
  assert.equal(run(["--version"]).trim(), `kagiroi ${version}`);
  assert.match(run(["--help"]), /Usage: kagiroi migration apply/);
  run(["migration", "apply", "--unknown"], { expectedStatus: 1 });
  for (const provider of ["sqlite", "pglite"]) {
    const options = configuration(provider, path.join(directory, `${provider}.db`));
    writeFileSync(
      path.join(migrations, "20261112090000_create_sample.sql"),
      "BEGIN;\nCREATE TABLE distribution_sample (id INTEGER PRIMARY KEY);\nINSERT INTO distribution_sample VALUES (1);\nCOMMIT;\n",
    );
    assert.match(run(["migration", "apply", "--dry-run"], options), /pending/);
    assert.match(run(["migration", "apply"], options), /pending/);
    assert.equal(run(["migration", "apply"], options).trim(), "");
    assert.equal(run(["migration", "apply", "--dry-run"], options).trim(), "");
    // A local SQL edit must still be rejected by the packaged CLI.
    writeFileSync(
      path.join(migrations, "20261112090000_create_sample.sql"),
      "BEGIN;\nCREATE TABLE distribution_sample (id INTEGER PRIMARY KEY);\nINSERT INTO distribution_sample VALUES (2);\nCOMMIT;\n",
    );
    assert.match(
      run(["migration", "apply"], { ...options, expectedStatus: 1 }),
      /hash|checksum|SHA-256/i,
    );
    console.log(`Packaged ${provider} apply, replay and integrity checks passed`);
  }

  // Exercise the remaining providers when disposable test databases are supplied.
  for (const [provider, variable] of [
    ["postgresql", "KAGIROI_TEST_POSTGRES_URL"],
    ["mysql", "KAGIROI_TEST_MYSQL_URL"],
    ["mariadb", "KAGIROI_TEST_MARIADB_URL"],
    ["sqlserver", "KAGIROI_TEST_SQLSERVER_URL"],
  ]) {
    if (!process.env[variable]) continue;
    const options = configuration(provider, process.env[variable]);
    writeFileSync(
      path.join(migrations, "20261112090000_create_sample.sql"),
      "CREATE TABLE distribution_sample (id INTEGER PRIMARY KEY);\n",
    );
    run(["migration", "apply", "--dry-run"], options);
    run(["migration", "apply"], options);
    assert.equal(run(["migration", "apply"], options).trim(), "");
    console.log(`Packaged ${provider} contract passed`);
  }
  if (process.env.KAGIROI_TEST_ORACLE_CONNECT_STRING) {
    writeFileSync(
      path.join(directory, "kagiroi.migration.json"),
      JSON.stringify({
        migrationsDirectory: "migrations",
        targets: {
          default: {
            provider: "oracle",
            providerSpecific: {
              connectString: process.env.KAGIROI_TEST_ORACLE_CONNECT_STRING,
              userEnv: "KAGIROI_TEST_ORACLE_USER",
              passwordEnv: "KAGIROI_TEST_ORACLE_PASSWORD",
            },
          },
        },
      }),
    );
    writeFileSync(
      path.join(migrations, "20261112090000_create_sample.sql"),
      "CREATE TABLE distribution_sample (id NUMBER PRIMARY KEY)\n",
    );
    run(["migration", "apply", "--dry-run"]);
    run(["migration", "apply"]);
    assert.equal(run(["migration", "apply"]).trim(), "");
    console.log("Packaged oracle contract passed");
  }
  // D1 configuration must validate its token before attempting the remote API.
  writeFileSync(
    path.join(directory, "kagiroi.migration.json"),
    JSON.stringify({
      migrationsDirectory: "migrations",
      targets: {
        default: {
          provider: "d1",
          providerSpecific: {
            accountId: "synthetic",
            databaseId: "synthetic",
            apiTokenEnv: "MISSING_TEST_TOKEN",
          },
        },
      },
    }),
  );
  assert.match(run(["migration", "apply"], { expectedStatus: 1 }), /MISSING_TEST_TOKEN/);
  console.log("Packaged CLI works without Node.js in PATH");
} finally {
  // Windows keeps native DLLs mapped until each child has exited, which it has here.
  rmSync(directory, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}
