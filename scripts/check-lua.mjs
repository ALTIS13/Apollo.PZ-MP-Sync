import { readdir } from "node:fs/promises";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = path.resolve(process.argv[2] ?? path.join(process.cwd(), "workshop", "Contents", "mods", "ApolloMPSyncB42", "42", "media", "lua"));
const fengariCli = path.resolve(import.meta.dirname, "..", "node_modules", "fengari-node-cli", "src", "lua-cli.js");

async function luaFiles(directory) {
  const result = [];
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0);
  for (const entry of entries) {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await luaFiles(target));
    else if (entry.isFile() && entry.name.endsWith(".lua")) result.push(target);
  }
  return result;
}

try {
  const files = await luaFiles(root);
  const failures = [];
  for (const file of files) {
    if (file.includes("]=]")) throw new Error(`unsupported Lua path: ${file}`);
    const compile = `local f,e=loadfile([=[${file}]=]); if not f then error(e) end`;
    const result = spawnSync(process.execPath, [fengariCli, "-e", compile], { encoding: "utf8" });
    if (result.status !== 0) failures.push(`${path.relative(root, file)}\n${result.stdout}${result.stderr}`);
  }
  if (failures.length) throw new Error(failures.join("\n"));
  console.log(`PASS Lua syntax/load (${files.length} modules)`);
} catch (error) {
  console.error(`FAIL Lua syntax/load: ${error.message}`);
  process.exitCode = 1;
}
