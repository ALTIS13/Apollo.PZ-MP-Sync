import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  canonicalFingerprintIdentity,
  parseFingerprintProperties,
  serializeFingerprintProperties,
} from "../scripts/fingerprint-properties.mjs";

const root = path.resolve(import.meta.dirname, "..");
const goldenPath = path.join(
  root,
  "tests", "fixtures", "fingerprint", "exact-fingerprint.properties",
);
const hostileMembersPath = path.join(path.dirname(goldenPath), "hostile-member-suffixes.tsv");

async function hostileMembers() {
  return (await readFile(hostileMembersPath, "utf8")).trimEnd().split("\n")
    .map((line) => line.split("\t"))
    .map(([label, suffix]) => [label, suffix === "<empty>" ? "" : suffix]);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

test("golden fingerprint identity is the hash of canonical bytes with only its identity line omitted", async () => {
  const bytes = await readFile(goldenPath);
  const text = bytes.toString("utf8");
  const fingerprint = parseFingerprintProperties(text);
  const omitted = Buffer.from(
    text.split("\n").filter((line) => !line.startsWith("fingerprintSha256=")).join("\n"),
    "utf8",
  );

  assert.equal(fingerprint.fingerprintSha256, sha256(omitted));
  assert.equal(canonicalFingerprintIdentity(text), fingerprint.fingerprintSha256);
});

test("serializer derives the identity and rejects a stale supplied identity", async () => {
  const original = parseFingerprintProperties(await readFile(goldenPath, "utf8"));
  const withoutIdentity = { ...original };
  delete withoutIdentity.fingerprintSha256;
  const emitted = serializeFingerprintProperties(withoutIdentity);
  assert.equal(emitted, await readFile(goldenPath, "utf8"));

  assert.throws(
    () => serializeFingerprintProperties({ ...original, fingerprintSha256: "0".repeat(64) }),
    /stale fingerprintSha256/i,
  );
});

test("runtime image launcher and lock tuple is identity-covered", async () => {
  const fixture = parseFingerprintProperties(await readFile(goldenPath, "utf8"));
  delete fixture.fingerprintSha256;
  Object.assign(fixture, {
    imageReference: `fixture.invalid/apollo/pz@sha256:${"1".repeat(64)}`,
    originalEntrypoint: [{
      path: "/fixture/entrypoint", kind: "file", mode: "0755", sha256: "2".repeat(64),
    }],
    imageCmd: "null",
    runtimeLockMode: "none-captured",
  });
  const baseline = serializeFingerprintProperties(fixture);
  const baselineIdentity = canonicalFingerprintIdentity(baseline);
  const mutations = [
    (value) => { value.imageReference = value.imageReference.replace("1", "3"); },
    (value) => { value.originalEntrypoint[0].path = "/fixture/other"; },
    (value) => { value.originalEntrypoint[0].mode = "0700"; },
    (value) => { value.originalEntrypoint[0].sha256 = "4".repeat(64); },
  ];
  for (const mutate of mutations) {
    const changed = structuredClone(fixture);
    mutate(changed);
    assert.notEqual(canonicalFingerprintIdentity(serializeFingerprintProperties(changed)),
      baselineIdentity);
  }
  for (const mutate of [
    (value) => { value.originalEntrypoint[0].kind = "other"; },
    (value) => { value.imageCmd = "empty"; },
    (value) => { value.runtimeLockMode = "active"; },
  ]) {
    const changed = structuredClone(fixture);
    mutate(changed);
    assert.throws(() => serializeFingerprintProperties(changed));
  }
});

test("runtime tuple fields are required and canonically ordered", async () => {
  const fixture = parseFingerprintProperties(await readFile(goldenPath, "utf8"));
  delete fixture.fingerprintSha256;
  Object.assign(fixture, {
    imageReference: `fixture.invalid/apollo/pz@sha256:${"1".repeat(64)}`,
    originalEntrypoint: [{
      path: "/fixture/entrypoint", kind: "file", mode: "0755", sha256: "2".repeat(64),
    }],
    imageCmd: "null",
    runtimeLockMode: "none-captured",
  });
  const canonical = serializeFingerprintProperties(fixture);
  assert.throws(() => parseFingerprintProperties(
    canonical.replace(/^runtimeLockMode=.*\n/m, "")), /runtimeLockMode/i);
  assert.throws(() => parseFingerprintProperties(canonical.replace(
    /^(originalEntrypoint\.0\.path=.*)\n(originalEntrypoint\.0\.kind=.*)$/m, "$2\n$1")),
  /canonical order/i);
});

test("serializer orders full encoded property keys by ASCII bytes", async () => {
  const fingerprint = parseFingerprintProperties(await readFile(goldenPath, "utf8"));
  delete fingerprint.fingerprintSha256;
  fingerprint.classHashes = {
    "a#x": "a".repeat(64),
    "a$b#y": "b".repeat(64),
  };
  fingerprint.methodDescriptors = {
    "a#x": "([Ljava/lang/String;)V",
    "a$b#y": "(La/b$C;)V",
  };
  const serialized = serializeFingerprintProperties(fingerprint);
  assert.ok(serialized.indexOf("classHashes.a$b%23y=") < serialized.indexOf("classHashes.a%23x="));
  assert.ok(serialized.indexOf("methodDescriptors.a$b%23y=") < serialized.indexOf("methodDescriptors.a%23x="));
  assert.deepEqual(parseFingerprintProperties(serialized).classHashes, fingerprint.classHashes);
});

for (const [label, mutate] of [
  ["CRLF", (value) => value.replaceAll("\n", "\r\n")],
  ["BOM", (value) => `\ufeff${value}`],
  ["NUL", (value) => value.replace("appId=", "appId=\0")],
  ["comment", (value) => `# forbidden\n${value}`],
  ["blank line", (value) => value.replace("buildId=", "\nbuildId=")],
  ["reordering", (value) => value.replace("appId=380870\nbuildId=24574884", "buildId=24574884\nappId=380870")],
  ["duplicate", (value) => value.replace("buildId=24574884", "buildId=24574884\nbuildId=24574884")],
  ["unknown key", (value) => value.replace("bridgeProtocol=1", "unknown=1\nbridgeProtocol=1")],
  ["key whitespace", (value) => value.replace("appId=", "appId =")],
  ["value whitespace", (value) => value.replace("appId=380870", "appId= 380870")],
  ["missing final newline", (value) => value.slice(0, -1)],
  ["stale identity", (value) => value.replace(/^fingerprintSha256=.*$/m, `fingerprintSha256=${"0".repeat(64)}`)],
]) {
  test(`strict fingerprint parser rejects ${label}`, async () => {
    const golden = await readFile(goldenPath, "utf8");
    assert.throws(() => parseFingerprintProperties(mutate(golden)));
  });
}

test("strict fingerprint parser rejects malformed UTF-8 bytes", () => {
  assert.throws(() => parseFingerprintProperties(Buffer.from([0xc3, 0x28])), /strict UTF-8/i);
});

for (const [label, suffix] of await hostileMembers()) {
  test(`strict fingerprint grammar rejects ${label}`, async () => {
    const golden = await readFile(goldenPath, "utf8");
    const changed = golden.replace(/^classHashes\..*$/m,
      `classHashes.${suffix}=${"a".repeat(64)}`);
    assert.throws(() => parseFingerprintProperties(changed));
  });
}

test("serializer rejects ambiguous percent and non-ASCII logical member names", async () => {
  const fingerprint = parseFingerprintProperties(await readFile(goldenPath, "utf8"));
  delete fingerprint.fingerprintSha256;
  fingerprint.classHashes = { "a%23x": "a".repeat(64) };
  assert.throws(() => serializeFingerprintProperties(fingerprint), /member name/i);
  fingerprint.classHashes = { "aж": "a".repeat(64) };
  assert.throws(() => serializeFingerprintProperties(fingerprint), /member name/i);
});
