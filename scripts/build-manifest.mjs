import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { displayPath, hashPackageFile, walkFiles } from "./package-utils.mjs";

const projectRoot = path.resolve(process.argv[2] ?? process.cwd());
const packageRoot = path.join(projectRoot, "workshop");
const outputPath = path.join(projectRoot, "SHA256SUMS");

try {
  const files = await walkFiles(packageRoot);
  const lines = [];
  for (const relativePath of files) {
    lines.push(`${await hashPackageFile(packageRoot, relativePath)}  ${relativePath}`);
  }
  await mkdir(path.dirname(outputPath), { recursive: true });
  await writeFile(outputPath, `${lines.join("\n")}\n`, "utf8");
  console.log(`PASS deterministic manifest (${lines.length} files): ${displayPath(path.relative(process.cwd(), outputPath).replaceAll(path.sep, "/"))}`);
} catch (error) {
  console.error(`FAIL manifest: ${error.message}`);
  process.exitCode = 1;
}
