/*
 * Run the compiled Worker with a local Hyperdrive binding.
 * Usage: node integration-tests/hyperdrive/test-worker.mjs <postgres|mysql> [port]
 * CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE must point to a disposable DB.
 * WRANGLER_BIN may name the local Wrangler executable; it defaults to `wrangler`.
 */
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const database = process.argv[2];
if (database !== "postgres" && database !== "mysql") throw new Error("Expected postgres or mysql");
const port = Number(process.argv[3] ?? 8791);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid port");
if (!process.env.CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE) {
  throw new Error("Set CLOUDFLARE_HYPERDRIVE_LOCAL_CONNECTION_STRING_HYPERDRIVE");
}
const config = fileURLToPath(new URL("./wrangler.jsonc", import.meta.url));
const worker = spawn(
  process.env.WRANGLER_BIN ?? "wrangler",
  ["dev", "--config", config, "--ip", "127.0.0.1", "--port", String(port)],
  { env: { ...process.env, CI: "true" }, stdio: ["ignore", "pipe", "pipe"] },
);
let output = "";
worker.stdout.on("data", (chunk) => {
  output = (output + chunk).slice(-20000);
});
worker.stderr.on("data", (chunk) => {
  output = (output + chunk).slice(-20000);
});

try {
  const deadline = Date.now() + 120000;
  while (!/Ready on http:\/\/127\.0\.0\.1:/u.test(output)) {
    if (worker.exitCode !== null) throw new Error(`Wrangler exited before readiness: ${output}`);
    if (/Build failed with \d+ errors?:/u.test(output))
      throw new Error(`Wrangler build failed: ${output}`);
    if (Date.now() > deadline) throw new Error(`Wrangler readiness timed out: ${output}`);
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  const response = await fetch(`http://127.0.0.1:${port}/${database}`, {
    method: "POST",
    signal: AbortSignal.timeout(120000),
  });
  const result = await response.json();
  if (!response.ok || !result.ok)
    throw new Error(`Worker contract failed: ${JSON.stringify(result)}`);
  console.log(`Local Worker Hyperdrive ${database} migration contract passed`);
} finally {
  worker.kill("SIGTERM");
  await Promise.race([
    new Promise((resolve) => worker.once("exit", resolve)),
    new Promise((resolve) => setTimeout(resolve, 5000)),
  ]);
  if (worker.exitCode === null) worker.kill("SIGKILL");
}
