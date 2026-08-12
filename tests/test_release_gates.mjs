import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  parseFingerprintProperties,
  serializeFingerprintProperties,
} from "../scripts/fingerprint-properties.mjs";

const projectRoot = path.resolve(import.meta.dirname, "..");
const releaseScript = path.join(projectRoot, "scripts", "check-release.mjs");
const fingerprintFormatModule = pathToFileURL(
  path.join(projectRoot, "scripts", "fingerprint-properties.mjs"),
).href;
const exactFingerprintFixture = path.join(
  projectRoot,
  "tests", "fixtures", "fingerprint", "exact-fingerprint.properties",
);
const productionFingerprint = path.join(
  projectRoot, "deploy", "fingerprints", "pz-42.20.2-build-24574884.properties",
);

async function put(root, relativePath, contents) {
  const destination = path.join(root, ...relativePath.split("/"));
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, contents);
}

function run(root, ...args) {
  const result = spawnSync(process.execPath, [releaseScript, root, ...args], {
    cwd: projectRoot,
    encoding: "utf8",
  });
  return { code: result.status, output: `${result.stdout}${result.stderr}` };
}

async function fixture() {
  const root = await mkdtemp(path.join(tmpdir(), "apollo-release-"));
  const modRoot = "workshop/Contents/mods/ApolloMPSyncB42";
  const modInfo = [
    "name=Apollo MP Sync [B42.20.2]",
    "id=ApolloMPSyncB42",
    "description=Stable Workshop description.",
    "pzversion=42",
    "versionMin=42.20.2",
    "versionMax=42.20.2",
    "modversion=0.2.0",
    "",
  ].join("\n");
  await put(root, "package.json", JSON.stringify({ name: "apollo-mp-sync-b42", version: "0.2.0" }));
  await put(root, "package-lock.json", JSON.stringify({
    name: "apollo-mp-sync-b42",
    version: "0.2.0",
    lockfileVersion: 3,
    packages: { "": { name: "apollo-mp-sync-b42", version: "0.2.0" } },
  }));
  await put(root, `${modRoot}/mod.info`, modInfo);
  await put(root, `${modRoot}/42/mod.info`, modInfo);
  await put(root, `${modRoot}/42/media/lua/shared/ApolloMPSync/Protocol.lua`, [
    "local Protocol = {",
    "    MOD_ID = \"ApolloMPSyncB42\",",
    "    WORKSHOP_ID = \"3780069702\",",
    "    BRIDGE_PROTOCOL = \"1\",",
    "}",
    "return Protocol",
    "",
  ].join("\n"));
  await put(root, "native-assist/build.gradle.kts", "version = \"0.2.0\"\n");
  await put(root, "native-assist/src/main/java/ru/apollot/pzsync/gate/CompatibilityGate.java", [
    "final class CompatibilityGate {",
    "  static final String APP_ID = \"380870\";",
    "  static final String BUILD_ID = \"24574884\";",
    "  static final String GAME_VERSION_REVISION = \"42.20.2\";",
    "  static final String WORKSHOP_ID = \"3780069702\";",
    "  static final String LUA_MOD_ID = \"ApolloMPSyncB42\";",
    "  static final String BRIDGE_PROTOCOL = \"1\";",
    "}",
    "",
  ].join("\n"));
  await put(root, "native-assist/src/main/java/ru/apollot/pzsync/SafePolicy.java", "final class SafePolicy {}\n");
  return root;
}

function exactFingerprint(agentSha256, classHash = "a".repeat(64)) {
  return {
    appId: "380870",
    buildId: "24574884",
    gameVersionRevision: "42.20.2",
    serverJarSha256: "b".repeat(64),
    nativeLibrarySha256: "c".repeat(64),
    agentSha256,
    imageReference: `fixture.invalid/apollo/native-assist@sha256:${"1".repeat(64)}`,
    originalEntrypoint: [{
      path: "/fixture/entrypoint", kind: "file", mode: "0755", sha256: "2".repeat(64),
    }],
    imageCmd: "null",
    runtimeLockMode: "none-captured",
    jvmFeature: 25,
    os: "linux",
    arch: "amd64",
    classHashes: {
      "a$b#y": "b".repeat(64),
      "a#x": "a".repeat(64),
      "zombie.network.PlayerPacket": classHash,
    },
    methodDescriptors: {
      "a$b#y": "(La/b$C;)V",
      "a#x": "([Ljava/lang/String;)V",
      "zombie.network.PlayerPacket#processServer": "(Ljava/lang/Object;)V",
    },
    workshopId: "3780069702",
    luaModId: "ApolloMPSyncB42",
    bridgeProtocol: "1",
  };
}

async function propertiesFingerprintForAgent(agentSha256) {
  const fingerprint = parseFingerprintProperties(await readFile(exactFingerprintFixture, "utf8"));
  fingerprint.agentSha256 = agentSha256;
  delete fingerprint.fingerprintSha256;
  return serializeFingerprintProperties(fingerprint);
}

async function productionPropertiesForAgent(agentSha256, mutate = () => {}) {
  const fingerprint = parseFingerprintProperties(await readFile(productionFingerprint, "utf8"));
  fingerprint.agentSha256 = agentSha256;
  mutate(fingerprint);
  delete fingerprint.fingerprintSha256;
  return serializeFingerprintProperties(fingerprint);
}

test("canonical fingerprint serializer emits the exact Java-loader fixture", async () => {
  const { serializeFingerprintProperties } = await import(fingerprintFormatModule);
  const fingerprint = exactFingerprint(
    "e0071a63e4a8bf260488796b60d4e6ed72b336250458695b2ac057a2b14cfe0a",
  );
  const emitted = serializeFingerprintProperties(fingerprint);

  assert.equal(emitted, await readFile(exactFingerprintFixture, "utf8"));
});

for (const variant of ["25e0", "25.0", " 25", "25 ", "+25", "025"]) {
  test(`native release gate rejects non-canonical jvmFeature ${JSON.stringify(variant)}`, async () => {
    const root = await fixture();
    try {
      const agentBytes = "same-build";
      const agentHash = createHash("sha256").update(agentBytes).digest("hex");
      await put(root, "first.jar", agentBytes);
      await put(root, "second.jar", agentBytes);
      const fingerprint = (await propertiesFingerprintForAgent(agentHash))
        .replace("jvmFeature=25", `jvmFeature=${variant}`);
      await put(root, "fingerprint.properties", fingerprint);
      const result = run(
        root,
        "--native-fingerprint", "fingerprint.properties",
        "--agent-jar-a", "first.jar",
        "--agent-jar-b", "second.jar",
      );
      assert.notEqual(result.code, 0, result.output);
      assert.match(result.output, /jvmFeature must use canonical decimal 25/i);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
}

test("canonical fingerprint serializer rejects a non-numeric jvmFeature representation", async () => {
  const { serializeFingerprintProperties } = await import(fingerprintFormatModule);
  const fingerprint = exactFingerprint(
    "6028de3730797cb1121f981550a1d821377773c00913bb00c58df58d3ba8dd24",
  );
  fingerprint.jvmFeature = "025";

  assert.throws(
    () => serializeFingerprintProperties(fingerprint),
    /fingerprint jvmFeature must be the integer 25/i,
  );
});

async function expectRejected(mutate, diagnostic, args = []) {
  const root = await fixture();
  try {
    await mutate(root);
    const result = run(root, ...args);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, diagnostic);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test("release gate accepts an exact Workshop-only 0.2.0 fixture", async () => {
  const root = await fixture();
  try {
    const result = run(root);
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /PASS release authority gates.*Workshop-only/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("release gate rejects every JAR below Workshop content", async () => {
  await expectRejected(
    (root) => put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/apollo-agent.jar", "not-a-jar"),
    /JAR is forbidden in Workshop.*apollo-agent\.jar/i,
  );
});

test("Workshop JAR exclusion cannot be bypassed with an excluded directory name", async () => {
  await expectRejected(
    (root) => put(root, "workshop/node_modules/hidden-agent.jar", "not-a-jar"),
    /JAR is forbidden in Workshop.*hidden-agent\.jar/i,
  );
});

for (const [relativePath, diagnostic] of [
  ["workshop/hidden/server.class", /forbidden server\/private Workshop artifact.*server\.class/i],
  ["workshop/.native/libpzsync.so.1", /forbidden server\/private Workshop artifact.*libpzsync\.so\.1/i],
  ["workshop/config/runtime-fingerprint.properties", /forbidden server\/private Workshop artifact.*runtime-fingerprint\.properties/i],
  ["workshop/fingerprints/pz-42.20.2-build-24574884.json", /forbidden server\/private Workshop artifact.*fingerprints.*pz-42\.20\.2/i],
  ["workshop/private/server-private.key", /forbidden server\/private Workshop artifact.*server-private\.key/i],
  ["workshop/deploy/credentials.env", /forbidden server\/private Workshop artifact.*credentials\.env/i],
  ["workshop/.ops-private/GameNode.json", /forbidden server\/private Workshop artifact.*\.ops-private.*GameNode\.json/i],
]) {
  test(`release gate rejects private/server payload ${relativePath}`, async () => {
    await expectRejected((root) => put(root, relativePath, "private payload"), diagnostic);
  });
}

for (const relativePath of [
  "workshop/deploy/compose.native-assist.override.yaml",
  "workshop/deploy/apollo-native-entrypoint.sh",
  "workshop/deploy/apollo-native.env.example",
  "workshop/deploy/README.md",
  "workshop/.deployment/renamed.dat",
  "workshop/ops/docker-compose.stealth.yaml",
  "workshop/content/compose-backup.yml",
  "workshop/scripts/run-entrypoint.bash",
  "workshop/config/apollo.env.local",
  "workshop/config/server-native-runtime.toml",
  "workshop/scripts/bootstrap.ps1",
]) {
  test(`release gate rejects deployment payload ${relativePath}`, async () => {
    await expectRejected(
      (root) => put(root, relativePath, "deployment payload"),
      new RegExp(`forbidden server/private Workshop artifact.*${path.basename(relativePath).replaceAll(".", "\\.")}`, "i"),
    );
  });
}

for (const relativePath of [
  "workshop/config/server-native.yaml",
  "workshop/ops/entry-point.yaml",
  "workshop/deploy-v2/renamed.dat",
  "workshop/apollo-native-agent.zip",
  "workshop/server-native.txt",
  "workshop/misc/harmless.txt",
  "workshop/node_modules/ordinary.txt",
  "workshop/docs/guide.md",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/archive.tar.gz",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/renamed.dat",
]) {
  test(`strict Workshop structure rejects ${relativePath}`, async () => {
    await expectRejected(
      (root) => put(root, relativePath, "unexpected payload"),
      /outside canonical ApolloMPSyncB42 Workshop structure/i,
    );
  });
}

test("strict Workshop structure rejects an empty unexpected directory envelope", async () => {
  await expectRejected(
    (root) => mkdir(path.join(root, "workshop", "deploy-v2", "empty"), { recursive: true }),
    /Workshop directory is outside canonical ApolloMPSyncB42 structure.*deploy-v2/i,
  );
});

test("strict Workshop structure permits new Lua and translations only inside canonical paths", async () => {
  const root = await fixture();
  try {
    const modRoot = "workshop/Contents/mods/ApolloMPSyncB42";
    await put(root, `${modRoot}/poster.png`, "fixture poster");
    await put(root, `${modRoot}/42/poster.png`, "fixture poster");
    await put(root, "workshop/preview.png", "fixture preview");
    await put(root, `${modRoot}/42/media/sandbox-options.txt`, "VERSION = 1,\n");
    await put(root, `${modRoot}/42/media/lua/client/ApolloMPSync/FutureClient.lua`, "return {}\n");
    await put(root, `${modRoot}/42/media/lua/server/ApolloMPSync/FutureServer.lua`, "return {}\n");
    await put(root, `${modRoot}/42/media/lua/shared/ApolloMPSync/FutureShared.lua`, "return {}\n");
    await put(root, `${modRoot}/42/media/lua/shared/Translate/EN/Future_EN.txt`, "Future_EN = {}\n");
    await put(root, `${modRoot}/42/media/lua/shared/Translate/RU/Future_RU.txt`, "Future_RU = {}\n");
    const result = run(root);
    assert.equal(result.code, 0, result.output);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("authority scan cannot be bypassed with node_modules below production Java", async () => {
  await expectRejected(
    (root) => put(
      root,
      "native-assist/src/main/java/ru/apollot/pzsync/node_modules/Bad.java",
      "final class Bad { void mutate(Object value) { value.setWorldTransform(null); } }\n",
    ),
    /forbidden transform mutator.*node_modules.*Bad\.java/i,
  );
});

test("release gate rejects a real Workshop link or Windows junction", async (t) => {
  const root = await fixture();
  const outside = await mkdtemp(path.join(tmpdir(), "apollo-release-link-outside-"));
  try {
    await put(outside, "payload.txt", "outside");
    const linkPath = path.join(root, "workshop", "linked");
    const linkType = process.platform === "win32" ? "junction" : "dir";
    await symlink(outside, linkPath, linkType);
    t.diagnostic(process.platform === "win32"
      ? "executed Windows reparse/junction regression"
      : "executed POSIX directory-symlink regression");
    const result = run(root);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /unsupported filesystem entry.*linked|unsupported Workshop entry.*linked/i);
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});

test("authority scan rejects a link hidden below production node_modules", async (t) => {
  const root = await fixture();
  const outside = await mkdtemp(path.join(tmpdir(), "apollo-authority-link-outside-"));
  try {
    await put(outside, "Bad.java", "final class Bad { void mutate(Object p) { p.applyDamage(1); } }\n");
    const parent = path.join(root, "native-assist", "src", "main", "java", "node_modules");
    await mkdir(parent, { recursive: true });
    const linkType = process.platform === "win32" ? "junction" : "dir";
    await symlink(outside, path.join(parent, "linked"), linkType);
    t.diagnostic(process.platform === "win32"
      ? "executed Windows authority reparse/junction regression"
      : "executed POSIX authority directory-symlink regression");
    const result = run(root);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /unsupported filesystem entry.*node_modules.*linked/i);
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});

for (const [label, relativePath, source, diagnostic] of [
  [
    "coordinate mutators",
    "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/Bad.lua",
    "player:setX(12)\n",
    /forbidden coordinate mutator.*setX/i,
  ],
  [
    "transform mutators",
    "native-assist/src/main/java/ru/apollot/pzsync/BadTransform.java",
    "final class BadTransform { void bad(Object v) { v.setWorldTransform(null); } }\n",
    /forbidden transform mutator.*setWorldTransform/i,
  ],
  [
    "direct damage mutators",
    "native-assist/src/main/java/ru/apollot/pzsync/BadDamage.java",
    "final class BadDamage { void bad(Object p) { p.applyDamage(1); } }\n",
    /forbidden direct damage mutator.*applyDamage/i,
  ],
]) {
  test(`release gate rejects ${label}`, async () => {
    await expectRejected((root) => put(root, relativePath, source), diagnostic);
  });
}

test("native release gate rejects a missing exact runtime fingerprint", async () => {
  await expectRejected(
    async () => {},
    /exact native fingerprint is missing/i,
    ["--native-fingerprint", "deploy/fingerprints/pz-42.20.2-build-24574884.json"],
  );
});

test("native release gate requires independent agent reproducibility evidence", async () => {
  const root = await fixture();
  try {
    const result = run(root, "--native-fingerprint", exactFingerprintFixture);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /native fingerprint validation requires two independently built agent JARs/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("native release gate rejects malformed class hashes", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    await put(root, "second.jar", agentBytes);
    const malformed = exactFingerprint(agentHash, "not-a-sha256");
    const fingerprint = serializeFingerprintProperties(malformed);
    await put(root, "fingerprint.properties", fingerprint);
    const result = run(
      root,
      "--native-fingerprint", "fingerprint.properties",
      "--agent-jar-a", "first.jar",
      "--agent-jar-b", "second.jar",
    );
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /fingerprint class hash.*lowercase SHA-256/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("native release gate rejects a blank exact member descriptor", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    await put(root, "second.jar", agentBytes);
    const fingerprint = (await propertiesFingerprintForAgent(agentHash))
      .replace("methodDescriptors.zombie.network.PlayerPacket%23processServer=(Ljava/lang/Object;)V",
        "methodDescriptors.zombie.network.PlayerPacket%23processServer=");
    await put(root, "fingerprint.properties", fingerprint);
    const result = run(
      root,
      "--native-fingerprint", "fingerprint.properties",
      "--agent-jar-a", "first.jar",
      "--agent-jar-b", "second.jar",
    );
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /fingerprint method descriptor.*must not be blank/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("native release gate accepts an exact fingerprint tied to reproducible JARs", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    await put(root, "second.jar", agentBytes);
    await put(root, "fingerprint.properties", await propertiesFingerprintForAgent(agentHash));
    const result = run(
      root,
      "--native-fingerprint", "fingerprint.properties",
      "--agent-jar-a", "first.jar",
      "--agent-jar-b", "second.jar",
    );
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /PASS release authority gates.*exact native fingerprint/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("native release gate rejects a production filename with an unpinned agent build", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-production-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    await put(root, "second.jar", agentBytes);
    const relative = "deploy/fingerprints/pz-42.20.2-build-24574884.properties";
    await put(root, relative, await productionPropertiesForAgent(agentHash));
    const result = run(root, "--native-fingerprint", relative,
      "--agent-jar-a", "first.jar", "--agent-jar-b", "second.jar");
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /production fingerprint agent SHA-256 mismatch/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("native release gate applies production pins to a renamed exact adapter", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-renamed-production-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    await put(root, "second.jar", agentBytes);
    await put(root, "renamed.properties", await productionPropertiesForAgent(agentHash));
    const result = run(root, "--native-fingerprint", "renamed.properties",
      "--agent-jar-a", "first.jar", "--agent-jar-b", "second.jar");
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /production fingerprint agent SHA-256 mismatch/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("native release gate rejects a renamed canonically re-signed production mutation", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-renamed-production-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    await put(root, "second.jar", agentBytes);
    await put(root, "renamed.properties", await productionPropertiesForAgent(agentHash, (value) => {
      value.imageReference = value.imageReference.replace("5e3479", "6e3479");
    }));
    const result = run(root, "--native-fingerprint", "renamed.properties",
      "--agent-jar-a", "first.jar", "--agent-jar-b", "second.jar");
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /production fingerprint (?:image reference|identity) mismatch/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("native production release gate rejects a canonically re-signed class mutation", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-production-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    await put(root, "second.jar", agentBytes);
    const relative = "deploy/fingerprints/pz-42.20.2-build-24574884.properties";
    await put(root, relative, await productionPropertiesForAgent(agentHash, (value) => {
      const [name] = Object.keys(value.classHashes);
      value.classHashes[name] = "0".repeat(64);
    }));
    const result = run(root, "--native-fingerprint", relative,
      "--agent-jar-a", "first.jar", "--agent-jar-b", "second.jar");
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /production fingerprint identity mismatch/i);
    assert.match(result.output, /production fingerprint transport SHA-256 mismatch/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

for (const [label, mutate, diagnostic] of [
  ["wrong server JAR", (value) => { value.serverJarSha256 = "0".repeat(64); }, /production fingerprint server JAR SHA-256 mismatch/i],
  ["wrong native manifest", (value) => { value.nativeLibrarySha256 = "0".repeat(64); }, /production fingerprint native manifest SHA-256 mismatch/i],
  ["extra runtime role", (value) => { value.methodDescriptors["UNUSED|ALIEN"] = "()V"; }, /unused\/unknown role/i],
]) {
  test(`native production release gate rejects ${label}`, async () => {
    const root = await fixture();
    try {
      const agentBytes = "same-production-build";
      const agentHash = createHash("sha256").update(agentBytes).digest("hex");
      await put(root, "first.jar", agentBytes);
      await put(root, "second.jar", agentBytes);
      const relative = "deploy/fingerprints/pz-42.20.2-build-24574884.properties";
      await put(root, relative, await productionPropertiesForAgent(agentHash, mutate));
      const result = run(root, "--native-fingerprint", relative,
        "--agent-jar-a", "first.jar", "--agent-jar-b", "second.jar");
      assert.notEqual(result.code, 0, result.output);
      assert.match(result.output, diagnostic);
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  });
}

test("native release gate rejects one missing production agent build", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-production-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    const relative = "deploy/fingerprints/pz-42.20.2-build-24574884.properties";
    await put(root, relative, await productionPropertiesForAgent(agentHash));
    const result = run(root, "--native-fingerprint", relative,
      "--agent-jar-a", "first.jar");
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /both independently built agent JAR paths are required/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("native release gate rejects a JSON fingerprint dialect", async () => {
  const root = await fixture();
  try {
    const agentBytes = "same-build";
    const agentHash = createHash("sha256").update(agentBytes).digest("hex");
    await put(root, "first.jar", agentBytes);
    await put(root, "second.jar", agentBytes);
    await put(root, "fingerprint.json", JSON.stringify(exactFingerprint(agentHash)));
    const result = run(
      root,
      "--native-fingerprint", "fingerprint.json",
      "--agent-jar-a", "first.jar",
      "--agent-jar-b", "second.jar",
    );
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /fingerprint must use canonical key=value serialization/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("release gate rejects a mismatched version tuple", async () => {
  await expectRejected(
    (root) => put(root, "native-assist/build.gradle.kts", "version = \"0.1.1\"\n"),
    /native agent version mismatch.*expected 0\.2\.0/i,
  );
});

test("release gate rejects non-reproducible agent JARs", async () => {
  const root = await fixture();
  try {
    await put(root, "first.jar", "first-build");
    await put(root, "second.jar", "different-second-build");
    const result = run(root, "--agent-jar-a", "first.jar", "--agent-jar-b", "second.jar");
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /agent JAR is not reproducible/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("release gate accepts byte-identical independently built agent JARs", async () => {
  const root = await fixture();
  try {
    await put(root, "first.jar", "same-build");
    await put(root, "second.jar", "same-build");
    const result = run(root, "--agent-jar-a", "first.jar", "--agent-jar-b", "second.jar");
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /agent SHA-256 [0-9a-f]{64}/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
