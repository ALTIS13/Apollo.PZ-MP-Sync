import { createHash } from "node:crypto";
import { lstat, readdir, readFile } from "node:fs/promises";
import path from "node:path";

const SENSITIVE_KEY_COMPONENT = /^(?:PASSWORD|PASSWD|TOKEN|SECRET|CREDENTIAL|RCON|API[_-]KEY|ACCESS[_-]KEY(?:[_-]ID)?|PRIVATE[_-]KEY)$/iu;
const EXACT_CAMELCASE_SENSITIVE_KEYS = new Set(["apikey", "accesskey", "accesskeyid", "privatekey"]);

export function isSensitiveKeyName(key) {
  const components = key.split(".");
  return components.some((component) => {
    if (EXACT_CAMELCASE_SENSITIVE_KEYS.has(component.toLowerCase())) return true;
    const words = component.split(/[_-]/u);
    for (let start = 0; start < words.length; start += 1) {
      for (let end = start + 1; end <= Math.min(words.length, start + 3); end += 1) {
        if (SENSITIVE_KEY_COMPONENT.test(words.slice(start, end).join("_"))) return true;
      }
    }
    return false;
  });
}

export async function walkTree(root) {
  const files = [];
  const directories = [];
  async function visit(directory, relativeDirectory = "") {
    const entries = await readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      const relativePath = relativeDirectory ? `${relativeDirectory}/${entry.name}` : entry.name;
      const absolutePath = path.join(directory, entry.name);
      const stats = await lstat(absolutePath);
      if (entry.isSymbolicLink() || stats.isSymbolicLink()) {
        throw new Error(`unsupported filesystem entry: ${relativePath} (symbolic link or junction)`);
      }
      const directoryEntry = stats.isDirectory();
      const fileEntry = stats.isFile();
      if (!directoryEntry && !fileEntry) throw new Error(`unsupported filesystem entry: ${relativePath}`);
      if (directoryEntry) {
        directories.push(relativePath);
        await visit(absolutePath, relativePath);
      } else files.push(relativePath);
    }
  }
  await visit(root);
  const ordinal = (left, right) => left < right ? -1 : left > right ? 1 : 0;
  return {
    directories: directories.sort(ordinal),
    files: files.sort(ordinal),
  };
}

export async function walkFiles(root) {
  return (await walkTree(root)).files;
}

export function withoutWorkshopId(bytes) {
  return Buffer.from(bytes.toString("utf8").replace(/^id=.*(?:\r?\n|$)/gm, ""), "utf8");
}

export function normalizedWorkshopMetadata(bytes) {
  return Buffer.from(bytes.toString("utf8").replace(/^(?:id|visibility)=.*(?:\r?\n|$)/gm, ""), "utf8");
}

export function hash(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

export async function hashPackageFile(packageRoot, relativePath) {
  const bytes = await readFile(path.join(packageRoot, ...relativePath.split("/")));
  return hash(relativePath === "workshop.txt" ? withoutWorkshopId(bytes) : bytes);
}

export function displayPath(relativePath) {
  return relativePath.split("/").join(path.sep);
}
