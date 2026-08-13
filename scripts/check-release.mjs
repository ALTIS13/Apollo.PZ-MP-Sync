import { createHash } from "node:crypto";
import { access, readFile } from "node:fs/promises";
import path from "node:path";
import { parseFingerprintProperties } from "./fingerprint-properties.mjs";
import { walkFiles, walkTree } from "./package-utils.mjs";

const WORKSHOP_VERSION = "0.2.0";
const NATIVE_ASSIST_VERSION = "0.2.1";
const EXACT = Object.freeze({
  appId: "380870",
  buildId: "24574884",
  gameVersionRevision: "42.20.2",
  workshopId: "3780069702",
  luaModId: "ApolloMPSyncB42",
  bridgeProtocol: "1",
  jvmFeature: 25,
  os: "linux",
  arch: "amd64",
});
const HASH = /^[0-9a-f]{64}$/;
const PRODUCTION_SERVER_JAR_SHA256 = "09a80a46e4febe9b436c0f4ec539bdfe9e9113b673eeaf8db22415ac34bef416";
const PRODUCTION_NATIVE_MANIFEST_SHA256 = "86dcfd62671e7a8618c9bbba8433a82425b9c2e896a635c4f21aa70de17108ba";
const PRODUCTION_AGENT_SHA256 = "7166d8ecfa11e8a2737f528b5abe2d561c474a450f737f36add86bda343bf69a";
const PRODUCTION_FINGERPRINT_IDENTITY = "0385b3d71e99ba23706f31e46f35b67993949e1e78e078e903a8445574f3794d";
const PRODUCTION_FINGERPRINT_TRANSPORT_SHA256 = "3d765d2f91b409960391769314bfa62da034c1766ad69e16e3bbc6afdeb49118";
const PRODUCTION_IMAGE_REFERENCE = "ghcr.io/renegade-master/zomboid-dedicated-server@sha256:5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951";
const PRODUCTION_ENTRYPOINT = Object.freeze([
  Object.freeze({
    path: "/bin/bash", kind: "file", mode: "0755",
    sha256: "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58",
  }),
  Object.freeze({
    path: "/home/steam/run_server.sh", kind: "file", mode: "0755",
    sha256: "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8",
  }),
]);
const MOD_INFO_PATHS = [
  "workshop/Contents/mods/ApolloMPSyncB42/mod.info",
  "workshop/Contents/mods/ApolloMPSyncB42/42/mod.info",
];
const SOURCE_ROOTS = [
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua",
  "native-assist/src/main/java",
];
const FORBIDDEN_CALLS = [
  ["coordinate", ["setX", "setY", "setZ", "setPosition", "setCurrentSquare", "setMovingSquare"]],
  ["transform", ["teleportVehicle", "setWorldTransform", "setNetPlayerAuthorization", "sendPhysic"]],
  ["direct damage", ["applyDamage", "doDamage", "addDamage", "setHealth"]],
];
const WORKSHOP_MOD_ROOT = "Contents/mods/ApolloMPSyncB42";
const WORKSHOP_BUILD_ROOT = `${WORKSHOP_MOD_ROOT}/42`;
const WORKSHOP_LUA_ROOT = `${WORKSHOP_BUILD_ROOT}/media/lua`;
const WORKSHOP_DIRECTORIES = new Set([
  "Contents",
  "Contents/mods",
  WORKSHOP_MOD_ROOT,
  WORKSHOP_BUILD_ROOT,
  `${WORKSHOP_BUILD_ROOT}/media`,
  WORKSHOP_LUA_ROOT,
  `${WORKSHOP_LUA_ROOT}/client`,
  `${WORKSHOP_LUA_ROOT}/client/ApolloMPSync`,
  `${WORKSHOP_LUA_ROOT}/server`,
  `${WORKSHOP_LUA_ROOT}/server/ApolloMPSync`,
  `${WORKSHOP_LUA_ROOT}/shared`,
  `${WORKSHOP_LUA_ROOT}/shared/ApolloMPSync`,
  `${WORKSHOP_LUA_ROOT}/shared/Translate`,
  `${WORKSHOP_LUA_ROOT}/shared/Translate/EN`,
  `${WORKSHOP_LUA_ROOT}/shared/Translate/RU`,
]);
const WORKSHOP_EXACT_FILES = new Set([
  "preview.png",
  "workshop.txt",
  `${WORKSHOP_MOD_ROOT}/mod.info`,
  `${WORKSHOP_MOD_ROOT}/poster.png`,
  `${WORKSHOP_BUILD_ROOT}/mod.info`,
  `${WORKSHOP_BUILD_ROOT}/poster.png`,
  `${WORKSHOP_BUILD_ROOT}/media/sandbox-options.txt`,
]);
const SAFE_WORKSHOP_FILE = "[A-Za-z0-9][A-Za-z0-9_.-]*";
const WORKSHOP_LUA_FILE = new RegExp(
  `^${WORKSHOP_LUA_ROOT}/(?:client|server|shared)/ApolloMPSync/${SAFE_WORKSHOP_FILE}\\.lua$`,
);
const WORKSHOP_TRANSLATION_FILE = new RegExp(
  `^${WORKSHOP_LUA_ROOT}/shared/Translate/(EN|RU)/${SAFE_WORKSHOP_FILE}_(EN|RU)\\.txt$`,
);

const argv = process.argv.slice(2);
const projectRoot = path.resolve(argv.shift() ?? process.cwd());
const options = new Map();
while (argv.length) {
  const name = argv.shift();
  if (!["--native-fingerprint", "--agent-jar-a", "--agent-jar-b"].includes(name)) {
    fail(`unknown option: ${name}`);
  }
  if (!argv.length || argv[0].startsWith("--")) fail(`missing value for ${name}`);
  if (options.has(name)) fail(`duplicate option: ${name}`);
  options.set(name, argv.shift());
}

const errors = [];
let agentHash;

function fail(message) {
  console.error(`FAIL release: ${message}`);
  process.exit(1);
}

function resolveInput(value) {
  return path.isAbsolute(value) ? value : path.join(projectRoot, ...value.split("/"));
}

function isProductionAdapter(fingerprint, descriptors) {
  return fingerprint.appId === EXACT.appId
    && fingerprint.buildId === EXACT.buildId
    && fingerprint.gameVersionRevision === EXACT.gameVersionRevision
    && descriptors?.["RUNTIME_ADAPTER|PZ_42_20_2"] === "1";
}

async function exists(filePath) {
  try {
    await access(filePath);
    return true;
  } catch {
    return false;
  }
}

async function text(relativePath) {
  return readFile(path.join(projectRoot, ...relativePath.split("/")), "utf8");
}

function metadataValue(contents, key) {
  return contents.match(new RegExp(`^${key}=(.*)$`, "m"))?.[1].trim();
}

function sourceConstant(contents, name) {
  return contents.match(new RegExp(`\\b${name}\\s*=\\s*[\"']([^\"']+)[\"']`))?.[1];
}

function requireEqual(label, observed, expected) {
  if (observed !== expected) errors.push(`${label} mismatch: expected ${expected}, got ${observed ?? "missing"}`);
}

async function checkVersionTuple() {
  const packageJson = JSON.parse(await text("package.json"));
  const lock = JSON.parse(await text("package-lock.json"));
  requireEqual("package version", packageJson.version, WORKSHOP_VERSION);
  requireEqual("package-lock version", lock.version, WORKSHOP_VERSION);
  requireEqual("package-lock root version", lock.packages?.[""]?.version, WORKSHOP_VERSION);

  for (const relativePath of MOD_INFO_PATHS) {
    const contents = await text(relativePath);
    requireEqual(`${relativePath} modversion`, metadataValue(contents, "modversion"), WORKSHOP_VERSION);
    requireEqual(`${relativePath} id`, metadataValue(contents, "id"), EXACT.luaModId);
    requireEqual(`${relativePath} versionMin`, metadataValue(contents, "versionMin"), EXACT.gameVersionRevision);
    requireEqual(`${relativePath} versionMax`, metadataValue(contents, "versionMax"), EXACT.gameVersionRevision);
  }

  const build = await text("native-assist/build.gradle.kts");
  const buildVersion = build.match(/\bversion\s*=\s*["']([^"']+)["']/)?.[1];
  requireEqual("native agent version", buildVersion, NATIVE_ASSIST_VERSION);

  const protocol = await text("workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/Protocol.lua");
  requireEqual("Lua Workshop ID", sourceConstant(protocol, "WORKSHOP_ID"), EXACT.workshopId);
  requireEqual("Lua Mod ID", sourceConstant(protocol, "MOD_ID"), EXACT.luaModId);
  requireEqual("Lua bridge protocol", sourceConstant(protocol, "BRIDGE_PROTOCOL"), EXACT.bridgeProtocol);

  const gate = await text("native-assist/src/main/java/ru/apollot/pzsync/gate/CompatibilityGate.java");
  for (const [sourceName, expected, label] of [
    ["APP_ID", EXACT.appId, "Java app ID"],
    ["BUILD_ID", EXACT.buildId, "Java BuildID"],
    ["GAME_VERSION_REVISION", EXACT.gameVersionRevision, "Java game version"],
    ["WORKSHOP_ID", EXACT.workshopId, "Java Workshop ID"],
    ["LUA_MOD_ID", EXACT.luaModId, "Java Mod ID"],
    ["BRIDGE_PROTOCOL", EXACT.bridgeProtocol, "Java bridge protocol"],
  ]) requireEqual(label, sourceConstant(gate, sourceName), expected);
}

function isCanonicalWorkshopFile(relativePath) {
  if (WORKSHOP_EXACT_FILES.has(relativePath) || WORKSHOP_LUA_FILE.test(relativePath)) return true;
  const translation = relativePath.match(WORKSHOP_TRANSLATION_FILE);
  return translation !== null && translation[1] === translation[2];
}

async function checkWorkshopSafety() {
  const workshopRoot = path.join(projectRoot, "workshop");
  const { directories, files } = await walkTree(workshopRoot);
  for (const relativePath of directories) {
    if (!WORKSHOP_DIRECTORIES.has(relativePath)) {
      errors.push(`Workshop directory is outside canonical ApolloMPSyncB42 structure: ${relativePath}`);
    }
  }
  for (const relativePath of files) {
    if (isCanonicalWorkshopFile(relativePath)) continue;
    if (relativePath.toLowerCase().endsWith(".jar")) {
      errors.push(`JAR is forbidden in Workshop: ${relativePath}`);
    } else {
      errors.push(`forbidden server/private Workshop artifact outside canonical ApolloMPSyncB42 Workshop structure: ${relativePath}`);
    }
  }
}

async function checkAuthoritySurface() {
  for (const relativeRoot of SOURCE_ROOTS) {
    const absoluteRoot = path.join(projectRoot, ...relativeRoot.split("/"));
    for (const relativePath of await walkFiles(absoluteRoot)) {
      if (!/\.(?:lua|java)$/i.test(relativePath)) continue;
      const contents = await readFile(path.join(absoluteRoot, ...relativePath.split("/")), "utf8");
      for (const [category, methods] of FORBIDDEN_CALLS) {
        for (const method of methods) {
          const call = new RegExp(`(?:[:.]|\\b)\\s*${method}\\s*\\(`);
          if (call.test(contents)) {
            errors.push(`forbidden ${category} mutator ${method} in ${relativeRoot}/${relativePath}`);
          }
        }
      }
    }
  }
}

async function checkAgentReproducibility() {
  const firstValue = options.get("--agent-jar-a");
  const secondValue = options.get("--agent-jar-b");
  if (!firstValue && !secondValue) return;
  if (!firstValue || !secondValue) {
    errors.push("both independently built agent JAR paths are required");
    return;
  }
  const [first, second] = await Promise.all([
    readFile(resolveInput(firstValue)),
    readFile(resolveInput(secondValue)),
  ]);
  const firstHash = createHash("sha256").update(first).digest("hex");
  const secondHash = createHash("sha256").update(second).digest("hex");
  if (firstHash !== secondHash) {
    errors.push(`agent JAR is not reproducible: ${firstHash} != ${secondHash}`);
    return;
  }
  agentHash = firstHash;
}

async function checkFingerprint() {
  const value = options.get("--native-fingerprint");
  if (!value) return;
  const absolutePath = resolveInput(value);
  if (!(await exists(absolutePath))) {
    errors.push(`exact native fingerprint is missing: ${value}`);
    return;
  }
  const contents = await readFile(absolutePath);
  let fingerprint;
  try {
    fingerprint = parseFingerprintProperties(contents);
  } catch (error) {
    errors.push(`invalid exact native fingerprint: ${error.message}`);
    return;
  }
  if (!agentHash) {
    errors.push("native fingerprint validation requires two independently built agent JARs");
  }
  for (const [field, expected] of Object.entries(EXACT)) {
    requireEqual(`fingerprint ${field}`, fingerprint[field], expected);
  }
  for (const field of ["serverJarSha256", "nativeLibrarySha256", "agentSha256", "fingerprintSha256"]) {
    if (!HASH.test(fingerprint[field] ?? "")) errors.push(`fingerprint ${field} must be an exact lowercase SHA-256`);
  }
  const classHashes = fingerprint.classHashes ?? Object.fromEntries(
    Object.entries(fingerprint).filter(([key]) => key.startsWith("classHashes.")),
  );
  const descriptors = fingerprint.methodDescriptors ?? Object.fromEntries(
    Object.entries(fingerprint).filter(([key]) => key.startsWith("methodDescriptors.")),
  );
  if (!classHashes || Object.keys(classHashes).length === 0) errors.push("fingerprint classHashes must not be empty");
  else for (const [name, value] of Object.entries(classHashes)) {
    if (!name) errors.push("fingerprint class hash name must not be blank");
    else if (!HASH.test(value)) errors.push(`fingerprint class hash ${name} must be a lowercase SHA-256`);
  }
  if (!descriptors || Object.keys(descriptors).length === 0) errors.push("fingerprint methodDescriptors must not be empty");
  else for (const [name, value] of Object.entries(descriptors)) {
    if (!name) errors.push("fingerprint method descriptor name must not be blank");
    else if (!value) errors.push(`fingerprint method descriptor ${name} must not be blank`);
  }
  if (agentHash && fingerprint.agentSha256 !== agentHash) {
    errors.push(`fingerprint agentSha256 mismatch: expected ${agentHash}, got ${fingerprint.agentSha256 ?? "missing"}`);
  }
  if (isProductionAdapter(fingerprint, descriptors)) {
    requireEqual("production fingerprint server JAR SHA-256",
      fingerprint.serverJarSha256, PRODUCTION_SERVER_JAR_SHA256);
    requireEqual("production fingerprint native manifest SHA-256",
      fingerprint.nativeLibrarySha256, PRODUCTION_NATIVE_MANIFEST_SHA256);
    requireEqual("production fingerprint image reference",
      fingerprint.imageReference, PRODUCTION_IMAGE_REFERENCE);
    requireEqual("production fingerprint ENTRYPOINT count",
      fingerprint.originalEntrypoint?.length, PRODUCTION_ENTRYPOINT.length);
    PRODUCTION_ENTRYPOINT.forEach((expected, index) => {
      for (const field of ["path", "kind", "mode", "sha256"]) {
        requireEqual(`production fingerprint ENTRYPOINT ${index} ${field}`,
          fingerprint.originalEntrypoint?.[index]?.[field], expected[field]);
      }
    });
    requireEqual("production fingerprint image CMD", fingerprint.imageCmd, "null");
    requireEqual("production fingerprint runtime lock mode",
      fingerprint.runtimeLockMode, "none-captured");
    requireEqual("production fingerprint agent SHA-256",
      fingerprint.agentSha256, PRODUCTION_AGENT_SHA256);
    requireEqual("production fingerprint identity",
      fingerprint.fingerprintSha256, PRODUCTION_FINGERPRINT_IDENTITY);
    requireEqual("production fingerprint transport SHA-256",
      createHash("sha256").update(contents).digest("hex"),
      PRODUCTION_FINGERPRINT_TRANSPORT_SHA256);
    const keys = Object.keys(descriptors ?? {});
    requireEqual("production fingerprint adapter marker",
      descriptors?.["RUNTIME_ADAPTER|PZ_42_20_2"], "1");
    for (const [prefix, count] of [
      ["ADAPTER_HOOK|", 6], ["ADAPTER_VARIANT|", 3],
      ["ADAPTER_CAPABILITY|", 8], ["ADAPTER_PROOF|", 1],
      ["RUNTIME_BINDINGS_FACTORY|", 1],
    ]) requireEqual(`production fingerprint ${prefix} count`,
      keys.filter((key) => key.startsWith(prefix)).length, count);
    const allowed = /^(?:RUNTIME_ADAPTER|RUNTIME_BINDINGS_FACTORY|ADAPTER_(?:HOOK|VARIANT|VARIANT_PROOF|CHAIN|CAPABILITY|SUPPORT|SUPPORT_PROOF|PROOF))\|/;
    for (const key of keys) {
      if (!allowed.test(key)) errors.push(`production fingerprint has an unused/unknown role: ${key}`);
    }
  }
}

try {
  await checkVersionTuple();
  await checkWorkshopSafety();
  await checkAuthoritySurface();
  await checkAgentReproducibility();
  await checkFingerprint();
} catch (error) {
  errors.push(error.message);
}

if (errors.length) {
  for (const error of errors) console.error(`FAIL release: ${error}`);
  process.exitCode = 1;
} else {
  const mode = options.has("--native-fingerprint") ? "exact native fingerprint" : "Workshop-only";
  const reproducibility = agentHash ? `; agent SHA-256 ${agentHash}` : "";
  console.log(`PASS release authority gates (${mode}${reproducibility})`);
}
