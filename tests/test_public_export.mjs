import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import {
  link,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rename,
  rm,
  symlink,
  writeFile,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { buildPublicExport } from "../scripts/build-public-export.mjs";
import { isSensitiveKeyName } from "../scripts/package-utils.mjs";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const reviewedRootDestinations = [
  ".github/workflows/release.yml",
  ".github/workflows/verify.yml",
  "README.md",
];

async function activeManifestLayout() {
  const manifestPath = path.join(projectRoot, "public", "public-export.json");
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  const byDestination = new Map(manifest.map((entry) => [entry.destination, entry]));
  const rootEntries = reviewedRootDestinations.map((destination) => {
    const entry = byDestination.get(destination);
    assert.ok(entry, `active manifest is missing reviewed root destination ${destination}`);
    return entry;
  });
  const internalSources = [
    "public/.github/workflows/release.yml",
    "public/.github/workflows/verify.yml",
    "public/README.md",
  ];
  const sources = rootEntries.map((entry) => entry.source);
  let mode;
  if (sources.every((source, index) => source === internalSources[index])) mode = "internal";
  else if (sources.every((source, index) => source === reviewedRootDestinations[index])) mode = "identity";
  else assert.fail(`active public manifest mixes or changes reviewed layout tuples: ${sources.join(", ")}`);
  for (const [index, entry] of rootEntries.entries()) {
    const expectedSource = mode === "internal" ? internalSources[index] : reviewedRootDestinations[index];
    assert.deepEqual(entry, { source: expectedSource, destination: reviewedRootDestinations[index], kind: "file" });
  }
  return {
    manifest,
    manifestPath,
    mode,
    sourceFor(destination) {
      return byDestination.get(destination).source;
    },
  };
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function publicFixture(t) {
  const temporaryRoot = await mkdtemp(path.join(tmpdir(), "apollo-public-export-"));
  const sourceRoot = path.join(temporaryRoot, "source");
  const outputRoot = path.join(temporaryRoot, "output");
  const manifestPath = path.join(temporaryRoot, "manifest.json");
  const entries = [];
  await mkdir(sourceRoot);
  t?.after(() => rm(temporaryRoot, { recursive: true, force: true }));

  return {
    sourceRoot,
    outputRoot,
    manifestPath,
    entries,
    options: { sourceRoot, outputRoot, manifestPath },
    async put(relativePath, contents, {
      destination = relativePath,
      kind = "file",
      listed = true,
    } = {}) {
      const absolutePath = path.join(sourceRoot, ...relativePath.split("/"));
      await mkdir(path.dirname(absolutePath), { recursive: true });
      await writeFile(absolutePath, contents);
      if (listed) entries.push({ source: relativePath, destination, kind });
    },
    async writeManifest(manifest = entries) {
      await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");
    },
  };
}

async function relativeFiles(root) {
  const files = [];
  async function visit(directory, relativeDirectory = "") {
    const entries = await readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      const relativePath = relativeDirectory ? `${relativeDirectory}/${entry.name}` : entry.name;
      if (entry.isDirectory()) await visit(path.join(directory, entry.name), relativePath);
      else files.push(relativePath);
    }
  }
  await visit(root);
  return files.sort();
}

async function treeHashes(root) {
  const result = {};
  for (const relativePath of await relativeFiles(root)) {
    result[relativePath] = sha256(await readFile(path.join(root, ...relativePath.split("/"))));
  }
  return result;
}

async function allPublicText(root) {
  const textExtensions = new Set(["", ".env", ".java", ".kts", ".lua", ".md", ".mf", ".properties", ".sh", ".txt", ".yaml"]);
  const chunks = [];
  for (const relativePath of await relativeFiles(root)) {
    if (relativePath === "PUBLIC-SHA256SUMS") continue;
    if (textExtensions.has(path.extname(relativePath).toLowerCase())) {
      chunks.push(await readFile(path.join(root, ...relativePath.split("/")), "utf8"));
    }
  }
  return chunks.join("\n");
}

async function buildFixtureExport(t) {
  const fixture = await publicFixture(t);
  await fixture.put("README.md", "# Apollo public\n");
  await fixture.put("LICENSE", "MIT\n");
  await fixture.put("SHA256SUMS", `${"0".repeat(64)}  workshop/preview.png\n`);
  await fixture.put("workshop/preview.png", Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  await fixture.put("workshop/Contents/mods/ApolloMPSyncB42/42/mod.info", "id=ApolloMPSyncB42\n");
  await fixture.put("native-assist/build.gradle.kts", "plugins { java }\n");
  await fixture.put("native-assist/gradle/wrapper/gradle-wrapper.jar",
    await readFile(path.join(projectRoot, "native-assist", "gradle", "wrapper", "gradle-wrapper.jar")));
  await fixture.put("native-assist/src/main/java/ru/apollot/pzsync/agent/ApolloNativeAgent.java",
    "package ru.apollot.pzsync.agent; public final class ApolloNativeAgent {}\n");
  await fixture.put("deploy/apollo-native.env.example", "APOLLO_NATIVE_ASSIST=off\n");
  await fixture.writeManifest();
  await buildPublicExport(fixture.options);
  return fixture;
}

async function assertNoUsefulOutput(fixture) {
  await assert.rejects(readFile(path.join(fixture.outputRoot, "PUBLIC-SHA256SUMS")));
  await assert.rejects(readdir(fixture.outputRoot));
}

test("real public export contains the complete buildable installer, tests, docs, and workflows", async (t) => {
  const temporaryRoot = await mkdtemp(path.join(tmpdir(), "apollo-real-public-export-"));
  const outputRoot = path.join(temporaryRoot, "output");
  t.after(() => rm(temporaryRoot, { recursive: true, force: true }));
  const layout = await activeManifestLayout();

  await buildPublicExport({
    sourceRoot: projectRoot,
    outputRoot,
    manifestPath: layout.manifestPath,
  });

  const files = await relativeFiles(outputRoot);
  const required = [
    ".github/workflows/release.yml",
    ".github/workflows/verify.yml",
    "README.md",
    "compatibility-current-live.md",
    "installer/Apollo.NativeAssist.Installer.sln",
    "installer/src/Apollo.NativeAssist.Installer/App.xaml",
    "installer/src/Apollo.NativeAssist.Installer/App.xaml.cs",
    "installer/src/Apollo.NativeAssist.Installer.Core/Contracts.cs",
    "installer/src/Apollo.NativeAssist.Installer.Infrastructure/Ssh/SshHostKeyProbe.cs",
    "installer/tests/Apollo.NativeAssist.Installer.Core.Tests/InstallationOrchestratorTests.cs",
    "installer/tests/Apollo.NativeAssist.Installer.Infrastructure.Tests/SshRemoteSessionTests.cs",
    "installer/tests/Apollo.NativeAssist.Installer.Ui.Tests/MainWindowViewModelTests.cs",
    "package-lock.json",
    "package.json",
    "public/docs/CLIENT_INSTALL_EN.md",
    "public/docs/CLIENT_INSTALL_RU.md",
    "public/docs/COMPATIBILITY.md",
    "public/docs/NATIVE_ASSIST_EN.md",
    "public/docs/NATIVE_ASSIST_RU.md",
    "public/public-export.json",
    "public/release/release-manifest.json",
    "scripts/build-public-export.mjs",
    "scripts/stage-native-release.mjs",
    "scripts/verify-installer-repro.ps1",
    "tests/run.lua",
    "tests/test_public_docs.mjs",
    "tests/test_public_export.mjs",
    "tests/test_public_workflow.mjs",
    "workshop/workshop.txt",
  ];
  for (const relativePath of required) {
    assert.ok(files.includes(relativePath), `real public export is missing ${relativePath}`);
  }
  assert.equal(files.some((file) => file.startsWith("native-assist/src/test/")), false);
  assert.equal(files.some((file) => file.startsWith("tests/")), true);
  assert.equal(files.includes("package.json"), true);
  assert.equal(files.includes("scripts/build-public-export.mjs"), true);

  assert.equal(
    await readFile(path.join(outputRoot, "README.md"), "utf8"),
    await readFile(path.join(projectRoot, ...layout.sourceFor("README.md").split("/")), "utf8"),
  );
  for (const workflow of ["release.yml", "verify.yml"]) {
    const destination = `.github/workflows/${workflow}`;
    assert.equal(
      await readFile(path.join(outputRoot, ...destination.split("/")), "utf8"),
      await readFile(path.join(projectRoot, ...layout.sourceFor(destination).split("/")), "utf8"),
    );
  }
  assert.equal(files.filter((file) => file.endsWith(".jar")).join("\n"), "native-assist/gradle/wrapper/gradle-wrapper.jar");
  assert.equal(files.filter((file) => /(?:^|\/)(?:bin|obj|build|TestResults|artifacts)(?:\/|$)/iu.test(file)).length, 0);
  assert.equal(files.filter((file) => /\.(?:dll|exe|pdb|zip|class|so)$/iu.test(file)).length, 0);
  const selfManifest = JSON.parse(await readFile(path.join(outputRoot, "public", "public-export.json"), "utf8"));
  assert.ok(selfManifest.every((entry) => entry.source === entry.destination), "public self-export manifest must use identity mappings only");
  assert.deepEqual(
    selfManifest.map((entry) => entry.destination).sort(),
    files.filter((file) => file !== "PUBLIC-SHA256SUMS").sort(),
    "public self-export manifest must describe the complete materialized tree",
  );
});

test("public export permits only the reviewed public re-rooting tuples", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("public/README.md", "# Public Apollo\n", { destination: "README.md" });
  await fixture.put("public/.github/workflows/verify.yml", "permissions:\n  contents: read\n", {
    destination: ".github/workflows/verify.yml",
  });
  await fixture.writeManifest();

  await buildPublicExport(fixture.options);
  assert.equal(await readFile(path.join(fixture.outputRoot, "README.md"), "utf8"), "# Public Apollo\n");
  assert.equal(
    await readFile(path.join(fixture.outputRoot, ".github", "workflows", "verify.yml"), "utf8"),
    "permissions:\n  contents: read\n",
  );
});

test("installer governed trees reject unlisted source material", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("installer/src/Apollo.NativeAssist.Installer.Core/Contracts.cs", "namespace Apollo;\n");
  await fixture.put("installer/src/Apollo.NativeAssist.Installer.Core/Unexpected.cs", "namespace Unexpected;\n", { listed: false });
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /unexpected source material.*Unexpected\.cs/i);
  await assertNoUsefulOutput(fixture);
});

test("installer generated output is ignored and can never enter the source export", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("installer/src/Apollo.NativeAssist.Installer.Core/Contracts.cs", "namespace Apollo;\n");
  await fixture.put("installer/src/Apollo.NativeAssist.Installer.Core/bin/Release/setup.exe", "generated", { listed: false });
  await fixture.put("installer/src/Apollo.NativeAssist.Installer.Core/obj/project.assets.json", "{}\n", { listed: false });
  await fixture.writeManifest();

  await buildPublicExport(fixture.options);
  assert.deepEqual(await relativeFiles(fixture.outputRoot), [
    "PUBLIC-SHA256SUMS",
    "installer/src/Apollo.NativeAssist.Installer.Core/Contracts.cs",
  ]);
});

test("public export is deterministic, exact, and contains no private path bytes", async (t) => {
  const a = await buildFixtureExport(t);
  const b = await buildFixtureExport(t);

  assert.deepEqual(await treeHashes(a.outputRoot), await treeHashes(b.outputRoot));
  assert.deepEqual(await relativeFiles(a.outputRoot), [
    "LICENSE",
    "PUBLIC-SHA256SUMS",
    "README.md",
    "SHA256SUMS",
    "deploy/apollo-native.env.example",
    "native-assist/build.gradle.kts",
    "native-assist/gradle/wrapper/gradle-wrapper.jar",
    "native-assist/src/main/java/ru/apollot/pzsync/agent/ApolloNativeAgent.java",
    "workshop/Contents/mods/ApolloMPSyncB42/42/mod.info",
    "workshop/preview.png",
  ]);
  const privateMarkerPattern = new RegExp(
    ["femboy" + "furrland", String.raw`\.ops-${"tmp"}`, String.raw`token\s*=`, String.raw`password\s*=`, String.raw`BEGIN [A-Z ]+PRIVATE KEY`].join("|"),
    "i",
  );
  assert.doesNotMatch(await allPublicText(a.outputRoot), privateMarkerPattern);

  const checksumLines = (await readFile(path.join(a.outputRoot, "PUBLIC-SHA256SUMS"), "utf8")).trimEnd().split("\n");
  const exportedFiles = (await relativeFiles(a.outputRoot)).filter((file) => file !== "PUBLIC-SHA256SUMS");
  assert.deepEqual(checksumLines.map((line) => line.slice(66)), exportedFiles);
  for (const [index, relativePath] of exportedFiles.entries()) {
    assert.equal(checksumLines[index], `${sha256(await readFile(path.join(a.outputRoot, ...relativePath.split("/"))))}  ${relativePath}`);
  }
});

test("public export rejects unallowlisted material before touching prior output", async (t) => {
  const fixture = await publicFixture(t);
  const privatePath = ".ops-" + "tmp/token.env";
  await fixture.put(privatePath, "TOKEN=secret\n");
  await fixture.writeManifest();
  await mkdir(fixture.outputRoot);
  await writeFile(path.join(fixture.outputRoot, "old.txt"), "stale");

  await assert.rejects(
    () => buildPublicExport(fixture.options),
    new RegExp(String.raw`unallowlisted.*\.ops-${"tmp"}`, "i"),
  );
  assert.equal(await readFile(path.join(fixture.outputRoot, "old.txt"), "utf8"), "stale");
  await assert.rejects(readFile(path.join(fixture.outputRoot, "PUBLIC-SHA256SUMS")));
});

test("public export rejects approved sources remapped to non-approved destinations", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("LICENSE", "MIT\n", { destination: "workshop/Contents/LICENSE" });
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /approved source.*destination|tuple/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects the Gradle wrapper JAR remapped across the Workshop boundary", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("native-assist/gradle/wrapper/gradle-wrapper.jar",
    await readFile(path.join(projectRoot, "native-assist", "gradle", "wrapper", "gradle-wrapper.jar")), {
      destination: "workshop/Contents/mods/ApolloMPSyncB42/42/gradle-wrapper.jar",
    });
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /approved source.*destination|cross-zone|tuple/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects categories reserved for an explicit later policy change", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("scripts/future-public-tool.mjs", "export const future = true;\n");
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /unallowlisted.*future-public-tool/i);
  await assertNoUsefulOutput(fixture);
});

test("public export pins the only approved Gradle wrapper JAR bytes", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("native-assist/gradle/wrapper/gradle-wrapper.jar", Buffer.from([0x50, 0x4b, 0x03, 0x04]));
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /wrapper.*SHA-256|pinned.*wrapper/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects unlisted files inside an exhaustively governed source tree", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("workshop/Contents/mods/ApolloMPSyncB42/42/mod.info", "id=ApolloMPSyncB42\n");
  await fixture.put("workshop/Contents/mods/ApolloMPSyncB42/42/surprise.txt", "not reviewed\n", { listed: false });
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /unexpected source material.*surprise\.txt/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects missing listed sources instead of producing a partial tree", async (t) => {
  const fixture = await publicFixture(t);
  fixture.entries.push({ source: "README.md", destination: "README.md", kind: "file" });
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /missing source.*README\.md/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects case-aliased source spelling", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("readme.md", "# wrong source spelling\n", { listed: false });
  fixture.entries.push({ source: "README.md", destination: "README.md", kind: "file" });
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /source path alias|missing source/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects traversal, aliases, collisions, and malformed manifest entries", async (t) => {
  const cases = [
    ["source traversal", [{ source: "../README.md", destination: "README.md", kind: "file" }], /source.*normalized relative path/i],
    ["destination traversal", [{ source: "README.md", destination: "../README.md", kind: "file" }], /destination.*normalized relative path/i],
    ["backslash alias", [{ source: "README.md", destination: "docs\\README.md", kind: "file" }], /destination.*normalized relative path/i],
    ["reserved checksum", [{ source: "README.md", destination: "public-sha256sums", kind: "file" }], /reserved destination/i],
    ["unsupported kind", [{ source: "README.md", destination: "README.md", kind: "tree" }], /kind.*file/i],
    ["source collision", [
      { source: "README.md", destination: "one.md", kind: "file" },
      { source: "README.md", destination: "two.md", kind: "file" },
    ], /source collision/i],
    ["case-folded destination collision", [
      { source: "README.md", destination: "Docs/Guide.md", kind: "file" },
      { source: "LICENSE", destination: "docs/guide.md", kind: "file" },
    ], /destination collision/i],
    ["destination prefix collision", [
      { source: "README.md", destination: "docs", kind: "file" },
      { source: "LICENSE", destination: "docs/LICENSE", kind: "file" },
    ], /destination collision/i],
    ["non-adjacent destination prefix collision", [
      { source: "README.md", destination: "a", kind: "file" },
      { source: "LICENSE", destination: "a-file", kind: "file" },
      { source: "SHA256SUMS", destination: "a/nested", kind: "file" },
    ], /destination collision/i],
    ["Windows device alias", [{ source: "README.md", destination: "docs/CON.txt", kind: "file" }], /destination.*device name/i],
  ];

  for (const [name, manifest, expected] of cases) {
    await t.test(name, async (t) => {
      const fixture = await publicFixture(t);
      await fixture.put("README.md", "# Apollo\n", { listed: false });
      await fixture.put("LICENSE", "MIT\n", { listed: false });
      await fixture.put("SHA256SUMS", `${"0".repeat(64)}  README.md\n`, { listed: false });
      await fixture.writeManifest(manifest);
      await assert.rejects(() => buildPublicExport(fixture.options), expected);
      await assertNoUsefulOutput(fixture);
    });
  }
});

test("public export rejects complete relevant Win32 device aliases", async (t) => {
  for (const device of ["CONIN$", "CONOUT$", "COM¹", "COM².log", "COM³", "LPT¹", "LPT².txt", "LPT³"]) {
    await t.test(device, async (t) => {
      const fixture = await publicFixture(t);
      await fixture.put("README.md", "# Apollo\n", { listed: false });
      await fixture.writeManifest([{ source: "README.md", destination: `docs/${device}`, kind: "file" }]);
      await assert.rejects(() => buildPublicExport(fixture.options), /destination.*device name/i);
      await assertNoUsefulOutput(fixture);
    });
  }
});

test("public export rejects a junction anywhere along a listed path", async (t) => {
  const fixture = await publicFixture(t);
  const external = path.join(path.dirname(fixture.sourceRoot), `external-${randomUUID()}`);
  await mkdir(path.join(fixture.sourceRoot, "workshop"));
  await mkdir(path.join(external, "mods", "ApolloMPSyncB42", "42"), { recursive: true });
  await writeFile(path.join(external, "mods", "ApolloMPSyncB42", "42", "mod.info"), "id=ApolloMPSyncB42\n");
  await symlink(external, path.join(fixture.sourceRoot, "workshop", "Contents"), process.platform === "win32" ? "junction" : "dir");
  fixture.entries.push({
    source: "workshop/Contents/mods/ApolloMPSyncB42/42/mod.info",
    destination: "workshop/Contents/mods/ApolloMPSyncB42/42/mod.info",
    kind: "file",
  });
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /link|junction|reparse/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects hard-linked listed files", async (t) => {
  const fixture = await publicFixture(t);
  const original = path.join(path.dirname(fixture.sourceRoot), `original-${randomUUID()}`);
  await writeFile(original, "MIT\n");
  await link(original, path.join(fixture.sourceRoot, "LICENSE"));
  fixture.entries.push({ source: "LICENSE", destination: "LICENSE", kind: "file" });
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport(fixture.options), /hard link|link count/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects in-place mutation while a listed file handle is open", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("LICENSE", "original stable bytes\n");
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport({
    ...fixture.options,
    _testHooks: {
      afterSourceOpen: async ({ relativePath, absolutePath }) => {
        if (relativePath === "LICENSE") await writeFile(absolutePath, "mutated bytes with another size\n");
      },
    },
  }), /changed during snapshot|unstable source snapshot/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects path replacement while the original file handle is open", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("LICENSE", "original stable bytes\n");
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport({
    ...fixture.options,
    _testHooks: {
      afterSourceOpen: async ({ relativePath, absolutePath }) => {
        if (relativePath !== "LICENSE") return;
        await rename(absolutePath, `${absolutePath}.replaced`);
        await writeFile(absolutePath, "replacement source bytes\n");
      },
    },
  }), /changed during snapshot|unstable source snapshot/i);
  await assertNoUsefulOutput(fixture);
});

test("public export revalidates every source after the complete multi-file snapshot", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("LICENSE", "first stable source\n");
  await fixture.put("README.md", "# second stable source\n");
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport({
    ...fixture.options,
    _testHooks: {
      afterAllSourcesRead: async () => {
        await writeFile(path.join(fixture.sourceRoot, "LICENSE"), "changed after its handle closed\n");
      },
    },
  }), /changed after snapshot|unstable source snapshot/i);
  await assertNoUsefulOutput(fixture);
});

test("public export rejects every non-placeholder credential assignment", async (t) => {
  for (const contents of [
    'TOKEN="realSecret123"\n',
    "TOKEN=hunter2\n",
    "PASSWORD=secret.value\n",
    "service_token=sentinel-secret\n",
  ]) {
    await t.test(contents.trim(), async (t) => {
      const fixture = await publicFixture(t);
      await fixture.put("README.md", contents);
      await fixture.writeManifest();
      await assert.rejects(() => buildPublicExport(fixture.options), /credential.*README\.md/i);
      await assertNoUsefulOutput(fixture);
    });
  }

  await t.test("test-like paths do not exempt credential values", async (t) => {
    const fixture = await publicFixture(t);
    await fixture.put("tests/test_public_docs.mjs", "TOKEN=sentinel-secret\n");
    await fixture.writeManifest();
    await assert.rejects(() => buildPublicExport(fixture.options), /credential-looking.*tests\/test_public_docs\.mjs/i);
    await assertNoUsefulOutput(fixture);
  });
});

test("public export rejects bounded multiline credential assignments", async (t) => {
  const cases = [
    ["LF const quoted", 'const API_TOKEN =\n  "realSecret123";\n'],
    ["CRLF let unquoted", "let password =\r\n  hunter2;\r\n"],
    ["comments and blank lines", "var service_secret =\n  // supplied below\n\n  secret.value;\n"],
    ["property-like plain name", "PASSWORD:\n  realSecret123\n"],
    ["property-like quoted name", '"api_token":\n  "realSecret123",\n'],
  ];
  for (const [name, contents] of cases) {
    await t.test(name, async (t) => {
      const fixture = await publicFixture(t);
      await fixture.put("README.md", contents);
      await fixture.writeManifest();
      await assert.rejects(() => buildPublicExport(fixture.options), /credential-looking.*README\.md/i);
      await assertNoUsefulOutput(fixture);
    });
  }
});

test("public export rejects every staged sensitive key family across assignment layouts", async (t) => {
  const keys = ["API_KEY", "ACCESS_KEY", "ACCESS_KEY_ID", "PRIVATE_KEY"];
  const layouts = [
    ["plain", (key) => `${key}=hunter2\n`],
    ["quoted", (key) => `"${key}": "realSecret123"\n`],
    ["LF multiline", (key) => `const ${key} =\n  secret.value;\n`],
    ["CRLF multiline", (key) => `${key}:\r\n  hunter2\r\n`],
    ["comments and blank continuation", (key) => `${key}=\n  // supplied below\n\n  realSecret123;\n`],
  ];
  for (const key of keys) {
    for (const [layout, contentsFor] of layouts) {
      await t.test(`${key} ${layout}`, async (t) => {
        const fixture = await publicFixture(t);
        await fixture.put("README.md", contentsFor(key));
        await fixture.writeManifest();
        await assert.rejects(() => buildPublicExport(fixture.options), /credential-looking.*README\.md/i);
        await assertNoUsefulOutput(fixture);
      });
    }
  }
});

test("shared sensitive-key grammar observes case and separator boundaries", () => {
  for (const key of [
    "API_KEY", "openai_api_key", "API-KEY", "ACCESS_KEY", "aws_access_key_id", "ACCESS-KEY-ID", "PRIVATE_KEY", "PRIVATE-KEY",
    "ADMIN_PASSWORD", "service-token", "client.secret", "rcon",
  ]) assert.equal(isSensitiveKeyName(key), true, `${key} must be sensitive`);
  for (const key of ["monkey", "privateKeyFactory", "apiKeyboard", "accessKeyIdentifier", "hockey"])
    assert.equal(isSensitiveKeyName(key), false, `${key} must not overmatch`);
});

test("public export rejects exact camelCase credential aliases without longer-identifier overmatch", async (t) => {
  const keys = ["apiKey", "APIKey", "ApiKey", "accessKey", "AccessKey", "accessKeyId", "AccessKeyID", "privateKey", "PrivateKey"];
  const layouts = [
    ["plain", (key) => `${key}=hunter2\n`],
    ["quoted", (key) => `"${key}": "realSecret123"\n`],
    ["multiline", (key) => `const ${key} =\n  secret.value;\n`],
  ];
  for (const key of keys) {
    for (const [layout, contentsFor] of layouts) {
      await t.test(`${key} ${layout}`, async (t) => {
        const fixture = await publicFixture(t);
        await fixture.put("README.md", contentsFor(key));
        await fixture.writeManifest();
        await assert.rejects(() => buildPublicExport(fixture.options), /credential-looking.*README\.md/i);
        await assertNoUsefulOutput(fixture);
      });
    }
  }
});


test("public export permits bounded multiline exact runtime placeholders", async (t) => {
  const fixture = await publicFixture(t);
  const safeText = [
    "const API_TOKEN =",
    "  process.env.API_TOKEN;",
    "PASSWORD:",
    "  ${RUNTIME_PASSWORD}",
    '"secret":',
    '  "__REQUIRED_AT_RUNTIME__",',
    "",
  ].join("\n");
  await fixture.put("LICENSE", safeText);
  await fixture.writeManifest();

  await buildPublicExport(fixture.options);
  assert.equal(await readFile(path.join(fixture.outputRoot, "LICENSE"), "utf8"), safeText);
});

test("public export rejects high-confidence secret signatures and private absolute paths", async (t) => {
  const cases = [
    ["declaration assignment", 'const API_TOKEN = "sentinel-production-secret";\n'],
    ["authorization bearer", "Authorization: " + "Bearer abcdefghijklmnopqrstuvwxyz012345\n"],
    ["GitHub PAT", `token ${`ghp_${"A".repeat(36)}`}\n`],
    ["JWT", ["eyJhbGciOiJIUzI1NiJ9", "eyJzdWIiOiJwcml2YXRlIn0", "c2lnbmF0dXJlMTIzNDU2"].join(".") + "\n"],
    ["private key", "-----BEGIN " + "PRIVATE KEY-----\nsentinel\n-----END PRIVATE KEY-----\n"],
    ["credential URI", "postgres://" + "apollo:sentinel-secret@db.internal/apollo\n"],
    ["Windows user path", "C:" + "\\Users\\private-admin\\Apollo.Safe\\credential.env\n"],
    ["POSIX user path", "/home/" + "private-admin/apollo/credential.env\n"],
  ];
  for (const [name, contents] of cases) {
    await t.test(name, async (t) => {
      const fixture = await publicFixture(t);
      await fixture.put("LICENSE", contents);
      await fixture.writeManifest();
      await assert.rejects(() => buildPublicExport(fixture.options), /credential-looking|private path bytes/i);
      await assertNoUsefulOutput(fixture);
    });
  }

  await t.test("sourceRoot bytes", async (t) => {
    const fixture = await publicFixture(t);
    await fixture.put("LICENSE", `private checkout: ${fixture.sourceRoot}${path.sep}README.md\n`);
    await fixture.writeManifest();
    await assert.rejects(() => buildPublicExport(fixture.options), /private path bytes/i);
    await assertNoUsefulOutput(fixture);
  });
});

test("public export permits only exact credential placeholders and environment expansion", async (t) => {
  const fixture = await publicFixture(t);
  const safeText = [
    "The installer requests credentials but never logs them.",
    "const API_TOKEN = process.env.API_TOKEN;",
    "TOKEN=__REQUIRED_AT_RUNTIME__",
    "PASSWORD=${RUNTIME_PASSWORD}",
    "GH_TOKEN=${{ github.token }}",
    "SECRET=Environment.GetEnvironmentVariable(\"API_SECRET\")",
    "Authorization is held in memory only.",
    "Steam example: D:\\SteamLibrary\\steamapps\\workshop",
    "Runtime commands: /usr/bin/env, /bin/bash, /home/steam/run_server.sh, /opt/apollo-native.",
    "",
  ].join("\n");
  await fixture.put("LICENSE", safeText);
  await fixture.writeManifest();

  await buildPublicExport(fixture.options);
  assert.equal(await readFile(path.join(fixture.outputRoot, "LICENSE"), "utf8"), safeText);
});

test("public export rejects raw PZ binaries and non-wrapper JARs", async (t) => {
  const candidates = [
    "workshop/Contents/mods/ApolloMPSyncB42/42/projectzomboid.jar",
    "native-assist/src/main/resources/agent.jar",
    "native-assist/src/main/resources/libPZBullet64.so",
  ];
  for (const candidate of candidates) {
    await t.test(candidate, async (t) => {
      const fixture = await publicFixture(t);
      await fixture.put(candidate, Buffer.from([0x50, 0x4b, 0x03, 0x04]));
      await fixture.writeManifest();
      await assert.rejects(() => buildPublicExport(fixture.options), /forbidden payload|unallowlisted/i);
      await assertNoUsefulOutput(fixture);
    });
  }
});

test("public export rejects output/source overlap without deleting source", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("README.md", "# keep me\n");
  await fixture.writeManifest();

  await assert.rejects(() => buildPublicExport({ ...fixture.options, outputRoot: fixture.sourceRoot }), /source.*output.*collision/i);
  assert.equal(await readFile(path.join(fixture.sourceRoot, "README.md"), "utf8"), "# keep me\n");
});

test("public export rejects output inside a governed source tree", async (t) => {
  const fixture = await publicFixture(t);
  await fixture.put("workshop/Contents/mods/ApolloMPSyncB42/42/mod.info", "id=ApolloMPSyncB42\n");
  await fixture.writeManifest();
  const nestedOutput = path.join(fixture.sourceRoot, "workshop", "Contents", "generated-export");

  await assert.rejects(() => buildPublicExport({ ...fixture.options, outputRoot: nestedOutput }), /source.*output.*collision/i);
  await assert.rejects(readdir(nestedOutput));
});

test("public export validates overlap before touching source or manifest bytes", async (t) => {
  const cases = [
    ["listed-source ancestor deploy", (fixture) => path.join(fixture.sourceRoot, "deploy")],
    ["governed/listed ancestor workshop", (fixture) => path.join(fixture.sourceRoot, "workshop")],
    ["manifest parent", (fixture) => path.dirname(fixture.manifestPath)],
    ["manifest equal", (fixture) => fixture.manifestPath],
    ["listed source equal", (fixture) => path.join(fixture.sourceRoot, "LICENSE")],
  ];

  for (const [name, outputFor] of cases) {
    await t.test(name, async (t) => {
      const fixture = await publicFixture(t);
      await fixture.put("LICENSE", "MIT source bytes stay intact\n");
      await fixture.put("deploy/apollo-native.env.example", "APOLLO_NATIVE_ASSIST=off\n");
      await fixture.put("workshop/preview.png", Buffer.from([0x89, 0x50, 0x4e, 0x47]));
      await fixture.writeManifest();
      const fixtureRoot = path.dirname(fixture.sourceRoot);
      const before = await treeHashes(fixtureRoot);

      await assert.rejects(
        () => buildPublicExport({ ...fixture.options, outputRoot: outputFor(fixture) }),
        /source.*output.*collision|output.*overlap/i,
      );
      assert.deepEqual(await treeHashes(fixtureRoot), before);
    });
  }
});

test("public export rejects non-array and invalid JSON manifests with zero output", async (t) => {
  for (const manifestText of ["{}\n", "{\n"]) {
    await t.test(JSON.stringify(manifestText), async (t) => {
      const fixture = await publicFixture(t);
      await writeFile(fixture.manifestPath, manifestText);
      await assert.rejects(() => buildPublicExport(fixture.options), /manifest|JSON/i);
      await assertNoUsefulOutput(fixture);
    });
  }
});
