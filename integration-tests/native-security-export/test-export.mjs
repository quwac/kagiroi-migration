import assert from "node:assert/strict";
import { readdir, readFile, rm } from "node:fs/promises";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const directory = dirname(fileURLToPath(import.meta.url));
const source = process.argv[2];
assert.ok(source, "Pass the Kagiroi source checkout as the first argument");
const projects = ["postgres", "sqlserver", "oracle"];
for (const project of projects) {
  await rm(join(directory, project, "build/contract-migrations"), { recursive: true, force: true });
}
function run(tasks) {
  const result = spawnSync(
    resolve(directory, "../../gradlew"),
    ["-p", directory, `-PkagiroiSource=${resolve(source)}`, ...tasks, "--console=plain"],
    { encoding: "utf8", timeout: 600000 },
  );
  process.stdout.write(result.stdout || "");
  process.stderr.write(result.stderr || "");
  if (result.error) throw result.error;
  assert.equal(result.status, 0, "Native security export contract failed");
  return result.stdout;
}
run(projects.map((project) => `:${project}:kagiroiGenerateMigration`));
const expected = {
  postgres: "ENABLE ROW LEVEL SECURITY",
  sqlserver: "CREATE SECURITY POLICY",
  oracle: "DBMS_RLS.ADD_POLICY",
};
for (const project of projects) {
  const schema = JSON.parse(
    await readFile(join(directory, project, "build/kagiroi/migration/schema.json"), "utf8"),
  );
  assert.equal(schema.formatVersion, 2);
  assert.ok(schema.schema.securityObjects.length > 0);
  const files = await readdir(join(directory, project, "build/contract-migrations"));
  assert.equal(files.length, 1);
  const sql = await readFile(
    join(directory, project, "build/contract-migrations", files[0]),
    "utf8",
  );
  assert.ok(sql.includes(expected[project]));
  if (project === "sqlserver") assert.equal((sql.match(/CREATE SECURITY POLICY/g) || []).length, 1);
}
run(projects.map((project) => `:${project}:kagiroiVerifyMigration`));
const output = run(projects.map((project) => `:${project}:kagiroiGenerateMigration`));
assert.equal((output.match(/No schema changes\./g) || []).length, projects.length);
console.log(
  "Native Table DSL -> export -> migration -> artifact replay passed for PostgreSQL, SQL Server, and Oracle",
);
