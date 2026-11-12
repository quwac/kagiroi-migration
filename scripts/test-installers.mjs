import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  chmodSync,
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const root = fileURLToPath(new URL("../", import.meta.url));
const windows = process.platform === "win32";

test(
  "shell installer selects all four targets, supports latest and paths with spaces, and rejects corrupt downloads",
  { skip: windows },
  () => {
    const directory = mkdtempSync(path.join(os.tmpdir(), "kagiroi-install-test-"));
    try {
      const bin = path.join(directory, "bin");
      const downloads = path.join(directory, "downloads");
      mkdirSync(bin);
      mkdirSync(downloads);
      writeFileSync(
        path.join(bin, "uname"),
        '#!/bin/sh\ncase "$1" in -s) printf "%s\\n" "$TEST_OS" ;; -m) printf "%s\\n" "$TEST_ARCH" ;; esac\n',
      );
      chmodSync(path.join(bin, "uname"), 0o755);
      writeFileSync(
        path.join(bin, "curl"),
        `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
if (args.includes('%{url_effective}')) {
  process.stdout.write('https://github.com/quwac/kagiroi-migration/releases/tag/v0.1.0');
} else {
  const url = args.find(x => x.startsWith('https://'));
  const output = args[args.indexOf('-o') + 1];
  fs.copyFileSync(path.join(process.env.TEST_DOWNLOADS, path.basename(url)), output);
}
`,
      );
      chmodSync(path.join(bin, "curl"), 0o755);
      for (const [system, arch, platform, architecture] of [
        ["Darwin", "arm64", "darwin", "arm64"],
        ["Darwin", "x86_64", "darwin", "x64"],
        ["Linux", "aarch64", "linux", "arm64"],
        ["Linux", "x86_64", "linux", "x64"],
      ]) {
        const name = `kagiroi-0.1.0-${platform}-${architecture}`;
        const staged = path.join(directory, name);
        mkdirSync(staged);
        writeFileSync(path.join(staged, "kagiroi"), "#!/bin/sh\nprintf 'fixture\\n'\n", {
          mode: 0o755,
        });
        const archive = `${name}.tar.gz`;
        const tar = spawnSync("tar", [
          "-czf",
          path.join(downloads, archive),
          "-C",
          directory,
          name,
        ]);
        assert.equal(tar.status, 0, tar.stderr?.toString());
        const digest = createHash("sha256")
          .update(readFileSync(path.join(downloads, archive)))
          .digest("hex");
        writeFileSync(path.join(downloads, `${archive}.sha256`), `${digest}  ${archive}\n`);
        const installed = path.join(directory, `install ${platform} ${architecture}`);
        const env = {
          ...process.env,
          PATH: `${bin}:${process.env.PATH}`,
          TEST_OS: system,
          TEST_ARCH: arch,
          TEST_DOWNLOADS: downloads,
          KAGIROI_INSTALL_DIR: installed,
          KAGIROI_VERSION: "",
        };
        const result = spawnSync("sh", [path.join(root, "install.sh")], { env, encoding: "utf8" });
        assert.equal(result.status, 0, result.stderr);
        assert.match(result.stdout, /Installed kagiroi 0.1.0/);
        assert.equal(
          readFileSync(path.join(installed, "kagiroi"), "utf8"),
          "#!/bin/sh\nprintf 'fixture\\n'\n",
        );
        writeFileSync(path.join(downloads, archive), "corrupt");
        const failed = spawnSync("sh", [path.join(root, "install.sh"), "--version", "v0.1.0"], {
          env,
          encoding: "utf8",
        });
        assert.notEqual(failed.status, 0);
        assert.match(failed.stderr, /checksum mismatch/);
        assert.equal(
          readFileSync(path.join(installed, "kagiroi"), "utf8"),
          "#!/bin/sh\nprintf 'fixture\\n'\n",
        );
      }
      const unsupported = spawnSync("sh", [path.join(root, "install.sh")], {
        env: {
          ...process.env,
          PATH: `${bin}:${process.env.PATH}`,
          TEST_OS: "Linux",
          TEST_ARCH: "riscv64",
          KAGIROI_INSTALL_DIR: path.join(directory, "unsupported"),
        },
        encoding: "utf8",
      });
      assert.notEqual(unsupported.status, 0);
      assert.equal(existsSync(path.join(directory, "unsupported")), false);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  },
);

test(
  "PowerShell installer supports latest, spaces and checksum failures without modifying user PATH",
  { skip: !windows },
  () => {
    const directory = mkdtempSync(path.join(os.tmpdir(), "kagiroi-install-test-"));
    try {
      const name = "kagiroi-0.1.0-windows-x64";
      mkdirSync(path.join(directory, name));
      writeFileSync(path.join(directory, name, "kagiroi.exe"), "fixture");
      const harness = path.join(directory, "test.ps1");
      writeFileSync(
        harness,
        `
$ErrorActionPreference = 'Stop'
$archive = Join-Path $env:TEST_DIRECTORY 'kagiroi-0.1.0-windows-x64.zip'
Compress-Archive -LiteralPath (Join-Path $env:TEST_DIRECTORY 'kagiroi-0.1.0-windows-x64') -DestinationPath $archive
$hash = (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant()
Set-Content -NoNewline -Path "$archive.sha256" -Value "$hash  kagiroi-0.1.0-windows-x64.zip"
function Invoke-RestMethod { param($Uri) return @{ tag_name = 'v0.1.0' } }
function Invoke-WebRequest { param([switch]$UseBasicParsing, $Uri, $OutFile) Copy-Item -LiteralPath (Join-Path $env:TEST_DIRECTORY ([Uri]$Uri).Segments[-1]) -Destination $OutFile }
$destination = Join-Path $env:TEST_DIRECTORY 'install with spaces'
$before = [Environment]::GetEnvironmentVariable('Path', 'User')
& $env:TEST_INSTALLER -InstallDir $destination -NoModifyPath
if ((Get-Content -Raw (Join-Path $destination 'kagiroi.exe')) -ne 'fixture') { throw 'Missing installed executable' }
Set-Content -Path $archive -Value 'corrupt'
$rejected = $false
try { & $env:TEST_INSTALLER -Version '0.1.0' -InstallDir $destination -NoModifyPath } catch { $rejected = $_.Exception.Message -match 'checksum mismatch' }
if (-not $rejected) { throw 'Corrupt download was accepted' }
if ((Get-Content -Raw (Join-Path $destination 'kagiroi.exe')) -ne 'fixture') { throw 'Existing install was modified' }
if ([Environment]::GetEnvironmentVariable('Path', 'User') -ne $before) { throw 'User PATH was modified' }
`,
      );
      const result = spawnSync(
        "powershell.exe",
        ["-NoProfile", "-ExecutionPolicy", "Bypass", "-File", harness],
        {
          env: {
            ...process.env,
            TEST_DIRECTORY: directory,
            TEST_INSTALLER: path.join(root, "install.ps1"),
            KAGIROI_VERSION: "",
          },
          encoding: "utf8",
        },
      );
      assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  },
);
