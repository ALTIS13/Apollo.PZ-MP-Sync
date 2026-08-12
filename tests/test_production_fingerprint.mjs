import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  canonicalFingerprintIdentity,
  parseFingerprintProperties,
  serializeFingerprintProperties,
} from "../scripts/fingerprint-properties.mjs";

const root = path.resolve(import.meta.dirname, "..");
const fingerprintPath = path.join(
  root, "deploy", "fingerprints", "pz-42.20.2-build-24574884.properties",
);
const nativeManifestPath = path.join(
  root, "deploy", "fingerprints", "pz-42.20.2-build-24574884.native-libraries.sha256",
);
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

test("production fingerprint is canonical and pins the exact captured tuple", async () => {
  const bytes = await readFile(fingerprintPath);
  const parsed = parseFingerprintProperties(bytes);

  assert.equal(serializeFingerprintProperties(parsed), bytes.toString("utf8"));
  assert.equal(canonicalFingerprintIdentity(bytes), parsed.fingerprintSha256);
  assert.equal(parsed.appId, "380870");
  assert.equal(parsed.buildId, "24574884");
  assert.equal(parsed.gameVersionRevision, "42.20.2");
  assert.equal(parsed.serverJarSha256,
    "09a80a46e4febe9b436c0f4ec539bdfe9e9113b673eeaf8db22415ac34bef416");
  assert.equal(parsed.imageReference,
    "ghcr.io/renegade-master/zomboid-dedicated-server@sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951");
  assert.deepEqual(parsed.originalEntrypoint, [
    {
      path: "/bin/bash", kind: "file", mode: "0755",
      sha256: "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58",
    },
    {
      path: "/home/steam/run_server.sh", kind: "file", mode: "0755",
      sha256: "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8",
    },
  ]);
  assert.equal(parsed.imageCmd, "null");
  assert.equal(parsed.runtimeLockMode, "none-captured");
  assert.equal(parsed.nativeLibrarySha256, sha256(await readFile(nativeManifestPath)));
  assert.equal(parsed.jvmFeature, 25);
  assert.equal(parsed.os, "linux");
  assert.equal(parsed.arch, "amd64");
  assert.equal(parsed.workshopId, "3780069702");
  assert.equal(parsed.luaModId, "ApolloMPSyncB42");
  assert.equal(parsed.bridgeProtocol, "1");
});

test("production fingerprint declares only the closed exact adapter grammar", async () => {
  const parsed = parseFingerprintProperties(await readFile(fingerprintPath));
  const keys = Object.keys(parsed.methodDescriptors);

  assert.equal(parsed.methodDescriptors["RUNTIME_ADAPTER|PZ_42_20_2"], "1");
  assert.equal(keys.filter((key) => key.startsWith("ADAPTER_HOOK|")).length, 6);
  assert.equal(keys.filter((key) => key.startsWith("ADAPTER_VARIANT|")).length, 3);
  assert.equal(keys.filter((key) => key.startsWith("ADAPTER_CAPABILITY|")).length, 8);
  assert.equal(keys.filter((key) => key.startsWith("ADAPTER_PROOF|")).length, 1);
  assert.equal(keys.filter((key) => key.startsWith("RUNTIME_BINDINGS_FACTORY|")).length, 1);
  assert.ok(keys.every((key) => /^(?:RUNTIME_ADAPTER|RUNTIME_BINDINGS_FACTORY|ADAPTER_(?:HOOK|VARIANT|VARIANT_PROOF|CHAIN|CAPABILITY|SUPPORT|SUPPORT_PROOF|PROOF))\|/.test(key)));
  assert.match(parsed.classHashes["se.krka.kahlua.vm.JavaFunction"], /^[0-9a-f]{64}$/);
  assert.match(parsed.classHashes["se.krka.kahlua.vm.LuaCallFrame"], /^[0-9a-f]{64}$/);
  assert.equal(parsed.methodDescriptors[
    "ADAPTER_CAPABILITY|LUA_JAVA_FUNCTION_CALL|VIRTUAL|401|se.krka.kahlua.vm.JavaFunction#call"
  ], "(Lse/krka/kahlua/vm/LuaCallFrame;I)I");
  assert.equal(parsed.methodDescriptors[
    "ADAPTER_CAPABILITY|LUA_CALL_FRAME_GET|VIRTUAL|11|se.krka.kahlua.vm.LuaCallFrame#get"
  ], "(I)Ljava/lang/Object;");
  assert.equal(parsed.methodDescriptors[
    "ADAPTER_CAPABILITY|LUA_CALL_FRAME_PUSH|VIRTUAL|1|se.krka.kahlua.vm.LuaCallFrame#push"
  ], "(Ljava/lang/Object;)I");
});

test("the exact wrapper validator accepts the same production bytes and identity", async () => {
  const wrapper = await readFile(path.join(root, "deploy", "apollo-native-entrypoint.sh"), "utf8");
  const start = wrapper.indexOf("fingerprint_value() {");
  const end = wrapper.indexOf("\nvalidate_native_manifest() {", start);
  assert.ok(start >= 0 && end > start, "wrapper fingerprint validator source is present");
  const directory = await mkdtemp(path.join(os.tmpdir(), "apollo-a7-wrapper-parity-"));
  try {
    const harness = path.join(directory, "validate.sh");
    await writeFile(harness, [
      "#!/bin/bash", "set -euo pipefail",
      `APOLLO_FINGERPRINT_FILE=${JSON.stringify(fingerprintPath.replaceAll("\\", "/"))}`,
      wrapper.slice(start, end),
      "validate_fingerprint",
      "printf '%s\\n' \"$APOLLO_VERIFIED_FINGERPRINT_SHA256\"",
      "",
    ].join("\n"));
    const candidates = process.platform === "win32"
      ? ["C:/Program Files/Git/bin/bash.exe", "bash.exe", "bash"] : ["bash"];
    const bash = candidates.find((candidate) =>
      spawnSync(candidate, ["--version"], { encoding: "utf8" }).status === 0);
    assert.ok(bash, "Bash is required for wrapper parity");
    const result = spawnSync(bash, [harness.replaceAll("\\", "/")], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.trim(), canonicalFingerprintIdentity(await readFile(fingerprintPath)));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
