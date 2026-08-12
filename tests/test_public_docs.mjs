import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const githubRepositoryUrl = "https://github.com/ALTIS13/Apollo.PZ-MP-Sync";
const githubReleaseUrl = `${githubRepositoryUrl}/releases/latest`;
const workshopUrl = "https://steamcommunity.com/sharedfiles/filedetails/?id=3780069702";
const imageDigest = "sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951";

async function requiredText(relativePath) {
  try {
    return await readFile(path.join(projectRoot, ...relativePath.split("/")), "utf8");
  } catch (error) {
    if (error?.code === "ENOENT" && relativePath.startsWith("public/")) {
      const materializedPath = relativePath.slice("public/".length);
      try {
        return await readFile(path.join(projectRoot, ...materializedPath.split("/")), "utf8");
      } catch (fallbackError) {
        if (fallbackError?.code !== "ENOENT") throw fallbackError;
      }
    }
    if (error?.code === "ENOENT") assert.fail(`required public file is missing: ${relativePath}`);
    throw error;
  }
}

function numberedHeadings(markdown) {
  return [...markdown.matchAll(/^##\s+(\d+)\./gmu)].map((match) => match[1]);
}

function assertExactPublicUrls(text, label) {
  assert.match(text, new RegExp(githubReleaseUrl.replaceAll(".", "\\."), "u"), `${label} must link the latest public GitHub Release`);
  assert.match(text, new RegExp(workshopUrl.replaceAll("?", "\\?").replaceAll(".", "\\."), "u"), `${label} must link the exact Workshop item`);
  assert.doesNotMatch(text, /github\.com\/(?!ALTIS13\/Apollo\.PZ-MP-Sync(?:\b|\/))/iu, `${label} contains an unapproved GitHub URL`);
}

function assertNoManualAdminCommands(text, label) {
  assert.doesNotMatch(text, /```(?:bash|console|powershell|sh|shell)/iu, `${label} must not contain an administrator command block`);
  assert.doesNotMatch(text, /type (?:this|the following) command|run (?:this|the following) command/iu, `${label} must not ask administrators to type commands`);
  assert.doesNotMatch(text, /введите команд|выполните команд/iu, `${label} must not ask administrators to type commands`);
}

test("public README describes the current product and canonical entry points", async () => {
  const readme = await requiredText("public/README.md");

  assert.match(readme, /Apollo MP Sync/u);
  assert.match(readme, /Project Zomboid.*42\.20\.2/isu);
  assert.match(readme, /Workshop/iu);
  assert.match(readme, /Native Assist/iu);
  assert.match(readme, /fail(?:s)? closed|fail-closed/iu);
  assert.doesNotMatch(readme, /what(?:'s| is) new(?: in)?\s+0\.2\.0/iu);
  assertExactPublicUrls(readme, "README");
});

test("client EN and RU guides contain only Workshop subscribe, enable, and connect steps", async () => {
  const en = await requiredText("public/docs/CLIENT_INSTALL_EN.md");
  const ru = await requiredText("public/docs/CLIENT_INSTALL_RU.md");

  assert.deepEqual(numberedHeadings(en), ["1", "2", "3"]);
  assert.deepEqual(numberedHeadings(ru), ["1", "2", "3"]);
  assert.match(en, /subscribe/iu);
  assert.match(en, /enable/iu);
  assert.match(en, /connect|join/iu);
  assert.match(en, /clients? never install Java/iu);
  assert.match(en, /clients? never install.*Native Assist/isu);
  assert.match(ru, /подпис/iu);
  assert.match(ru, /включ/iu);
  assert.match(ru, /подключ/iu);
  assert.match(ru, /клиент.*не.*Java/isu);
  assert.match(ru, /клиент.*не.*Native Assist/isu);
  for (const [label, text] of [["client EN", en], ["client RU", ru]]) {
    assert.match(text, new RegExp(workshopUrl.replaceAll("?", "\\?").replaceAll(".", "\\."), "u"));
    assert.doesNotMatch(text, /github\.com|releases\/latest/iu, `${label} must stay within the Workshop client flow`);
    assert.doesNotMatch(text, /SSH|Docker|Compose|private key|password|парол|закрыт.*ключ|JAR|javaagent/iu, `${label} contains server-only instructions`);
  }
});

test("administrator EN and RU guides are equivalent command-free Docker-over-SSH procedures", async () => {
  const en = await requiredText("public/docs/NATIVE_ASSIST_EN.md");
  const ru = await requiredText("public/docs/NATIVE_ASSIST_RU.md");

  assert.deepEqual(numberedHeadings(en), ["1", "2", "3", "4", "5", "6", "7", "8", "9"]);
  assert.deepEqual(numberedHeadings(ru), ["1", "2", "3", "4", "5", "6", "7", "8", "9"]);
  assert.match(en, /Docker Compose over SSH/u);
  assert.match(ru, /Docker Compose.*SSH/u);
  assert.match(en, /command-free/iu);
  assert.match(ru, /без команд|команд не треб/iu);
  assertNoManualAdminCommands(en, "Native Assist EN");
  assertNoManualAdminCommands(ru, "Native Assist RU");

  const guides = [
    ["Native Assist EN", en, ["SSH host", "SSH port", "SSH username", "deployment directory", "Compose files", "service name"]],
    ["Native Assist RU", ru, ["SSH-хост", "SSH-порт", "имя пользователя SSH", "каталог развёртывания", "файл.*Compose", "точное имя сервиса"]],
  ];
  for (const [label, text, fields] of guides) {
    for (const value of ["380870", "24574884", "42.20.2", "25", "linux", "amd64", imageDigest]) {
      assert.match(text, new RegExp(value.replaceAll(".", "\\."), "iu"), `${label} is missing exact support value ${value}`);
    }
    for (const field of fields) {
      assert.match(text, new RegExp(field, "iu"), `${label} is missing GUI field ${field}`);
    }
    assert.match(text, /private key|password|закрыт.*ключ|парол/iu);
    assert.match(text, /credential-free|без.*уч[её]тн/isu);
    assert.match(text, /SHA-256.*fingerprint|отпечат.*SHA-256/isu);
    assert.match(text, /explicitly confirm|явно подтверд/iu);
    assert.match(text, /preview|предварительн/iu);
    assert.match(text, /restart confirmation|подтвержден.*перезапуск/iu);
    assert.match(text, /install or update|установк.*обновлен/iu);
    assert.match(text, /READY\/hooks-armed/u);
    assert.match(text, /SERVER STARTED/u);
    assert.match(text, /safe disable|безопасн.*отключ/iu);
    assert.match(text, /full rollback|полн.*откат/iu);
    assert.match(text, /post-backup|после.*резерв/iu);
    assert.match(text, /exactly one rollback|ровно одн.*откат/iu);
    assert.match(text, /fail(?:s)? closed|fail-closed|безопасно блок/iu);
    assertExactPublicUrls(text, label);
  }
  assert.match(en, /Coolify.*not available.*No Coolify changes/isu);
  assert.match(ru, /Coolify.*недоступ.*изменени.*не/isu);
});

test("Russian administrator actions use the exact localized WPF button labels", async () => {
  const ru = await requiredText("public/docs/NATIVE_ASSIST_RU.md");
  const xaml = await requiredText("installer/src/Apollo.NativeAssist.Installer/Localization/Strings.ru-RU.xaml");

  for (const key of ["InstallOrUpdate", "SafeDisable", "FullRollback"]) {
    const label = xaml.match(new RegExp(`x:Key="${key}">([^<]+)<`, "u"))?.[1];
    assert.ok(label, `missing Russian WPF resource ${key}`);
    assert.match(ru, new RegExp(`\\*\\*${label}\\*\\*`, "u"), `guide must use the exact ${key} button label`);
  }
});

test("compatibility guide states the exact boundary and known limits", async () => {
  const compatibility = await requiredText("public/docs/COMPATIBILITY.md");

  for (const value of ["380870", "24574884", "42.20.2", "25", "linux", "amd64", imageDigest, "3780069702", "ApolloMPSyncB42"]) {
    assert.match(compatibility, new RegExp(value.replaceAll(".", "\\."), "iu"), `compatibility guide is missing ${value}`);
  }
  assert.match(compatibility, /Build 41.*not supported/isu);
  assert.match(compatibility, /other Build 42.*not supported/isu);
  assert.match(compatibility, /latency.*cannot|cannot eliminate latency/isu);
  assert.match(compatibility, /mods?.*same actions|mods?.*replace/isu);
  assert.match(compatibility, /ABSENT.*DISABLED.*INCOMPATIBLE.*CIRCUIT_OPEN/isu);
  assert.match(compatibility, /vanilla.*Lua/isu);
  assertExactPublicUrls(compatibility, "compatibility guide");
});

test("verification workflow is read-only and pins the required toolchain and deterministic checks", async () => {
  const workflow = await requiredText("public/.github/workflows/verify.yml");
  const installerRepro = await requiredText("scripts/verify-installer-repro.ps1");
  const verificationSurface = `${workflow}\n${installerRepro}`;

  assert.match(workflow, /^permissions:\s*\n\s+contents:\s+read\s*$/mu);
  assert.match(workflow, /pull_request:/u);
  assert.match(workflow, /push:/u);
  assert.match(workflow, /workflow_call:/u);
  for (const action of ["actions/checkout@v4", "actions/setup-node@v4", "actions/setup-java@v4", "actions/setup-dotnet@v4", "actions/download-artifact@v4", "actions/upload-artifact@v4"]) {
    assert.match(workflow, new RegExp(action.replaceAll("/", "\\/"), "u"), `verify workflow must use ${action}`);
  }
  assert.match(workflow, /node-version:\s*["']?24["']?/u);
  assert.match(workflow, /java-version:\s*["']?25["']?/u);
  assert.match(workflow, /dotnet-version:\s*["']?8\.0\.x["']?/u);
  for (const command of [
    "npm clean-install",
    "npm run test:public-docs",
    "npm run test:public-export",
    "npm run check:release",
    "npm run stage:native-release",
    "dotnet test installer/Apollo.NativeAssist.Installer.sln -c Release",
    "--self-contained true",
    "-p:PublishSingleFile=true",
    "-p:PublishTrimmed=false",
    "npm run build:public-export",
    "PUBLIC-SHA256SUMS",
    "scripts/verify-installer-repro.ps1",
  ]) {
    assert.match(verificationSurface, new RegExp(command.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&"), "u"), `verification surface is missing ${command}`);
  }
  assert.match(installerRepro, /foreach \(\$runName in @\("a", "b"\)\)/u);
  assert.match(installerRepro, /dotnet clean/u);
  assert.match(installerRepro, /generatedDirectoryNames[\s\S]*?"bin"[\s\S]*?"obj"/u);
  assert.doesNotMatch(installerRepro, /Copy-Item[^\n]*installer[^\n]*-Recurse/iu);
  assert.match(workflow, /agent JAR reproducibility mismatch|cmp .*agent/iu);
  assert.match(workflow, /public export reproducibility mismatch|cmp .*PUBLIC-SHA256SUMS/iu);
  assert.doesNotMatch(workflow, /steamcmd|workshop.*upload|gh release|coolify|curl\s+.*(?:https?|ssh)|Invoke-WebRequest/iu);
});

test("release workflow is semver-tagged, reuses verification, and publishes exactly three assets", async () => {
  const workflow = await requiredText("public/.github/workflows/release.yml");

  assert.match(workflow, /tags:\s*\n\s+-\s+["']v0\.2\.0["']/u);
  assert.match(workflow, /^permissions:\s*\n\s+contents:\s+read\s*$/mu);
  assert.match(workflow, /uses:\s+\.\/\.github\/workflows\/verify\.yml/u);
  assert.match(workflow, /release:[\s\S]*?permissions:\s*\n\s+contents:\s+write/u);
  assert.match(workflow, /zip\s+-X/iu);
  assert.match(workflow, /LC_ALL=C\s+sort|sort.*LC_ALL/iu);
  assert.match(workflow, /sha256sum\s+--check\s+SHA256SUMS/u);
  assert.match(workflow, /Apollo\.PZ\.MP\.Sync\.Setup-win-x64\.exe/u);
  assert.match(workflow, /apollo-native-assist-0\.2\.0-pz42\.20\.2-linux-amd64\.zip/u);
  const releaseCommand = workflow.match(/gh release create[\s\S]*?--generate-notes/u)?.[0] ?? "";
  assert.match(releaseCommand, /Apollo\.PZ\.MP\.Sync\.Setup-win-x64\.exe/u);
  assert.match(releaseCommand, /apollo-native-assist-0\.2\.0-pz42\.20\.2-linux-amd64\.zip/u);
  assert.match(releaseCommand, /SHA256SUMS/u);
  assert.match(workflow, /GH_REPO:\s*\$\{\{\s*github\.repository\s*\}\}/u);
  assert.doesNotMatch(workflow, /steamcmd|workshop.*upload|coolify|ssh\s|scp\s|rsync\s|docker\s+(?:context|login)|curl\s+.*(?:https?|ssh)/iu);
});

test("package scripts expose the Task 8 documentation gate", async () => {
  const packageJson = JSON.parse(await requiredText("package.json"));

  assert.equal(packageJson.scripts["test:public-docs"], "node --test tests/test_public_docs.mjs");
  assert.equal(packageJson.scripts["test:public-workflow"], "node --test tests/test_public_workflow.mjs");
});
