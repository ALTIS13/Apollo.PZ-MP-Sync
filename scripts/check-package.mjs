import { access, readFile } from "node:fs/promises";
import path from "node:path";
import { inflateSync } from "node:zlib";
import luaparse from "luaparse";
import { displayPath, walkFiles } from "./package-utils.mjs";

const EXPECTED_MOD_ID = "ApolloMPSyncB42";
const FORBIDDEN_PROTOCOL_FIELDS = ["lua", "inventory", "xp", "damage", "item", "recipe"];
const ARTWORK = new Map([
  ["workshop/preview.png", [512, 512]],
  ["workshop/Contents/mods/ApolloMPSyncB42/poster.png", [256, 256]],
  ["workshop/Contents/mods/ApolloMPSyncB42/42/poster.png", [256, 256]],
]);

const args = process.argv.slice(2);
const release = args[0] === "--release";
if (release) args.shift();
const projectRoot = path.resolve(args[0] ?? process.cwd());
const workshopRoot = path.join(projectRoot, "workshop");
const modRoot = path.join(workshopRoot, "Contents", "mods", EXPECTED_MOD_ID);
const buildRoot = path.join(modRoot, "42");
const errors = [];

async function exists(filePath) {
  try { await access(filePath); return true; } catch { return false; }
}

async function text(relativePath) {
  return readFile(path.join(projectRoot, ...relativePath.split("/")), "utf8");
}

function metadataValue(contents, key) {
  const match = contents.match(new RegExp(`^${key}=(.*)$`, "m"));
  return match?.[1].trim();
}

function translationKeys(contents) {
  const keys = new Set();
  for (const match of contents.matchAll(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=/gm)) {
    if (match[1].includes("_ApolloMPSync")) keys.add(match[1]);
  }
  return keys;
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function inspectPng(bytes) {
  const signature = Buffer.from("89504e470d0a1a0a", "hex");
  if (bytes.length < 45 || !bytes.subarray(0, 8).equals(signature)) return null;
  let offset = 8;
  let width;
  let height;
  let bitDepth;
  let colorType;
  let interlace;
  const imageData = [];
  let imageDataEnded = false;
  let sawEnd = false;
  while (offset + 12 <= bytes.length) {
    const length = bytes.readUInt32BE(offset);
    const end = offset + 12 + length;
    if (end > bytes.length) return null;
    const type = bytes.subarray(offset + 4, offset + 8);
    const data = bytes.subarray(offset + 8, offset + 8 + length);
    const expectedCrc = bytes.readUInt32BE(offset + 8 + length);
    if (crc32(Buffer.concat([type, data])) !== expectedCrc) return null;
    const name = type.toString("ascii");
    if (!/^[A-Za-z]{4}$/.test(name)) return null;
    if (offset === 8 && (name !== "IHDR" || length !== 13)) return null;
    if (name === "IHDR") {
      if (offset !== 8) return null;
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      if (width === 0 || height === 0) return null;
      bitDepth = data[8];
      colorType = data[9];
      interlace = data[12];
      const allowedDepths = {
        0: [1, 2, 4, 8, 16],
        2: [8, 16],
        3: [1, 2, 4, 8],
        4: [8, 16],
        6: [8, 16],
      };
      if (!allowedDepths[colorType]?.includes(bitDepth) || data[10] !== 0 || data[11] !== 0 || ![0, 1].includes(interlace)) return null;
    } else if (name === "IDAT") {
      if (imageDataEnded) return null;
      imageData.push(data);
    }
    else if (name === "IEND") {
      if (length !== 0 || end !== bytes.length) return null;
      sawEnd = true;
    } else if (imageData.length) imageDataEnded = true;
    offset = end;
  }
  if (!imageData.length || !sawEnd) return null;
  let decoded;
  try { decoded = inflateSync(Buffer.concat(imageData)); } catch { return null; }
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
  const bitsPerPixel = channels * bitDepth;
  const passes = interlace === 0
    ? [[0, 0, 1, 1]]
    : [[0, 0, 8, 8], [4, 0, 8, 8], [0, 4, 4, 8], [2, 0, 4, 4], [0, 2, 2, 4], [1, 0, 2, 2], [0, 1, 1, 2]];
  let decodedOffset = 0;
  for (const [startX, startY, stepX, stepY] of passes) {
    const passWidth = width > startX ? Math.ceil((width - startX) / stepX) : 0;
    const passHeight = height > startY ? Math.ceil((height - startY) / stepY) : 0;
    if (passWidth === 0 || passHeight === 0) continue;
    const rowBytes = Math.ceil(passWidth * bitsPerPixel / 8);
    for (let row = 0; row < passHeight; row += 1) {
      if (decodedOffset >= decoded.length || decoded[decodedOffset] > 4) return null;
      decodedOffset += 1 + rowBytes;
    }
  }
  return decodedOffset === decoded.length ? { width, height } : null;
}

async function checkMetadata() {
  const buildInfoPath = path.join(buildRoot, "mod.info");
  if (!(await exists(buildInfoPath))) {
    errors.push(`missing ${displayPath("workshop/Contents/mods/ApolloMPSyncB42/42/mod.info")}`);
    return;
  }
  for (const relativePath of [
    "workshop/Contents/mods/ApolloMPSyncB42/mod.info",
    "workshop/Contents/mods/ApolloMPSyncB42/42/mod.info",
  ]) {
    if (!(await exists(path.join(projectRoot, ...relativePath.split("/"))))) {
      errors.push(`missing ${displayPath(relativePath)}`);
      continue;
    }
    const contents = await text(relativePath);
    if (metadataValue(contents, "id") !== EXPECTED_MOD_ID) errors.push(`expected id=${EXPECTED_MOD_ID} in ${displayPath(relativePath)}`);
  }
  const workshop = await text("workshop/workshop.txt");
  const workshopId = metadataValue(workshop, "id");
  if (workshopId !== undefined && !/^\d+$/.test(workshopId)) errors.push("Workshop id must be numeric when assigned");
  const visibility = metadataValue(workshop, "visibility");
  const isAssigned = workshopId !== undefined && /^\d+$/.test(workshopId);
  if (visibility !== "hidden" && !(visibility === "public" && isAssigned)) {
    errors.push("workshop.txt must use visibility=hidden before assignment or visibility=public with a numeric Workshop id");
  }
}

async function checkTranslations() {
  const translationRoot = path.join(buildRoot, "media", "lua", "shared", "Translate");
  const enRoot = path.join(translationRoot, "EN");
  if (!(await exists(enRoot))) { errors.push("missing EN translations"); return; }
  const files = (await walkFiles(enRoot)).filter((file) => file.endsWith("_EN.txt"));
  for (const enFile of files) {
    const ruFile = enFile.replace(/_EN\.txt$/, "_RU.txt");
    const ruPath = path.join(translationRoot, "RU", ...ruFile.split("/"));
    if (!(await exists(ruPath))) { errors.push(`missing RU translation file: ${ruFile}`); continue; }
    const enKeys = translationKeys(await readFile(path.join(enRoot, ...enFile.split("/")), "utf8"));
    const ruKeys = translationKeys(await readFile(ruPath, "utf8"));
    for (const key of enKeys) if (!ruKeys.has(key)) errors.push(`missing RU translation key: ${key}`);
    for (const key of ruKeys) if (!enKeys.has(key)) errors.push(`extra RU translation key: ${key}`);
  }
}

async function checkLua() {
  const luaRoot = path.join(buildRoot, "media", "lua");
  const files = (await walkFiles(luaRoot)).filter((file) => file.endsWith(".lua"));
  const globalAssignments = new Map();

  function tableFieldName(field) {
    if (field.type === "TableKeyString") return field.key.name;
    if (field.type === "TableKey" && field.key.type === "StringLiteral") return field.key.value;
    return undefined;
  }

  function checkProtocolTable(table, relativePath) {
    for (const field of table.fields) {
      const fieldName = tableFieldName(field);
      if (FORBIDDEN_PROTOCOL_FIELDS.includes(fieldName)) {
        errors.push(`forbidden protocol field: ${fieldName} in ${displayPath(relativePath)}`);
      }
      if (field.value?.type === "TableConstructorExpression") checkProtocolTable(field.value, relativePath);
    }
  }

  function recordGlobal(name, relativePath) {
    const firstPath = globalAssignments.get(name);
    if (firstPath !== undefined) {
      errors.push(`duplicate Lua global assignment: ${name} in ${displayPath(relativePath)} (first in ${displayPath(firstPath)})`);
    } else {
      globalAssignments.set(name, relativePath);
    }
  }

  function visit(node, relativePath) {
    if (node === null || typeof node !== "object") return;
    if (node.type === "AssignmentStatement") {
      for (const variable of node.variables) {
        if (variable.type !== "Identifier" || variable.isLocal === true) continue;
        recordGlobal(variable.name, relativePath);
      }
    }
    if (node.type === "FunctionDeclaration" && node.identifier?.type === "Identifier" && node.isLocal !== true) {
      recordGlobal(node.identifier.name, relativePath);
    }
    if (node.type === "CallExpression" && node.base?.type === "Identifier") {
      const argumentIndex = node.base.name === "sendClientCommand" ? 2
        : node.base.name === "sendServerCommand" ? 3 : -1;
      const payload = node.arguments?.[argumentIndex];
      if (payload?.type === "TableConstructorExpression") checkProtocolTable(payload, relativePath);
    }
    for (const [key, value] of Object.entries(node)) {
      if (key === "loc" || key === "comments") continue;
      if (Array.isArray(value)) for (const child of value) visit(child, relativePath);
      else if (value && typeof value === "object") visit(value, relativePath);
    }
  }

  for (const relativePath of files) {
    const contents = await readFile(path.join(luaRoot, ...relativePath.split("/")), "utf8");
    const ast = luaparse.parse(contents, {
      comments: false,
      encodingMode: "pseudo-latin1",
      locations: true,
      luaVersion: "5.1",
      scope: true,
    });
    visit(ast, relativePath);
  }
}

async function checkArtwork() {
  for (const [relativePath, [expectedWidth, expectedHeight]] of ARTWORK) {
    const absolutePath = path.join(projectRoot, ...relativePath.split("/"));
    if (!(await exists(absolutePath))) {
      if (release) errors.push(`missing release artwork: ${displayPath(relativePath)}`);
      continue;
    }
    const dimensions = inspectPng(await readFile(absolutePath));
    if (!dimensions) { errors.push(`invalid PNG: ${displayPath(relativePath)}`); continue; }
    if (dimensions.width !== expectedWidth || dimensions.height !== expectedHeight) {
      errors.push(`expected ${expectedWidth}x${expectedHeight} artwork at ${displayPath(relativePath)}; got ${dimensions.width}x${dimensions.height}`);
    }
  }
}

try {
  await walkFiles(workshopRoot);
  await checkMetadata();
  await checkTranslations();
  await checkLua();
  await checkArtwork();
} catch (error) {
  errors.push(error.message);
}

if (errors.length) {
  for (const error of errors) console.error(`FAIL package: ${error}`);
  process.exitCode = 1;
} else {
  console.log(`PASS package validation${release ? " (release artwork required)" : " (code/metadata; present artwork validated)"}`);
}
