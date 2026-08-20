import { createHash, randomBytes } from "node:crypto";
import {
  constants as fsConstants,
  lstat,
  mkdir,
  open,
  readdir,
  readFile,
  rename,
  rm,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { parseFingerprintProperties } from "./fingerprint-properties.mjs";
import { isSensitiveKeyName, walkFiles } from "./package-utils.mjs";

const RELEASE_MANIFEST = "public/release/release-manifest.json";
const PRODUCTION_FINGERPRINT = "deploy/fingerprints/pz-42.20.3-build-24775771.properties";
const PRODUCTION_NATIVE_MANIFEST =
  "deploy/fingerprints/pz-42.20.3-build-24775771.native-libraries.sha256";
const HASH = /^[0-9a-f]{64}$/;
const EXPECTED_METADATA = Object.freeze({
  schemaVersion: 1,
  version: "0.2.2",
  support: Object.freeze({
    appId: "380870",
    buildId: "24775771",
    gameVersion: "42.20.3",
    javaFeature: 25,
    os: "linux",
    arch: "amd64",
  }),
  imageDigest: "sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951",
  agentSha256: "abe17cd769616c0b80d4388cf0ad945108b3fb3edf75d3313097aefb62da433b",
  fingerprint: Object.freeze({
    transportSha256: "8c982ca8b5d4aa14dd7ccf7c061df4e83df7b88a71fdaae6674e279de33bbe98",
    identitySha256: "38a321f9b4883f71b6fc94b96d24232cbbbca7f3c458a557451a4b0e9168148d",
  }),
  nativeManifestSha256: "86dcfd62671e7a8618c9bbba8433a82425b9c2e896a635c4f21aa70de17108ba",
});
const FILE_SPECS = Object.freeze([
  Object.freeze({ source: "public/docs/NATIVE_ASSIST_EN.md", path: "README_EN.md", text: true }),
  Object.freeze({ source: "public/docs/NATIVE_ASSIST_RU.md", path: "README_RU.md", text: true }),
  Object.freeze({ source: null, path: "companion/apollo-native-agent.jar", jar: true }),
  Object.freeze({
    source: "deploy/apollo-native-entrypoint.sh",
    path: "companion/apollo-native-entrypoint.sh",
    text: true,
  }),
  Object.freeze({
    source: PRODUCTION_FINGERPRINT,
    path: "companion/fingerprint.properties",
    text: true,
  }),
  Object.freeze({ source: PRODUCTION_NATIVE_MANIFEST, path: "companion/native-libraries.sha256", text: true }),
  Object.freeze({
    source: "deploy/apollo-native.env.example",
    path: "templates/apollo-native.env.example",
    text: true,
  }),
  Object.freeze({
    source: "deploy/compose.native-assist.override.yaml",
    path: "templates/compose.native-assist.override.yaml",
    text: true,
  }),
]);
const EXACT_STAGED_FILES = Object.freeze([
  ...FILE_SPECS.map((entry) => entry.path),
  "release-manifest.json",
].sort(ordinal));

function ordinal(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
  const observed = Object.keys(value).sort(ordinal);
  const wanted = [...expected].sort(ordinal);
  if (JSON.stringify(observed) !== JSON.stringify(wanted)) {
    throw new Error(`${label} has unknown or missing fields`);
  }
}

function requireEqual(label, observed, expected) {
  if (observed !== expected) {
    throw new Error(`${label} mismatch: expected ${expected}, got ${observed ?? "missing"}`);
  }
}

function strictUtf8(bytes, label) {
  let text;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch (error) {
    throw new Error(`${label} must be strict UTF-8`, { cause: error });
  }
  if (text.startsWith("\ufeff") || text.includes("\0") || text.includes("\r")) {
    throw new Error(`${label} contains forbidden BOM, NUL, or CR bytes`);
  }
  if (!text.endsWith("\n")) throw new Error(`${label} must end with LF`);
  return text;
}

function safeCredentialPlaceholder(value) {
  const trimmed = value.trim().replace(/^['"]|['"]$/g, "");
  return trimmed === "" || /^\$\{?[A-Z0-9_]+\}?$/u.test(trimmed)
    || /^__[A-Z0-9_:-]+__$/u.test(trimmed) || /^<[A-Z0-9_ -]+>$/iu.test(trimmed)
    || /^(?:change-?me|replace-?me|example|redacted|null)$/iu.test(trimmed)
    || /^process\.env\.[A-Z0-9_]+$/iu.test(trimmed)
    || /^Environment\.GetEnvironmentVariable\(["'][A-Z0-9_]+["']\)$/iu.test(trimmed);
}

const PRIVATE_TEXT_MARKERS = [".ops-" + "private", ".ops-" + "tmp", ".super" + "powers"];

function sensitiveAssignment(line) {
  const indent = line.match(/^\s*/u)[0].length;
  const declaration = line.match(
    /^\s*(?:(?:public|private|protected|internal|static|final|readonly|const|let|var)\s+)*(?:(?:String|string|char\[\])\s+)?([A-Z_][A-Z0-9_.-]*)\s*=\s*(.*?)\s*;?\s*$/iu,
  );
  const quotedMapping = line.match(
    /^\s*["']([^"']+)["']\s*[:=]\s*(.*?)\s*[,;]?\s*$/u,
  );
  const plainMapping = line.match(
    /^\s*(?:export\s+)?([A-Z_][A-Z0-9_.-]*)\s*[:=]\s*(.*?)\s*[,;]?\s*$/iu,
  );
  const match = declaration || quotedMapping || plainMapping;
  if (!match || !isSensitiveKeyName(match[1])) return undefined;
  return { indent, key: match[1], value: match[2] };
}

function commentOnly(line) {
  return /^\s*(?:#|\/\/|;)/u.test(line);
}

// Kept policy-equivalent to the reviewed public-export scanner, with the stricter
// Native Assist bundle rule that rejects every concrete Windows drive/UNC path.
function assertSafePublicText(relativePath, bytes, sourceRoot) {
  const text = strictUtf8(bytes, relativePath);
  const portableText = text.replaceAll("\\", "/").toLowerCase();
  const portableSourceRoot = path.resolve(sourceRoot).replaceAll("\\", "/").toLowerCase();
  if (portableText.includes(portableSourceRoot)
      || PRIVATE_TEXT_MARKERS.some((marker) => portableText.includes(marker))
      || /docs[\\/]evidence/iu.test(text)
      || /\b(?:[A-Z]:|\\\\\?\\[A-Z]:)[\\/][^\s"'<>|]*/iu.test(text)
      || /\\\\[^\\/\s]+[\\/][^\s"'<>|]+/u.test(text)
      || /\/(?:home\/(?!steam(?:\/|$))[^/\s]+|Users\/[^/\s]+|root)(?:\/|$)/u.test(text)) {
    throw new Error(`private path bytes in Native Assist release source: ${relativePath}`);
  }
  if (/-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/u.test(text)
      || /\b[a-z][a-z0-9+.-]*:\/\/[^\s/:@]+:[^\s/@]+@/iu.test(text)
      || /\bAuthorization\s*[:=]\s*["']?Bearer\s+[A-Za-z0-9._~+/-]{16,}/iu.test(text)
      || /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/u.test(text)
      || /\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/u.test(text)) {
    throw new Error(`credential-looking content in Native Assist release source: ${relativePath}`);
  }
  const lines = text.split("\n");
  for (let index = 0; index < lines.length; index += 1) {
    const assignment = sensitiveAssignment(lines[index]);
    if (!assignment) continue;
    if (assignment.value.trim() && !safeCredentialPlaceholder(assignment.value)) {
      throw new Error(`credential-looking content in Native Assist release source: ${relativePath}`);
    }
    if (assignment.value.trim()) continue;
    for (let continuation = index + 1; continuation < lines.length; continuation += 1) {
      const line = lines[continuation];
      if (!line.trim() || commentOnly(line)) continue;
      const indent = line.match(/^\s*/u)[0].length;
      if (indent <= assignment.indent) break;
      if (!safeCredentialPlaceholder(line.trim())) {
        throw new Error(`credential-looking content in Native Assist release source: ${relativePath}`);
      }
    }
  }
  return text;
}

function sameStat(left, right) {
  return ["dev", "ino", "mode", "nlink", "size", "birthtimeNs", "ctimeNs", "mtimeNs"]
    .every((field) => left[field] === right[field]);
}

async function requireExactComponentSpelling(absolutePath) {
  const parsed = path.parse(absolutePath);
  const parts = absolutePath.slice(parsed.root.length).split(path.sep).filter(Boolean);
  let parent = parsed.root;
  for (const part of parts) {
    const names = await readdir(parent);
    if (!names.includes(part)) throw new Error(`input path does not use exact component spelling: ${part}`);
    parent = path.join(parent, part);
    const stats = await lstat(parent, { bigint: true });
    if (stats.isSymbolicLink()) throw new Error(`input must not contain a symbolic link or junction: ${part}`);
  }
}

async function snapshotRegularFile(filePath, label) {
  const absolutePath = path.resolve(filePath);
  await requireExactComponentSpelling(absolutePath);
  const beforePath = await lstat(absolutePath, { bigint: true });
  if (!beforePath.isFile() || beforePath.isSymbolicLink() || beforePath.nlink !== 1n) {
    throw new Error(`${label} must be one regular non-link file`);
  }
  const noFollow = fsConstants.O_NOFOLLOW ?? 0;
  const handle = await open(absolutePath, fsConstants.O_RDONLY | noFollow);
  try {
    const beforeHandle = await handle.stat({ bigint: true });
    if (!beforeHandle.isFile() || beforeHandle.nlink !== 1n || !sameStat(beforePath, beforeHandle)) {
      throw new Error(`${label} changed while opening`);
    }
    const bytes = await handle.readFile();
    const afterHandle = await handle.stat({ bigint: true });
    const afterPath = await lstat(absolutePath, { bigint: true });
    if (!sameStat(beforeHandle, afterHandle) || !sameStat(afterHandle, afterPath)) {
      throw new Error(`${label} changed while reading`);
    }
    return { absolutePath, label, handle, stats: afterHandle, bytes, sha256: sha256(bytes) };
  } catch (error) {
    await handle.close();
    throw error;
  }
}

async function revalidateSnapshot(snapshot) {
  await requireExactComponentSpelling(snapshot.absolutePath);
  const handleStats = await snapshot.handle.stat({ bigint: true });
  const pathStats = await lstat(snapshot.absolutePath, { bigint: true });
  if (!sameStat(snapshot.stats, handleStats) || !sameStat(handleStats, pathStats)) {
    throw new Error(`${snapshot.label} changed after validation`);
  }
}

function validateManifest(bytes) {
  const contents = strictUtf8(bytes, "release manifest");
  let manifest;
  try {
    manifest = JSON.parse(contents);
  } catch (error) {
    throw new Error(`release manifest is invalid JSON: ${error.message}`);
  }
  exactKeys(manifest, [
    "schemaVersion", "version", "support", "imageDigest", "agentSha256",
    "fingerprint", "nativeManifestSha256", "files",
  ], "release manifest");
  exactKeys(manifest.support, [
    "appId", "buildId", "gameVersion", "javaFeature", "os", "arch",
  ], "release manifest support");
  exactKeys(manifest.fingerprint, ["transportSha256", "identitySha256"], "release manifest fingerprint");
  for (const [field, expected] of [
    ["schemaVersion", EXPECTED_METADATA.schemaVersion],
    ["version", EXPECTED_METADATA.version],
    ["imageDigest", EXPECTED_METADATA.imageDigest],
    ["agentSha256", EXPECTED_METADATA.agentSha256],
    ["nativeManifestSha256", EXPECTED_METADATA.nativeManifestSha256],
  ]) requireEqual(`release manifest ${field}`, manifest[field], expected);
  for (const [field, expected] of Object.entries(EXPECTED_METADATA.support)) {
    requireEqual(`release manifest support ${field}`, manifest.support[field], expected);
  }
  for (const [field, expected] of Object.entries(EXPECTED_METADATA.fingerprint)) {
    requireEqual(`release manifest fingerprint ${field}`, manifest.fingerprint[field], expected);
  }
  if (!Array.isArray(manifest.files) || manifest.files.length !== FILE_SPECS.length) {
    throw new Error(`release manifest files must contain exactly ${FILE_SPECS.length} entries`);
  }
  manifest.files.forEach((entry, index) => {
    exactKeys(entry, ["path", "sha256"], `release manifest file ${index}`);
    requireEqual(`release manifest file ${index} path`, entry.path, FILE_SPECS[index].path);
    if (!HASH.test(entry.sha256 ?? "")) {
      throw new Error(`release manifest file ${index} sha256 must be lowercase SHA-256`);
    }
  });
  const canonical = `${JSON.stringify(manifest, null, 2)}\n`;
  if (canonical !== contents) throw new Error("release manifest must use canonical JSON formatting");
  return { manifest, contents };
}

function validateNativeManifest(bytes) {
  const contents = strictUtf8(bytes, "native library manifest");
  const lines = contents.slice(0, -1).split("\n");
  if (!lines.length) throw new Error("native library manifest must not be empty");
  const names = new Set();
  for (const line of lines) {
    const match = line.match(/^([0-9a-f]{64})  ([A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)*)$/);
    if (!match) throw new Error("native library manifest contains a malformed entry");
    if (names.has(match[2])) throw new Error(`native library manifest contains duplicate path: ${match[2]}`);
    names.add(match[2]);
  }
}

function validateFingerprint(fingerprint, fingerprintHash, manifest) {
  requireEqual("fingerprint transportSha256", fingerprintHash, manifest.fingerprint.transportSha256);
  requireEqual("fingerprint fingerprintSha256", fingerprint.fingerprintSha256, manifest.fingerprint.identitySha256);
  for (const [field, expected] of [
    ["appId", manifest.support.appId],
    ["buildId", manifest.support.buildId],
    ["gameVersionRevision", manifest.support.gameVersion],
    ["jvmFeature", manifest.support.javaFeature],
    ["os", manifest.support.os],
    ["arch", manifest.support.arch],
  ]) requireEqual(`fingerprint ${field}`, fingerprint[field], expected);
  const separator = fingerprint.imageReference.lastIndexOf("@");
  requireEqual("fingerprint image digest", fingerprint.imageReference.slice(separator + 1), manifest.imageDigest);
  requireEqual("fingerprint agentSha256", fingerprint.agentSha256, manifest.agentSha256);
  requireEqual(
    "fingerprint nativeLibrarySha256",
    fingerprint.nativeLibrarySha256,
    manifest.nativeManifestSha256,
  );
}

function isWithin(parent, child) {
  const relative = path.relative(parent, child);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative));
}

function assertOutputDoesNotOverlap(stagingPath, snapshots) {
  for (const snapshot of snapshots) {
    if (isWithin(stagingPath, snapshot.absolutePath) || isWithin(snapshot.absolutePath, stagingPath)) {
      throw new Error(`output staging path overlaps input: ${snapshot.label}`);
    }
  }
}

async function writeExclusive(filePath, bytes) {
  await mkdir(path.dirname(filePath), { recursive: true });
  const handle = await open(filePath, "wx");
  try {
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

async function prepareOutputRoot(absolutePath) {
  const parsed = path.parse(absolutePath);
  const parts = absolutePath.slice(parsed.root.length).split(path.sep).filter(Boolean);
  let parent = parsed.root;
  for (const part of parts) {
    const current = path.join(parent, part);
    let stats;
    const names = await readdir(parent);
    if (names.includes(part)) {
      stats = await lstat(current);
    } else {
      if (names.some((name) => name.toLowerCase() === part.toLowerCase())) {
        throw new Error(`output path does not use exact component spelling: ${part}`);
      }
      try {
        await mkdir(current, { recursive: false });
      } catch (mkdirError) {
        if (mkdirError.code !== "EEXIST") throw mkdirError;
        const racedNames = await readdir(parent);
        if (!racedNames.includes(part)) {
          throw new Error(`output path does not use exact component spelling: ${part}`);
        }
      }
      stats = await lstat(current);
    }
    if (stats.isSymbolicLink()) {
      throw new Error(`output path contains a symbolic link or junction: ${part}`);
    }
    if (!stats.isDirectory()) throw new Error(`output path component is not a directory: ${part}`);
    parent = current;
  }
}

async function verifyStaging(stagingRoot, manifestBytes, manifest) {
  const files = await walkFiles(stagingRoot);
  if (JSON.stringify(files) !== JSON.stringify(EXACT_STAGED_FILES)) {
    throw new Error("staged release tree is not the exact nine-file closure");
  }
  for (const entry of manifest.files) {
    const bytes = await readFile(path.join(stagingRoot, ...entry.path.split("/")));
    requireEqual(`staged file ${entry.path} sha256`, sha256(bytes), entry.sha256);
  }
  const writtenManifest = await readFile(path.join(stagingRoot, "release-manifest.json"));
  if (!writtenManifest.equals(manifestBytes)) throw new Error("staged release manifest changed while writing");
}

async function publishStaging(outputRoot, snapshots, files, manifestBytes, manifest) {
  const resolvedOutputRoot = path.resolve(outputRoot);
  const stagingPath = path.join(resolvedOutputRoot, "staging");
  assertOutputDoesNotOverlap(stagingPath, snapshots);
  await Promise.all(snapshots.map(revalidateSnapshot));
  await Promise.all(snapshots.map((snapshot) => snapshot.handle.close()));

  await prepareOutputRoot(resolvedOutputRoot);
  const nonce = `${process.pid}-${randomBytes(8).toString("hex")}`;
  const temporaryPath = path.join(resolvedOutputRoot, `.staging-${nonce}`);
  const backupPath = path.join(resolvedOutputRoot, `.previous-${nonce}`);
  if (!isWithin(resolvedOutputRoot, temporaryPath) || !isWithin(resolvedOutputRoot, backupPath)) {
    throw new Error("internal staging paths escaped output root");
  }
  let movedPrevious = false;
  let published = false;
  try {
    await mkdir(temporaryPath, { recursive: false });
    for (const file of files) {
      await writeExclusive(path.join(temporaryPath, ...file.path.split("/")), file.bytes);
    }
    await writeExclusive(path.join(temporaryPath, "release-manifest.json"), manifestBytes);
    await verifyStaging(temporaryPath, manifestBytes, manifest);

    try {
      const existing = await lstat(stagingPath);
      if (!existing.isDirectory() || existing.isSymbolicLink()) {
        throw new Error("existing staging output must be a regular directory");
      }
      await rename(stagingPath, backupPath);
      movedPrevious = true;
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
    await rename(temporaryPath, stagingPath);
    published = true;
    if (movedPrevious) await rm(backupPath, { recursive: true, force: true });
    return stagingPath;
  } catch (error) {
    if (!published && movedPrevious) {
      try {
        await rename(backupPath, stagingPath);
        movedPrevious = false;
      } catch (restoreError) {
        throw new AggregateError([error, restoreError], "failed to publish and restore Native Assist staging");
      }
    }
    throw error;
  } finally {
    if (!published) await rm(temporaryPath, { recursive: true, force: true });
    if (!movedPrevious) await rm(backupPath, { recursive: true, force: true });
  }
}

export async function stageNativeRelease({ projectRoot, agentJar, outputRoot }) {
  if (typeof projectRoot !== "string" || typeof agentJar !== "string" || typeof outputRoot !== "string") {
    throw new Error("projectRoot, agentJar, and outputRoot are required path strings");
  }
  const resolvedProjectRoot = path.resolve(projectRoot);
  const manifestSnapshot = await snapshotRegularFile(
    path.join(resolvedProjectRoot, ...RELEASE_MANIFEST.split("/")),
    "release manifest",
  );
  const snapshots = [manifestSnapshot];
  try {
    assertSafePublicText("release-manifest.json", manifestSnapshot.bytes, resolvedProjectRoot);
    const { manifest, contents: manifestContents } = validateManifest(manifestSnapshot.bytes);
    const payloadSnapshots = [];
    for (const spec of FILE_SPECS) {
      const sourcePath = spec.source === null
        ? path.resolve(agentJar)
        : path.join(resolvedProjectRoot, ...spec.source.split("/"));
      const snapshot = await snapshotRegularFile(sourcePath, spec.path);
      snapshots.push(snapshot);
      payloadSnapshots.push({ ...snapshot, ...spec });
    }

    const byPath = new Map(payloadSnapshots.map((entry) => [entry.path, entry]));
    const fingerprintSnapshot = byPath.get("companion/fingerprint.properties");
    const nativeManifestSnapshot = byPath.get("companion/native-libraries.sha256");
    const agentSnapshot = byPath.get("companion/apollo-native-agent.jar");
    const fingerprint = parseFingerprintProperties(fingerprintSnapshot.bytes);
    validateFingerprint(fingerprint, fingerprintSnapshot.sha256, manifest);
    if (agentSnapshot.sha256 !== fingerprint.agentSha256) {
      throw new Error(
        `agentSha256 mismatch: expected ${fingerprint.agentSha256}, got ${agentSnapshot.sha256}`,
      );
    }
    if (nativeManifestSnapshot.sha256 !== fingerprint.nativeLibrarySha256) {
      throw new Error(
        `nativeLibrarySha256 mismatch: expected ${fingerprint.nativeLibrarySha256}, got ${nativeManifestSnapshot.sha256}`,
      );
    }
    validateNativeManifest(nativeManifestSnapshot.bytes);
    for (const entry of payloadSnapshots) {
      const expected = manifest.files.find((candidate) => candidate.path === entry.path).sha256;
      requireEqual(`input file ${entry.path} sha256`, entry.sha256, expected);
      if (entry.text) assertSafePublicText(entry.path, entry.bytes, resolvedProjectRoot);
      if (entry.jar && !(entry.bytes[0] === 0x50 && entry.bytes[1] === 0x4b)) {
        throw new Error(`${entry.path} must be a JAR/ZIP file`);
      }
    }
    const manifestBytes = Buffer.from(manifestContents, "utf8");
    return await publishStaging(
      outputRoot,
      snapshots,
      payloadSnapshots.map(({ path: stagedPath, bytes }) => ({ path: stagedPath, bytes })),
      manifestBytes,
      manifest,
    );
  } finally {
    await Promise.all(snapshots.map(async (snapshot) => {
      try {
        await snapshot.handle.close();
      } catch (error) {
        if (error.code !== "EBADF") throw error;
      }
    }));
  }
}

function parseCli(args) {
  let agentJar;
  while (args.length) {
    const option = args.shift();
    if (option !== "--agent-jar") throw new Error(`unknown option: ${option}`);
    if (agentJar !== undefined) throw new Error("duplicate option: --agent-jar");
    if (!args.length || args[0].startsWith("--")) throw new Error("missing value for --agent-jar");
    agentJar = args.shift();
  }
  if (agentJar === undefined) throw new Error("--agent-jar is required");
  return agentJar;
}

async function main() {
  const projectRoot = process.cwd();
  const agentJar = parseCli(process.argv.slice(2));
  await stageNativeRelease({
    projectRoot,
    agentJar: path.resolve(projectRoot, agentJar),
    outputRoot: path.join(projectRoot, "build/native-release"),
  });
  console.log("PASS staged Native Assist 0.2.2 (9 exact files)");
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(`FAIL native release staging: ${error.message}`);
    process.exitCode = 1;
  });
}
