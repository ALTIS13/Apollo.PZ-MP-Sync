import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const compatibility = readFileSync("compatibility-current-live.md", "utf8");
const adapterRows = compatibility
    .split(/\r?\n/)
    .filter((line) => line.startsWith("|"))
    .map((line) => line.split("|").slice(1, -1).map((cell) => cell.trim()))
    .filter((columns) => columns[4] === "built-in-adapter" || columns[4] === "extension-adapter")
    .map((columns) => `${columns[0]}|${columns[1]}|${columns[4]}`)
    .sort();

const fengariCli = resolve("node_modules/fengari-node-cli/src/lua-cli.js");
const runtimeOutput = execFileSync(process.execPath, [fengariCli, "tests/print_compatibility_adapters.lua"], {
    encoding: "utf8"
});
const runtimeRows = runtimeOutput.trim().split(/\r?\n/).filter(Boolean).sort();

assert.deepEqual(runtimeRows, adapterRows);

console.log("PASS compatibility adapter rows");
