import assert from "node:assert/strict";
import { readdir, readFile, rm } from "node:fs/promises";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const directory = dirname(fileURLToPath(import.meta.url));
const source = process.argv[2];
assert.ok(source, "Pass the Kagiroi source checkout as the first argument");
const projects = ["postgres", "mysql", "mariadb", "sqlite", "sqlserver", "oracle"];
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
  assert.equal(result.status, 0, "Declared type export contract failed");
  return result.stdout;
}
run(projects.map((project) => `:${project}:kagiroiGenerateMigration`));
let declarations = 0;
for (const project of projects) {
  const schema = JSON.parse(
    await readFile(join(directory, project, "build/kagiroi/migration/schema.json"), "utf8"),
  );
  assert.equal(schema.formatVersion, 1);
  const files = await readdir(join(directory, project, "build/contract-migrations"));
  assert.equal(files.length, 1);
  const sql = await readFile(
    join(directory, project, "build/contract-migrations", files[0]),
    "utf8",
  );
  const columns = schema.schema.tables.flatMap((table) => table.columns);
  assert.ok(columns.length > 0);
  for (const column of columns) {
    assert.ok(column.type.id.endsWith(".declared"), column.type.id);
    const declaration = column.type.arguments.find(
      (argument) => argument.name === "declaration",
    )?.value;
    assert.ok(declaration, `${project}: missing physical declaration`);
    const rendered = /^(ENUM|SET)\(/.test(declaration)
      ? declaration.replaceAll("','", "', '")
      : declaration;
    assert.ok(sql.includes(` ${rendered} NOT NULL`), `${project}: ${column.name}: ${declaration}`);
    declarations++;
  }
}
run(projects.map((project) => `:${project}:kagiroiVerifyMigration`));
const output = run(projects.map((project) => `:${project}:kagiroiGenerateMigration`));
assert.equal((output.match(/No schema changes\./g) || []).length, projects.length);
console.log(
  `Native Table DSL -> export -> migration -> artifact replay passed: ${declarations} declarations in six dialects`,
);
