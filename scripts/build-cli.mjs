import { createHash } from "node:crypto";
import {
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
import { spawnSync } from "node:child_process";
import postject from "postject";

const root = fileURLToPath(new URL("../", import.meta.url));
const version = process.argv[2];
if (!/^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(version || "")) {
  throw new Error("Usage: node scripts/build-cli.mjs <version>");
}
if (
  !new Set(["darwin-arm64", "darwin-x64", "linux-arm64", "linux-x64", "win32-x64"]).has(
    `${process.platform}-${process.arch}`,
  )
) {
  throw new Error(`Unsupported build host: ${process.platform}-${process.arch}`);
}

function run(command, arguments_, options = {}) {
  const result = spawnSync(command, arguments_, { cwd: root, stdio: "inherit", ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} failed (${result.status})`);
}

// Gradle owns dependency resolution and the Kotlin/JS npm lockfile.
if (process.platform === "win32") {
  run("cmd.exe", [
    "/d",
    "/c",
    "gradlew.bat :migration-cli:jsProductionExecutableCompileSync :kotlinNpmInstall --console=plain",
  ]);
} else {
  run("./gradlew", [
    ":migration-cli:jsProductionExecutableCompileSync",
    ":kotlinNpmInstall",
    "--console=plain",
  ]);
}

const work = path.join(root, "build", "cli-sea");
rmSync(work, { recursive: true, force: true });
const application = path.join(work, "application");
mkdirSync(application, { recursive: true });
const packageDirectory = path.join(root, "build/js/packages/kagiroi-migration-migration-cli");
const npmRoot = path.join(root, "build/js/node_modules");
const manifest = JSON.parse(readFileSync(path.join(packageDirectory, "package.json"), "utf8"));
const kotlinDirectory = path.join(
  root,
  "migration-cli/build/compileSync/js/main/productionExecutable/kotlin",
);
cpSync(kotlinDirectory, path.join(application, "kotlin"), {
  recursive: true,
  filter: (source) => !source.endsWith(".map"),
});
writeFileSync(path.join(application, "package.json"), JSON.stringify({ type: "commonjs" }));
const nodeLicense = [
  path.join(path.dirname(process.execPath), "LICENSE"),
  path.join(path.dirname(process.execPath), "../LICENSE"),
].find((candidate) => existsSync(candidate));
if (!nodeLicense) throw new Error("The Node.js distribution LICENSE file is required");
cpSync(nodeLicense, path.join(application, "NODE-LICENSE"));

const copied = new Set();
function collect(name, from, optional = false) {
  const requireFrom = createRequire(path.join(from, "package.json"));
  const directory = (requireFrom.resolve.paths(`${name}/`) || [])
    .map((candidate) => path.join(candidate, name))
    .find((candidate) => existsSync(path.join(candidate, "package.json")));
  if (!directory) {
    if (optional) return;
    throw new Error(`Missing runtime dependency: ${name} from ${from}`);
  }
  const relative = path.relative(npmRoot, directory);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error(`Runtime dependency is outside Gradle's npm tree: ${name}`);
  }
  if (copied.has(directory)) return;
  copied.add(directory);
  cpSync(directory, path.join(application, "node_modules", relative), {
    recursive: true,
    dereference: true,
    filter: (source) => source === directory || path.basename(source) !== "node_modules",
  });
  const dependency = JSON.parse(readFileSync(path.join(directory, "package.json"), "utf8"));
  for (const child of Object.keys(dependency.dependencies || {})) {
    collect(child, directory, child in (dependency.optionalDependencies || {}));
  }
  for (const child of Object.keys(dependency.optionalDependencies || {}))
    collect(child, directory, true);
  for (const child of Object.keys(dependency.peerDependencies || {})) {
    collect(child, directory, dependency.peerDependenciesMeta?.[child]?.optional === true);
  }
}
for (const name of Object.keys(manifest.dependencies)) collect(name, packageDirectory);

// Test the shipped native addon with the exact runtime that will be embedded.
run(process.execPath, [
  "-e",
  'const {createRequire}=require("node:module");const load=createRequire(process.argv[1]);const Database=load("better-sqlite3");new Database(":memory:").close();',
  path.join(application, "package.json"),
]);
const entries = [];
function addFiles(directory, prefix = "") {
  for (const name of readdirSync(directory).toSorted()) {
    const source = path.join(directory, name);
    const relative = prefix ? `${prefix}/${name}` : name;
    const info = statSync(source);
    if (info.isDirectory()) addFiles(source, relative);
    else
      entries.push({
        path: relative,
        mode: info.mode & 0o777,
        data: readFileSync(source).toString("base64"),
      });
  }
}
addFiles(application);
const payload = gzipSync(Buffer.from(JSON.stringify(entries)), { level: 9 });
writeFileSync(path.join(work, "application.gz"), payload);
writeFileSync(
  path.join(work, "metadata.json"),
  JSON.stringify({
    version,
    sha256: createHash("sha256").update(payload).digest("hex"),
  }),
);
writeFileSync(
  path.join(work, "sea.json"),
  JSON.stringify({
    main: path.join(root, "scripts/sea-bootstrap.cjs"),
    output: path.join(work, "sea.blob"),
    disableExperimentalSEAWarning: true,
    useSnapshot: false,
    useCodeCache: false,
    execArgvExtension: "none",
    assets: {
      metadata: path.join(work, "metadata.json"),
      application: path.join(work, "application.gz"),
    },
  }),
);
run(process.execPath, ["--experimental-sea-config", path.join(work, "sea.json")]);

const platform = process.platform === "win32" ? "windows" : process.platform;
const distributionName = `kagiroi-${version}-${platform}-${process.arch}`;
const distribution = path.join(root, "build", "distributions", distributionName);
rmSync(distribution, { recursive: true, force: true });
mkdirSync(distribution, { recursive: true });
const executable = path.join(
  distribution,
  process.platform === "win32" ? "kagiroi.exe" : "kagiroi",
);
cpSync(process.execPath, executable);
if (process.platform === "darwin") run("codesign", ["--remove-signature", executable]);
await postject.inject(executable, "NODE_SEA_BLOB", readFileSync(path.join(work, "sea.blob")), {
  sentinelFuse: "NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2",
  ...(process.platform === "darwin" ? { machoSegmentName: "NODE_SEA" } : {}),
});
if (process.platform === "darwin") run("codesign", ["--sign", "-", executable]);
cpSync(path.join(root, "LICENSE"), path.join(distribution, "LICENSE"));
cpSync(nodeLicense, path.join(distribution, "NODE-LICENSE"));
cpSync(path.join(root, "README.md"), path.join(distribution, "README.md"));
const archive = `${distribution}.${process.platform === "win32" ? "zip" : "tar.gz"}`;
rmSync(archive, { force: true });
if (process.platform === "win32") {
  run(
    "powershell.exe",
    [
      "-NoProfile",
      "-Command",
      "Compress-Archive -LiteralPath $env:KAGIROI_ARCHIVE_INPUT -DestinationPath $env:KAGIROI_ARCHIVE_OUTPUT",
    ],
    {
      env: { ...process.env, KAGIROI_ARCHIVE_INPUT: distribution, KAGIROI_ARCHIVE_OUTPUT: archive },
    },
  );
} else {
  run("tar", ["-czf", archive, "-C", path.dirname(distribution), distributionName]);
}
writeFileSync(
  `${archive}.sha256`,
  `${createHash("sha256").update(readFileSync(archive)).digest("hex")}  ${path.basename(archive)}\n`,
);
console.log(`Created ${archive}`);
