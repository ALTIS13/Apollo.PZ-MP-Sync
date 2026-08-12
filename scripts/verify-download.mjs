import { readFile } from "node:fs/promises";
import path from "node:path";
import { hash, hashPackageFile, normalizedWorkshopMetadata, walkFiles } from "./package-utils.mjs";

const args = process.argv.slice(2);
const downloadedRoot = args.shift();
function option(name, fallback) {
  const index = args.indexOf(name);
  return index === -1 ? fallback : args[index + 1];
}

if (!downloadedRoot) {
  console.error("Usage: node scripts/verify-download.mjs <downloaded-item-dir>");
  process.exit(2);
}

const projectRoot = path.resolve(import.meta.dirname, "..");
const sourceRoot = path.resolve(option("--source", path.join(projectRoot, "workshop")));
const manifestPath = path.resolve(option("--manifest", path.join(projectRoot, "SHA256SUMS")));
const downloaded = path.resolve(downloadedRoot);
const allowedVisibility = new Set(["public", "friendsOnly", "private", "hidden", "unlisted"]);

function assignedId(contents, label) {
  const values = [...contents.matchAll(/^id=(.*)$/gm)].map((match) => match[1].trim());
  if (values.length !== 1 || !/^\d+$/.test(values[0])) {
    throw new Error(`${label} workshop.txt must contain exactly one numeric assigned id (invalid Steam id rewrite)`);
  }
  return values[0];
}

function visibilityValue(contents, label) {
  const values = [...contents.matchAll(/^visibility=(.*)$/gm)].map((match) => match[1].trim());
  if (values.length !== 1 || !allowedVisibility.has(values[0])) {
    throw new Error(`${label} workshop.txt has invalid Steam visibility rewrite`);
  }
  return values[0];
}

function comparePathSets(sourceFiles, manifestFiles) {
  const source = new Set(sourceFiles);
  const manifest = new Set(manifestFiles);
  const added = sourceFiles.filter((file) => !manifest.has(file));
  const removed = manifestFiles.filter((file) => !source.has(file));
  if (added.length || removed.length) {
    const detail = added.length ? `unexpected source path: ${added[0]}` : `missing source path: ${removed[0]}`;
    throw new Error(`source path set does not match manifest (${detail})`);
  }
}

try {
  const manifest = await readFile(manifestPath, "utf8");
  const expected = new Map();
  for (const line of manifest.trimEnd().split("\n")) {
    const match = line.match(/^([0-9a-f]{64})  (.+)$/);
    if (!match) throw new Error(`malformed manifest line: ${line}`);
    if (expected.has(match[2])) throw new Error(`duplicate manifest path: ${match[2]}`);
    expected.set(match[2], match[1]);
  }

  const sourceFiles = await walkFiles(sourceRoot);
  comparePathSets(sourceFiles, [...expected.keys()]);
  const sourceWorkshopBytes = await readFile(path.join(sourceRoot, "workshop.txt"));
  const sourceWorkshopContents = sourceWorkshopBytes.toString("utf8");
  const sourceId = assignedId(sourceWorkshopContents, "source");
  visibilityValue(sourceWorkshopContents, "source");

  const actualFiles = await walkFiles(downloaded);
  const contentOnly = !actualFiles.some((file) => file === "workshop.txt" || file.startsWith("Contents/"));
  const downloadedPaths = new Map();
  for (const relativePath of expected.keys()) {
    if (!contentOnly) downloadedPaths.set(relativePath, relativePath);
    else if (relativePath.startsWith("Contents/")) downloadedPaths.set(relativePath, relativePath.slice("Contents/".length));
    else if (actualFiles.includes(relativePath)) downloadedPaths.set(relativePath, relativePath);
  }
  const expectedActualFiles = new Set(downloadedPaths.values());
  const extras = actualFiles.filter((file) => !expectedActualFiles.has(file));
  if (extras.length) throw new Error(`unexpected downloaded file: ${extras[0]}`);
  for (const relativePath of expected.keys()) {
    if (await hashPackageFile(sourceRoot, relativePath) !== expected.get(relativePath)) {
      throw new Error(`source hash mismatch: ${relativePath}`);
    }
    const downloadedPath = downloadedPaths.get(relativePath);
    if (downloadedPath === undefined) continue;
    if (!actualFiles.includes(downloadedPath)) throw new Error(`missing downloaded file: ${downloadedPath}`);
    if (relativePath === "workshop.txt") {
      const downloadBytes = await readFile(path.join(downloaded, ...downloadedPath.split("/")));
      const contents = downloadBytes.toString("utf8");
      const downloadedId = assignedId(contents, "downloaded");
      visibilityValue(contents, "downloaded");
      if (downloadedId !== sourceId) throw new Error(`Workshop id mismatch: source ${sourceId}, downloaded ${downloadedId}`);
      if (hash(normalizedWorkshopMetadata(downloadBytes)) !== hash(normalizedWorkshopMetadata(sourceWorkshopBytes))) {
        throw new Error("hash mismatch: workshop.txt");
      }
    } else if (await hashPackageFile(downloaded, downloadedPath) !== expected.get(relativePath)) {
      throw new Error(`hash mismatch: ${relativePath}`);
    }
  }
  const sourceOnlyCount = expected.size - downloadedPaths.size;
  if (sourceOnlyCount) {
    console.log(`PASS download verification (${downloadedPaths.size} downloaded files; ${sourceOnlyCount} source metadata ${sourceOnlyCount === 1 ? "file" : "files"})`);
  } else {
    console.log(`PASS download verification (${expected.size} files)`);
  }
} catch (error) {
  console.error(`FAIL download verification: ${error.message}`);
  process.exitCode = 1;
}
