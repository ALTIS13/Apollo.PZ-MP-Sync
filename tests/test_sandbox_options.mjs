import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const mediaPath = path.resolve("workshop/Contents/mods/ApolloMPSyncB42/42/media");
const fixturePath = path.resolve("tests/fixtures/sandbox-options");

function assertTopLevelVersion(source) {
  const versions = [];
  let depth = 0;
  for (const line of source.split(/\r?\n/)) {
    const version = line.match(/^\s*VERSION\s*=\s*([^,\s]+)\s*,?\s*$/)?.[1];
    if (version !== undefined) {
      assert.equal(depth, 0, "VERSION = 1 must be top-level");
      versions.push(version);
    }
    depth += (line.match(/\{/g) ?? []).length;
    depth -= (line.match(/\}/g) ?? []).length;
  }
  assert.equal(versions.length, 1, "sandbox must declare exactly one top-level VERSION = 1");
  assert.equal(versions[0], "1", "sandbox must declare top-level VERSION = 1");
}

function parseSandboxOptions(filePath) {
  assert.ok(fs.existsSync(filePath), `missing sandbox definition: ${filePath}`);
  const source = fs.readFileSync(filePath, "utf8");
  assertTopLevelVersion(source);
  assert.doesNotMatch(source, /^\s*DoLuaChecksum\s*=/m, "DoLuaChecksum must not be a top-level assignment");
  const options = new Map();
  for (const match of source.matchAll(/option\s+([\w.]+)\s*\{([\s\S]*?)\}/g)) {
    const [, key, body] = match;
    assert.ok(!key.split(".").includes("DoLuaChecksum"), "DoLuaChecksum must not be a sandbox option");
    const property = (name) => body.match(new RegExp(`\\b${name}\\s*=\\s*([^,\\s]+)`))?.[1];
    const numberProperty = (name) => {
      const value = property(name);
      return value === undefined ? undefined : Number(value);
    };
    options.set(key, {
      type: property("type"),
      min: numberProperty("min"),
      max: numberProperty("max"),
      default: property("default"),
      translation: property("translation"),
    });
  }
  return options;
}

function parseTranslation(filePath, tableName) {
  assert.ok(fs.existsSync(filePath), `missing translation: ${filePath}`);
  const source = fs.readFileSync(filePath, "utf8");
  assert.match(source, new RegExp(`\\b${tableName}\\s*=\\s*\\{`));
  const values = new Map();
  for (const match of source.matchAll(/^\s*([A-Za-z0-9_]+)\s*=\s*"((?:\\.|[^"\\])*)"\s*,?\s*$/gm)) {
    values.set(match[1], match[2]);
  }
  return values;
}

function assertSameKeys(left, right, name) {
  assert.deepEqual([...left.keys()].sort(), [...right.keys()].sort(), `${name} key sets differ`);
}

const expectedOptions = {
  Enabled: ["boolean", undefined, undefined, "true"],
  PlayerSync: ["boolean", undefined, undefined, "true"],
  ActionSync: ["boolean", undefined, undefined, "true"],
  SleepSync: ["boolean", undefined, undefined, "true"],
  ActionCategorySync: ["boolean", undefined, undefined, "true"],
  ActionProgressIntervalMs: ["integer", 50, 5000, "500"],
  PostActionGraceMs: ["integer", 50, 5000, "1250"],
  PlayerRadius: ["integer", 10, 500, "60"],
  MovingIntervalMs: ["integer", 50, 5000, "200"],
  IdleIntervalMs: ["integer", 50, 5000, "1000"],
  VehicleTransformSync: ["boolean", undefined, undefined, "true"],
  VehicleVisualPartSync: ["boolean", undefined, undefined, "true"],
  VehicleRadius: ["integer", 10, 500, "300"],
  VehicleIntervalMinMs: ["integer", 50, 5000, "100"],
  VehicleIntervalMaxMs: ["integer", 50, 5000, "250"],
  VehicleRepairIntervalSeconds: ["integer", 1, 60, "5"],
  TrailerSync: ["boolean", undefined, undefined, "true"],
  DebugLogging: ["boolean", undefined, undefined, "false"],
  NativeAssistEnabled: ["boolean", undefined, undefined, "true"],
  ServerRewindEnabled: ["boolean", undefined, undefined, "true"],
  PvPRewind: ["boolean", undefined, undefined, "true"],
  PvERewind: ["boolean", undefined, undefined, "true"],
  PlayerNativeAssist: ["boolean", undefined, undefined, "true"],
  ZombieCombatBubble: ["boolean", undefined, undefined, "true"],
  VehicleNativeAssist: ["boolean", undefined, undefined, "true"],
  MaxRewindMs: ["integer", 0, 150, "150"],
};

// Break caught: accepting an invalid sandbox document or reintroducing the live checksum setting into this mod's options.
for (const [fixture, expectedError] of [
  ["missing-version.txt", /VERSION = 1/],
  ["wrong-version.txt", /VERSION = 1/],
  ["duplicate-version.txt", /VERSION = 1/],
  ["version-inside-option-indented.txt", /VERSION = 1/],
  ["version-inside-option-unindented.txt", /VERSION = 1/],
  ["checksum-option.txt", /DoLuaChecksum/],
  ["checksum-assignment.txt", /DoLuaChecksum/],
]) {
  assert.throws(() => parseSandboxOptions(path.join(fixturePath, fixture)), expectedError);
}

// Break caught: a released sandbox definition missing an option, safe range, default, or ApolloMPSync namespace.
const options = parseSandboxOptions(path.join(mediaPath, "sandbox-options.txt"));
assert.equal(options.size, Object.keys(expectedOptions).length);
for (const [name, expected] of Object.entries(expectedOptions)) {
  assert.deepEqual(options.get(`ApolloMPSync.${name}`), {
    type: expected[0], min: expected[1], max: expected[2], default: expected[3], translation: `ApolloMPSync_${name}`,
  });
}
for (const forbidden of ["DirectPlayerCorrection", "DirectVehicleCorrection", "AuthoritativeAssist", "HardSnap", "DiagnosticsIntervalSeconds"])
  assert.equal(options.has(`ApolloMPSync.${forbidden}`), false, `${forbidden} must not be exposed`);

// Break caught: shipping a sandbox label or tooltip in one supported language but not the other.
const sandboxEnglish = parseTranslation(path.join(mediaPath, "lua/shared/Translate/EN/Sandbox_EN.txt"), "Sandbox_EN");
const sandboxRussian = parseTranslation(path.join(mediaPath, "lua/shared/Translate/RU/Sandbox_RU.txt"), "Sandbox_RU");
assertSameKeys(sandboxEnglish, sandboxRussian, "sandbox translation");
for (const name of Object.keys(expectedOptions)) {
  assert.ok(sandboxEnglish.has(`Sandbox_ApolloMPSync_${name}`));
  assert.ok(sandboxEnglish.has(`Sandbox_ApolloMPSync_${name}_tooltip`));
}

// Break caught: client-visible sync status text drifting between English and Russian releases.
const uiEnglish = parseTranslation(path.join(mediaPath, "lua/shared/Translate/EN/IG_UI_EN.txt"), "IG_UI_EN");
const uiRussian = parseTranslation(path.join(mediaPath, "lua/shared/Translate/RU/IG_UI_RU.txt"), "IG_UI_RU");
assertSameKeys(uiEnglish, uiRussian, "UI translation");
assert.ok(uiEnglish.has("IGUI_ApolloMPSync_ObserverOnly"));
assert.ok(uiEnglish.has("IGUI_ApolloMPSync_NativeAuthority"));

console.log("PASS sandbox options and translations");
