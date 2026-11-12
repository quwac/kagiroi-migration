// Node SEA preserves the Node ABI used by the bundled database drivers.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { createHash } = require("node:crypto");
const { createRequire } = require("node:module");
const { getAsset } = require("node:sea");
const { gunzipSync } = require("node:zlib");

const metadata = JSON.parse(getAsset("metadata", "utf8"));
if (process.argv.length === 3 && process.argv[2] === "--version") {
  console.log(`kagiroi ${metadata.version}`);
} else {
  try {
    const cacheBase =
      process.env.KAGIROI_CACHE_DIR ||
      (process.platform === "win32"
        ? path.join(process.env.LOCALAPPDATA || os.homedir(), "Kagiroi", "Cache")
        : path.join(process.env.XDG_CACHE_HOME || path.join(os.homedir(), ".cache"), "kagiroi"));
    const directory = path.join(cacheBase, metadata.sha256);
    fs.mkdirSync(cacheBase, { recursive: true, mode: 0o700 });
    if (!fs.existsSync(path.join(directory, ".complete"))) {
      const compressed = Buffer.from(getAsset("application"));
      if (createHash("sha256").update(compressed).digest("hex") !== metadata.sha256) {
        throw new Error("Embedded application checksum mismatch");
      }
      const entries = JSON.parse(gunzipSync(compressed).toString("utf8"));
      const temporary = fs.mkdtempSync(path.join(cacheBase, ".extract-"));
      try {
        for (const entry of entries) {
          if (
            !entry.path ||
            entry.path.split("/").some((part) => !part || part === "..") ||
            path.isAbsolute(entry.path) ||
            entry.path.includes("\\") ||
            entry.path.includes(":")
          ) {
            throw new Error("Invalid embedded application path");
          }
          const target = path.join(temporary, entry.path);
          fs.mkdirSync(path.dirname(target), { recursive: true, mode: 0o700 });
          fs.writeFileSync(target, Buffer.from(entry.data, "base64"), { mode: entry.mode });
        }
        fs.writeFileSync(path.join(temporary, ".complete"), metadata.sha256);
        try {
          fs.renameSync(temporary, directory);
        } catch (failure) {
          // Another invocation may have finished extracting the same payload.
          if (!fs.existsSync(path.join(directory, ".complete"))) throw failure;
        }
      } finally {
        fs.rmSync(temporary, { recursive: true, force: true });
      }
    }
    const entrypoint = path.join(directory, "kotlin", "kagiroi-migration-migration-cli.js");
    createRequire(entrypoint)(entrypoint);
  } catch (failure) {
    console.error(`kagiroi: ${failure.message}`);
    process.exitCode = 1;
  }
}
