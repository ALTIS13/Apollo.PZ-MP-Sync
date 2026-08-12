import { createHash } from "node:crypto";

const BEFORE_IDENTITY = [
  "appId", "buildId", "gameVersionRevision", "serverJarSha256",
  "nativeLibrarySha256", "agentSha256",
];
const RUNTIME_PREFIX = ["imageReference", "originalEntrypointCount"];
const RUNTIME_SUFFIX = ["imageCmd", "runtimeLockMode"];
const ENTRYPOINT_FIELDS = ["path", "kind", "mode", "sha256"];
const IDENTITY = "fingerprintSha256";
const AFTER_IDENTITY = ["jvmFeature", "os", "arch"];
const TRAILING_SCALAR_ORDER = ["workshopId", "luaModId", "bridgeProtocol"];
const SCALARS = new Set([
  ...BEFORE_IDENTITY, ...RUNTIME_PREFIX, ...RUNTIME_SUFFIX,
  IDENTITY, ...AFTER_IDENTITY, ...TRAILING_SCALAR_ORDER,
]);

function requiredLineValue(label, value) {
  const text = String(value ?? "");
  if (!/^[\x21-\x7e]+$/.test(text) || text.includes("=")) {
    throw new Error(`${label} must be one canonical ASCII token`);
  }
  return text;
}

function encodedMemberName(name) {
  const text = requiredLineValue("fingerprint member name", name);
  if (text.includes("%")) {
    throw new Error("fingerprint member name contains an unsupported serialization token");
  }
  return text.replaceAll("#", "%23");
}

function encodedEntries(prefix, values) {
  return Object.entries(values ?? {})
    .map(([name, value]) => [`${prefix}${encodedMemberName(name)}`, name, value])
    .sort(([left], [right]) => Buffer.compare(Buffer.from(left, "ascii"), Buffer.from(right, "ascii")));
}

function canonicalEntrypoint(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 32) {
    throw new Error("fingerprint originalEntrypoint must contain 1..32 elements");
  }
  return value.map((entry, index) => {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)
        || Object.keys(entry).sort().join(",") !== [...ENTRYPOINT_FIELDS].sort().join(",")) {
      throw new Error(`fingerprint originalEntrypoint element ${index} has unknown/missing fields`);
    }
    const path = requiredLineValue(`fingerprint originalEntrypoint ${index} path`, entry.path);
    const kind = requiredLineValue(`fingerprint originalEntrypoint ${index} kind`, entry.kind);
    const mode = requiredLineValue(`fingerprint originalEntrypoint ${index} mode`, entry.mode);
    const sha256 = requiredLineValue(`fingerprint originalEntrypoint ${index} sha256`, entry.sha256);
    if (!path.startsWith("/") || path.includes("\n") || path.includes("\r")) {
      throw new Error(`fingerprint originalEntrypoint ${index} path must be absolute`);
    }
    if (kind !== "file") {
      throw new Error(`fingerprint originalEntrypoint ${index} kind must be file`);
    }
    if (!/^0[0-7]{3}$/.test(mode)) {
      throw new Error(`fingerprint originalEntrypoint ${index} mode must be four-digit octal`);
    }
    if (!/^[0-9a-f]{64}$/.test(sha256)) {
      throw new Error(`fingerprint originalEntrypoint ${index} sha256 must be lowercase SHA-256`);
    }
    return { path, kind, mode, sha256 };
  });
}

function canonicalSerializedSuffix(key, prefix) {
  const suffix = key.slice(prefix.length);
  if (!suffix) throw new Error(`empty fingerprint member name: ${key}`);
  for (let index = 0; index < suffix.length; index += 1) {
    const code = suffix.charCodeAt(index);
    if (suffix[index] === "%") {
      if (suffix.slice(index, index + 3) !== "%23") {
        throw new Error(`non-canonical fingerprint member name: ${key}`);
      }
      index += 2;
    } else if (code < 0x21 || code > 0x7e || suffix[index] === "#" || suffix[index] === "=") {
      throw new Error(`non-canonical fingerprint member name: ${key}`);
    }
  }
  return suffix.replaceAll("%23", "#");
}

function contentLines(fingerprint, identity) {
  const lines = [];
  for (const key of BEFORE_IDENTITY) {
    lines.push(`${key}=${requiredLineValue(`fingerprint ${key}`, fingerprint[key])}`);
  }
  const imageReference = requiredLineValue(
    "fingerprint imageReference", fingerprint.imageReference,
  );
  if (!/^.+@sha256:[0-9a-f]{64}$/.test(imageReference)) {
    throw new Error("fingerprint imageReference must be a digest-pinned image reference");
  }
  lines.push(`imageReference=${imageReference}`);
  const entrypoint = canonicalEntrypoint(fingerprint.originalEntrypoint);
  lines.push(`originalEntrypointCount=${entrypoint.length}`);
  entrypoint.forEach((entry, index) => ENTRYPOINT_FIELDS.forEach((field) => {
    lines.push(`originalEntrypoint.${index}.${field}=${entry[field]}`);
  }));
  if (fingerprint.imageCmd !== "null") {
    throw new Error("fingerprint imageCmd must use the canonical null representation");
  }
  lines.push("imageCmd=null");
  if (fingerprint.runtimeLockMode !== "none-captured") {
    throw new Error("fingerprint runtimeLockMode must truthfully be none-captured");
  }
  lines.push("runtimeLockMode=none-captured");
  if (identity !== undefined) lines.push(`${IDENTITY}=${identity}`);
  for (const key of AFTER_IDENTITY) {
    if (key === "jvmFeature"
        && (typeof fingerprint[key] !== "number" || fingerprint[key] !== 25)) {
      throw new Error("fingerprint jvmFeature must be the integer 25");
    }
    lines.push(`${key}=${requiredLineValue(`fingerprint ${key}`, fingerprint[key])}`);
  }
  for (const [key, name, value] of encodedEntries("classHashes.", fingerprint.classHashes)) {
    lines.push(`${key}=${requiredLineValue(`fingerprint class hash ${name}`, value)}`);
  }
  for (const [key, name, value] of encodedEntries("methodDescriptors.", fingerprint.methodDescriptors)) {
    lines.push(`${key}=${requiredLineValue(`fingerprint method descriptor ${name}`, value)}`);
  }
  for (const key of TRAILING_SCALAR_ORDER) {
    lines.push(`${key}=${requiredLineValue(`fingerprint ${key}`, fingerprint[key])}`);
  }
  return lines;
}

function hashCanonicalOmission(fingerprint) {
  const omitted = `${contentLines(fingerprint, undefined).join("\n")}\n`;
  return createHash("sha256").update(omitted, "utf8").digest("hex");
}

export function serializeFingerprintProperties(fingerprint, options = {}) {
  if (Object.keys(options).length !== 0) {
    throw new Error("canonical fingerprint serialization does not permit comments or options");
  }
  const identity = hashCanonicalOmission(fingerprint);
  if (fingerprint.fingerprintSha256 !== undefined
      && fingerprint.fingerprintSha256 !== identity) {
    throw new Error(`stale fingerprintSha256: expected ${identity}`);
  }
  return `${contentLines(fingerprint, identity).join("\n")}\n`;
}

export function parseFingerprintProperties(contents) {
  if (contents instanceof Uint8Array) {
    try {
      contents = new TextDecoder("utf-8", { fatal: true }).decode(contents);
    } catch (error) {
      throw new Error("fingerprint must be strict UTF-8 text", { cause: error });
    }
  }
  if (typeof contents !== "string") throw new Error("fingerprint must be UTF-8 text");
  if (contents.trimStart().startsWith("{")) {
    throw new Error("fingerprint must use canonical key=value serialization");
  }
  if (contents.startsWith("\ufeff") || contents.includes("\0") || contents.includes("\r")) {
    throw new Error("fingerprint contains forbidden BOM, NUL, or CR bytes");
  }
  if (!contents.endsWith("\n") || contents.endsWith("\n\n")) {
    throw new Error("fingerprint must end with exactly one LF");
  }
  const lines = contents.slice(0, -1).split("\n");
  if (!lines.length || lines.some((line) => !line || line.startsWith("#") || line.startsWith("!"))) {
    throw new Error("fingerprint comments and blank lines are forbidden");
  }

  const flat = new Map();
  for (const rawLine of lines) {
    const separator = rawLine.indexOf("=");
    if (separator < 1) throw new Error("malformed fingerprint line");
    const key = rawLine.slice(0, separator);
    const value = rawLine.slice(separator + 1);
    if (key === "jvmFeature" && value !== "25") {
      throw new Error("fingerprint jvmFeature must use canonical decimal 25");
    }
    if (key.startsWith("methodDescriptors.") && !value) {
      throw new Error(`fingerprint method descriptor ${key.slice("methodDescriptors.".length)} must not be blank`);
    }
    if (!/^[\x21-\x7e]+$/.test(key) || !/^[\x21-\x7e]+$/.test(value)
        || value.includes("=")) {
      throw new Error("fingerprint properties must use canonical ASCII tokens");
    }
    if (flat.has(key)) throw new Error(`duplicate fingerprint field: ${key}`);
    flat.set(key, value);
  }

  const countText = flat.get("originalEntrypointCount");
  if (!/^(?:[1-9]|[12][0-9]|3[0-2])$/.test(countText ?? "")) {
    throw new Error("fingerprint originalEntrypointCount must be canonical decimal 1..32");
  }
  const entrypointCount = Number(countText);
  const entrypointKeys = new Set();
  for (let index = 0; index < entrypointCount; index += 1) {
    for (const field of ENTRYPOINT_FIELDS) {
      entrypointKeys.add(`originalEntrypoint.${index}.${field}`);
    }
  }

  const result = { classHashes: {}, methodDescriptors: {}, originalEntrypoint: [] };
  for (const [key, value] of flat) {
    if (SCALARS.has(key)) {
      result[key] = key === "jvmFeature" ? 25 : value;
      continue;
    }
    if (entrypointKeys.has(key)) continue;
    const prefix = key.startsWith("classHashes.") ? "classHashes."
      : key.startsWith("methodDescriptors.") ? "methodDescriptors." : undefined;
    if (!prefix) throw new Error(`unknown fingerprint field: ${key}`);
    const decoded = canonicalSerializedSuffix(key, prefix);
    const destination = prefix === "classHashes." ? result.classHashes : result.methodDescriptors;
    if (Object.hasOwn(destination, decoded)) {
      throw new Error(`duplicate fingerprint field: ${prefix}${decoded}`);
    }
    destination[decoded] = value;
  }
  for (let index = 0; index < entrypointCount; index += 1) {
    const entry = {};
    for (const field of ENTRYPOINT_FIELDS) {
      const key = `originalEntrypoint.${index}.${field}`;
      if (!flat.has(key)) throw new Error(`missing fingerprint field: ${key}`);
      entry[field] = flat.get(key);
    }
    result.originalEntrypoint.push(entry);
  }

  const canonical = serializeFingerprintProperties(result);
  if (canonical !== contents) {
    throw new Error("fingerprint fields are not in strict canonical order");
  }
  return result;
}

export function canonicalFingerprintIdentity(contents) {
  return parseFingerprintProperties(contents).fingerprintSha256;
}
