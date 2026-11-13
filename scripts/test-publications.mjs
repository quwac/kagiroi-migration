import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const fixture = path.join(root, "build", "publication-consumer");
const repository = path.join(root, "build", "staging-repository");
const version = "0.1.0-SNAPSHOT";
const kagiroiVersion = readFileSync(path.join(root, "gradle.properties"), "utf8")
  .match(/^kagiroiVersion=(.+)$/m)?.[1]
  .trim();
assert.ok(kagiroiVersion);

function gradle(arguments_, cwd = root) {
  const command = process.platform === "win32" ? "cmd.exe" : path.join(root, "gradlew");
  const args =
    process.platform === "win32"
      ? ["/d", "/c", `"${path.join(root, "gradlew.bat")}" ${arguments_.join(" ")}`]
      : arguments_;
  const result = spawnSync(command, args, { cwd, stdio: "inherit" });
  if (result.error) throw result.error;
  assert.equal(result.status, 0, "Publication consumer Gradle check failed");
}

gradle([
  ":migration-core:publishAllPublicationsToStagingRepository",
  ":migration-gradle-plugin:publishAllPublicationsToStagingRepository",
  "--console=plain",
]);
rmSync(fixture, { recursive: true, force: true });
mkdirSync(path.join(fixture, "gradle"), { recursive: true });
writeFileSync(
  path.join(fixture, "package.json"),
  JSON.stringify({ private: true, type: "commonjs" }),
);
// Strict verification also covers the locally generated publications. External
// dependencies retain the repository's official-origin checksums.
let verification = readFileSync(path.join(root, "gradle/verification-metadata.xml"), "utf8");
const components = new Map();
// Group/artifact directory names can contain dots; use dirents for recursion.
function walk(directory, segments = []) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (entry.isDirectory()) walk(path.join(directory, entry.name), [...segments, entry.name]);
    else if (/\.(?:pom|module|jar|klib)$/.test(entry.name)) {
      const [artifact, artifactVersion] = segments.slice(-2);
      if (artifactVersion !== version) continue;
      const group = segments.slice(0, -2).join(".");
      const key = `${group}:${artifact}:${artifactVersion}`;
      if (!components.has(key)) components.set(key, []);
      const digest = createHash("sha256")
        .update(readFileSync(path.join(directory, entry.name)))
        .digest("hex");
      components
        .get(key)
        .push(
          `<artifact name="${entry.name}"><sha256 value="${digest}" origin="Local publication contract"/></artifact>`,
        );
    }
  }
}
walk(repository);
const localComponents = [...components]
  .map(([key, artifacts]) => {
    const [group, name, componentVersion] = key.split(":");
    return `<component group="${group}" name="${name}" version="${componentVersion}">${artifacts.join("")}</component>`;
  })
  .join("\n");
verification = verification.replace("</components>", `${localComponents}\n</components>`);
writeFileSync(path.join(fixture, "gradle/verification-metadata.xml"), verification);
cpSync(path.join(root, "gradle.properties"), path.join(fixture, "gradle.properties"));
writeFileSync(
  path.join(fixture, "settings.gradle.kts"),
  `
pluginManagement {
    repositories {
        maven { url = uri("${pathToFileURL(repository).href}") }
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("${pathToFileURL(repository).href}") }
        mavenCentral()
    }
}
rootProject.name = "publication-consumer"
`,
);
writeFileSync(
  path.join(fixture, "build.gradle.kts"),
  `
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
plugins {
    kotlin("multiplatform") version "2.4.21"
    id("dev.quwac.kagiroi.migration") version "${version}"
}
kotlin {
    jvm()
    js { nodejs() }
    wasmJs { nodejs() }
    sourceSets {
        commonMain.dependencies { implementation("dev.quwac.kagiroi:migration-core:${version}") }
        jsMain.dependencies { implementation("dev.quwac.kagiroi:kagiroi-schema:${kagiroiVersion}") }
    }
}
kagiroi {
    schema.set("example.AppSchema")
    dialect.set("postgresql")
    migrationsDirectory.set(layout.buildDirectory.dir("migrations"))
}
`,
);
mkdirSync(path.join(fixture, "src/commonMain/kotlin"), { recursive: true });
writeFileSync(
  path.join(fixture, "src/commonMain/kotlin/Consumer.kt"),
  `
import dev.quwac.kagiroi.migration.CanonicalPhysicalSchema
fun publishedSchema() = CanonicalPhysicalSchema()
`,
);
mkdirSync(path.join(fixture, "src/jsMain/kotlin"), { recursive: true });
writeFileSync(
  path.join(fixture, "src/jsMain/kotlin/Schema.kt"),
  `
package example
import dev.quwac.kagiroi.schema.Table
import dev.quwac.kagiroi.schema.schema
object Users : Table("users") { val name = string("name") }
val AppSchema = schema(Users)
`,
);
gradle(
  [
    "kagiroiGenerateMigration",
    "kagiroiVerifyMigration",
    "compileKotlinJvm",
    "compileKotlinWasmJs",
    "--console=plain",
  ],
  fixture,
);
const sql = readdirSync(path.join(fixture, "build/migrations")).filter((name) =>
  name.endsWith(".sql"),
);
assert.equal(sql.length, 1);
assert.match(readFileSync(path.join(fixture, "build/migrations", sql[0]), "utf8"), /CREATE TABLE/);
console.log(
  "Published core variants and Gradle plugin marker/implementation work without a composite build",
);
