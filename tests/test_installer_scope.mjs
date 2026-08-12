import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { access, lstat, mkdtemp, readdir, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const installerSourceRoot = path.join(projectRoot, "installer", "src");
const sourceExtensions = new Set([".cs", ".csproj", ".xaml", ".json"]);
const windowsReparsePointAttribute = 0x400;
const execFileAsync = promisify(execFile);

function powershellLiteral(value) {
  return `'${value.replaceAll("'", "''")}'`;
}

async function windowsFileAttributes(candidate) {
  const script = `[Console]::Write([int][IO.File]::GetAttributes(${powershellLiteral(candidate)}))`;
  const { stdout } = await runPowerShell(script);
  const attributes = Number.parseInt(stdout.trim(), 10);
  if (!Number.isInteger(attributes)) {
    throw new Error(`Windows did not return file attributes for ${candidate}`);
  }
  return attributes;
}

async function runPowerShell(script) {
  const encoded = Buffer.from(script, "utf16le").toString("base64");
  return execFileAsync("powershell.exe", [
    "-NoProfile",
    "-NonInteractive",
    "-EncodedCommand",
    encoded,
  ]);
}

async function appExecutionAliasFixture(t) {
  if (process.platform !== "win32") {
    t.skip("Windows App Execution Alias reparse regression is Windows-only");
    return undefined;
  }

  const alias = path.join(process.env.LOCALAPPDATA ?? "", "Microsoft", "WindowsApps", "python.exe");
  try {
    await access(alias);
  } catch {
    t.skip(`Cannot create exact non-ordinary reparse fixture: App Execution Alias is unavailable at ${alias}`);
    return undefined;
  }

  const root = await mkdtemp(path.join(tmpdir(), "apollo-installer-reparse-"));
  const candidate = path.join(root, "hostile.cs");
  t.after(() => rm(root, { recursive: true, force: true }));
  try {
    await runPowerShell(
      `New-Item -ItemType HardLink -Path ${powershellLiteral(candidate)} -Target ${powershellLiteral(alias)} | Out-Null`,
    );
  } catch (error) {
    t.skip(`Cannot create exact non-ordinary reparse fixture from App Execution Alias: ${error.message}`);
    return undefined;
  }

  return { root, candidate };
}

async function verifyWindowsTreeHasNoReparsePoints(sourceRoot) {
  const script = [
    "$ErrorActionPreference = 'Stop'",
    "function Test-InstallerComponent([string]$candidate) {",
    "  $attributes = [IO.File]::GetAttributes($candidate)",
    "  if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {",
    "    throw \"reparse point: $candidate\"",
    "  }",
    "  if (($attributes -band [IO.FileAttributes]::Directory) -ne 0) {",
    "    foreach ($child in [IO.Directory]::EnumerateFileSystemEntries($candidate)) {",
    "      Test-InstallerComponent $child",
    "    }",
    "  }",
    "}",
    `Test-InstallerComponent ${powershellLiteral(sourceRoot)}`,
  ].join("\n");
  try {
    await runPowerShell(script);
  } catch (error) {
    throw new Error(`Installer source contains a link or reparse point, or could not verify one: ${sourceRoot}`, {
      cause: error,
    });
  }
}

async function linkOrReparsePoint(candidate, stats, reparsePointProbe) {
  if (stats.isSymbolicLink()) return true;
  if (process.platform !== "win32") return false;
  try {
    return await reparsePointProbe(candidate);
  } catch (error) {
    throw new Error(`Installer source cannot verify link or reparse-point evidence: ${candidate}`, {
      cause: error,
    });
  }
}

async function readInstallerText(
  sourceRoot = installerSourceRoot,
  { lstatComponent = lstat, reparsePointProbe } = {},
) {
  let effectiveReparsePointProbe = reparsePointProbe;
  if (process.platform === "win32" && effectiveReparsePointProbe === undefined) {
    await verifyWindowsTreeHasNoReparsePoints(sourceRoot);
    effectiveReparsePointProbe = async () => false;
  }
  effectiveReparsePointProbe ??= async () => false;
  const chunks = [];

  async function visit(directory) {
    const directoryStats = await lstatComponent(directory);
    if (await linkOrReparsePoint(directory, directoryStats, effectiveReparsePointProbe)) {
      throw new Error(`Installer source contains a link or reparse point: ${directory}`);
    }

    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const candidate = path.join(directory, entry.name);
      const candidateStats = await lstatComponent(candidate);
      if (entry.isSymbolicLink() || await linkOrReparsePoint(candidate, candidateStats, effectiveReparsePointProbe)) {
        throw new Error(`Installer source contains a link or reparse point: ${candidate}`);
      }
      if (candidateStats.isDirectory()) {
        await visit(candidate);
      } else if (candidateStats.isFile() && sourceExtensions.has(path.extname(entry.name).toLowerCase())) {
        chunks.push(await readFile(candidate, "utf8"));
      }
    }
  }

  await visit(sourceRoot);
  return chunks.join("\n");
}

test("installer ships no functional Coolify path", async () => {
  await assert.rejects(access("installer/src/Apollo.NativeAssist.Installer.Infrastructure/Coolify"));
  const source = await readInstallerText();
  for (const forbidden of [
    /ICoolifyClient/u,
    /CoolifyConnector/u,
    /\/api\/v1\/services/u,
    /coolifyToken/iu,
    /PackageReference Include="YamlDotNet"/u,
  ]) {
    assert.doesNotMatch(source, forbidden);
  }
});

test("scope scanner rejects raw Windows reparse evidence when lstat appears ordinary", async (t) => {
  const fixture = await appExecutionAliasFixture(t);
  if (!fixture) return;

  const attributes = await windowsFileAttributes(fixture.candidate);
  assert.notEqual(
    attributes & windowsReparsePointAttribute,
    0,
    "App Execution Alias fixture must carry a real Windows ReparsePoint attribute",
  );
  const ordinaryFileStats = {
    isSymbolicLink: () => false,
    isDirectory: () => false,
    isFile: () => true,
  };
  await assert.rejects(
    readInstallerText(fixture.root, {
      lstatComponent: async (candidate) => candidate === fixture.candidate ? ordinaryFileStats : lstat(candidate),
    }),
    /link or reparse point/u,
  );
});
