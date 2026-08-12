import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile, cp, symlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { deflateSync } from "node:zlib";

const projectRoot = path.resolve(import.meta.dirname, "..");
const checkScript = path.join(projectRoot, "scripts", "check-package.mjs");
const manifestScript = path.join(projectRoot, "scripts", "build-manifest.mjs");
const verifyScript = path.join(projectRoot, "scripts", "verify-download.mjs");
const luaScript = path.join(projectRoot, "scripts", "check-lua.mjs");

function run(script, args, cwd = projectRoot) {
  const result = spawnSync(process.execPath, [script, ...args], {
    cwd,
    encoding: "utf8",
  });
  return { code: result.status, output: `${result.stdout}${result.stderr}` };
}

async function put(root, relativePath, contents) {
  const destination = path.join(root, ...relativePath.split("/"));
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, contents);
}

function png(width, height, idatOverride) {
  function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
    return (crc ^ 0xffffffff) >>> 0;
  }
  function chunk(type, data) {
    const name = Buffer.from(type, "ascii");
    const result = Buffer.alloc(12 + data.length);
    result.writeUInt32BE(data.length, 0);
    name.copy(result, 4);
    data.copy(result, 8);
    result.writeUInt32BE(crc32(Buffer.concat([name, data])), 8 + data.length);
    return result;
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const pixels = Buffer.alloc(height * (1 + width * 4));
  return Buffer.concat([
    Buffer.from("89504e470d0a1a0a", "hex"),
    chunk("IHDR", ihdr),
    chunk("IDAT", idatOverride ?? deflateSync(pixels)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

async function makeFixture() {
  const root = await mkdtemp(path.join(tmpdir(), "apollo-package-"));
  const modRoot = "workshop/Contents/mods/ApolloMPSyncB42";
  const modInfo = [
    "name=Apollo MP Sync [B42.20.2]",
    "id=ApolloMPSyncB42",
    "poster=poster.png",
    "pzversion=42",
    "versionMin=42.20",
    "modversion=0.1.0",
    "",
  ].join("\n");
  await put(root, "workshop/workshop.txt", "version=1\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=hidden\n");
  await put(root, `${modRoot}/mod.info`, modInfo);
  await put(root, `${modRoot}/42/mod.info`, modInfo);
  await put(root, `${modRoot}/42/media/lua/shared/ApolloMPSync/Protocol.lua`, "local Protocol = {}\nreturn Protocol\n");
  await put(root, `${modRoot}/42/media/lua/client/ApolloMPSync/StateSampler.lua`, "local StateSampler = {}\nreturn StateSampler\n");
  await put(root, `${modRoot}/42/media/lua/shared/Translate/EN/IG_UI_EN.txt`, "IG_UI_EN = {\n    IGUI_ApolloMPSync_ObserverOnly = \"Observer only\",\n}\n");
  await put(root, `${modRoot}/42/media/lua/shared/Translate/RU/IG_UI_RU.txt`, "IG_UI_RU = {\n    IGUI_ApolloMPSync_ObserverOnly = \"Только наблюдатель\",\n}\n");
  return root;
}

async function assignWorkshopId(root, id = "123456") {
  const target = path.join(root, "workshop", "workshop.txt");
  const contents = await readFile(target, "utf8");
  await writeFile(target, contents.replace("title=", `id=${id}\ntitle=`));
}

async function expectRejected(mutate, diagnostic) {
  const root = await makeFixture();
  try {
    await mutate(root);
    const result = run(checkScript, [root]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, diagnostic);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

test("package checker accepts a valid code-only fixture", async () => {
  const root = await makeFixture();
  try {
    const result = run(checkScript, [root]);
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /PASS package validation/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("package checker accepts public visibility after a numeric Workshop ID is assigned", async () => {
  const root = await makeFixture();
  try {
    await assignWorkshopId(root);
    const target = path.join(root, "workshop", "workshop.txt");
    const contents = await readFile(target, "utf8");
    await writeFile(target, contents.replace("visibility=hidden", "visibility=public"));

    const result = run(checkScript, [root]);
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /PASS package validation/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("package checker rejects a missing Build 42 mod.info", async () => {
  await expectRejected(
    (root) => rm(path.join(root, "workshop", "Contents", "mods", "ApolloMPSyncB42", "42", "mod.info")),
    /missing .*42[/\\]mod\.info/i,
  );
});

test("package checker rejects a wrong Mod ID", async () => {
  await expectRejected(async (root) => {
    const target = path.join(root, "workshop", "Contents", "mods", "ApolloMPSyncB42", "42", "mod.info");
    const contents = await readFile(target, "utf8");
    await writeFile(target, contents.replace("id=ApolloMPSyncB42", "id=WrongMod"));
  }, /expected id=ApolloMPSyncB42/i);
});

test("package checker rejects public visibility before numeric Workshop ID assignment", async () => {
  await expectRejected(async (root) => {
    await put(root, "workshop/workshop.txt", "version=1\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=public\n");
  }, /visibility=hidden/i);
});

test("package checker rejects a non-numeric assigned Workshop ID", async () => {
  await expectRejected(async (root) => {
    await put(root, "workshop/workshop.txt", "version=1\nid=not-assigned\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=hidden\n");
  }, /Workshop id must be numeric/i);
});

test("package checker rejects an absent Russian translation key", async () => {
  await expectRejected(async (root) => {
    const target = path.join(root, "workshop", "Contents", "mods", "ApolloMPSyncB42", "42", "media", "lua", "shared", "Translate", "RU", "IG_UI_RU.txt");
    await writeFile(target, "IG_UI_RU = {}\n");
  }, /missing RU translation key: IGUI_ApolloMPSync_ObserverOnly/i);
});

test("package checker rejects duplicate Lua global assignment", async () => {
  await expectRejected(async (root) => {
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/Bad.lua", "ApolloLeak = {}\nApolloLeak = {}\nreturn ApolloLeak\n");
  }, /duplicate Lua global assignment: ApolloLeak/i);
});

test("package checker rejects a duplicate Lua global assigned across modules", async () => {
  await expectRejected(async (root) => {
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/First.lua", "ApolloLeak = {}\nreturn ApolloLeak\n");
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/server/ApolloMPSync/Second.lua", "ApolloLeak = {}\nreturn ApolloLeak\n");
  }, /duplicate Lua global assignment: ApolloLeak/i);
});

test("package checker treats global function declarations as global assignments", async () => {
  await expectRejected(async (root) => {
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/First.lua", "function ApolloLeak() return 1 end\nreturn ApolloLeak\n");
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/server/ApolloMPSync/Second.lua", "function ApolloLeak() return 2 end\nreturn ApolloLeak\n");
  }, /duplicate Lua global assignment: ApolloLeak/i);
});

test("package checker rejects bracketed forbidden fields in direct protocol sends", async () => {
  await expectRejected(async (root) => {
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/StateSampler.lua", "local StateSampler = {}\nfunction StateSampler.player() sendClientCommand('ApolloMPSync', 'player', { ['damage'] = 1 }) end\nreturn StateSampler\n");
  }, /forbidden protocol field: damage/i);
});

test("package checker rejects forbidden fields in direct server protocol sends", async () => {
  await expectRejected(async (root) => {
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/server/ApolloMPSync/Relay.lua", "local function relay(player) sendServerCommand(player, 'ApolloMPSync', 'action', { inventory = true }) end\nreturn relay\n");
  }, /forbidden protocol field: inventory/i);
});

test("Lua policy ignores locals, unrelated table fields, comments, and strings", async () => {
  const root = await makeFixture();
  try {
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/StateSampler.lua", [
      "local ApolloLeak = 'ApolloLeak = hidden'",
      "ApolloLeak = 'still local'",
      "ApolloLeak = 'local reassignment'",
      "local unrelated = { damage = 1, ['inventory'] = true }",
      "-- ApolloLeak = {}; sendClientCommand('x', 'y', { xp = 1 })",
      "return { text = ApolloLeak, unrelated = unrelated }",
      "",
    ].join("\n"));
    const result = run(checkScript, [root]);
    assert.equal(result.code, 0, result.output);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("package checker rejects present artwork without a PNG signature", async () => {
  await expectRejected(
    (root) => put(root, "workshop/preview.png", "not a png"),
    /invalid PNG: workshop[/\\]preview\.png/i,
  );
});

test("package checker rejects a preview whose dimensions are not 512x512", async () => {
  await expectRejected(
    (root) => put(root, "workshop/preview.png", png(511, 512)),
    /expected 512x512.*workshop[/\\]preview\.png/i,
  );
});

test("package checker rejects a structurally framed PNG with invalid image data", async () => {
  await expectRejected(
    (root) => put(root, "workshop/preview.png", png(512, 512, Buffer.from("not zlib data"))),
    /invalid PNG: workshop[/\\]preview\.png/i,
  );
});

test("package checker rejects PNG artwork with a duplicate IHDR chunk", async () => {
  await expectRejected(async (root) => {
    const valid = png(512, 512);
    const duplicateHeader = Buffer.concat([valid.subarray(0, 33), valid.subarray(8)]);
    await put(root, "workshop/preview.png", duplicateHeader);
  }, /invalid PNG: workshop[/\\]preview\.png/i);
});

test("release checking requires the exact preview and poster set", async () => {
  const root = await makeFixture();
  try {
    let result = run(checkScript, ["--release", root]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /missing release artwork: workshop[/\\]preview\.png/i);

    await put(root, "workshop/preview.png", png(512, 512));
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/poster.png", png(256, 256));
    await put(root, "workshop/Contents/mods/ApolloMPSyncB42/42/poster.png", png(256, 256));
    result = run(checkScript, ["--release", root]);
    assert.equal(result.code, 0, result.output);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("manifest is stable, includes ordinary hidden directories, and ignores only external metadata drift", async () => {
  const root = await makeFixture();
  try {
    await put(root, "workshop/workshop.txt", "version=1\nid=123456\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=hidden\n");
    await put(root, "workshop/node_modules/ordinary.txt", "ordinary");
    await put(root, "workshop/.hidden/readme.md", "ordinary documentation");
    const first = run(manifestScript, [root]);
    assert.equal(first.code, 0, first.output);
    const manifestPath = path.join(root, "SHA256SUMS");
    const before = await readFile(manifestPath, "utf8");
    const second = run(manifestScript, [root]);
    assert.equal(second.code, 0, second.output);
    const after = await readFile(manifestPath, "utf8");
    assert.equal(after, before);
    assert.match(after, /node_modules\/ordinary\.txt/);
    assert.match(after, /\.hidden\/readme\.md/);
    assert.doesNotMatch(after, /SHA256SUMS/);

    await put(root, "workshop/workshop.txt", "version=1\nid=999999\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=hidden\n");
    const third = run(manifestScript, [root]);
    assert.equal(third.code, 0, third.output);
    assert.equal(await readFile(manifestPath, "utf8"), before);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("manifest globally sorts sibling file and directory paths ordinally", async () => {
  const root = await makeFixture();
  try {
    await put(root, "workshop/foo/bar", "nested");
    await put(root, "workshop/foo.txt", "sibling");
    const result = run(manifestScript, [root]);
    assert.equal(result.code, 0, result.output);
    const paths = (await readFile(path.join(root, "SHA256SUMS"), "utf8"))
      .trimEnd().split("\n").map((line) => line.slice(66));
    assert.ok(paths.indexOf("foo.txt") < paths.indexOf("foo/bar"), paths.join("\n"));
    assert.deepEqual(paths, [...paths].sort((left, right) => left < right ? -1 : left > right ? 1 : 0));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("source and manifest traversal reject junctions instead of following or skipping them", async () => {
  const root = await makeFixture();
  const outside = await mkdtemp(path.join(tmpdir(), "apollo-outside-"));
  try {
    await put(outside, "escaped.lua", "return {}\n");
    const link = path.join(root, "workshop", "linked");
    await symlink(outside, link, "junction");
    let result = run(checkScript, [root]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /unsupported filesystem entry.*linked/i);
    result = run(manifestScript, [root]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /unsupported filesystem entry.*linked/i);
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});

test("traversal rejects a junction even when its name is normally excluded", async () => {
  const root = await makeFixture();
  const outside = await mkdtemp(path.join(tmpdir(), "apollo-excluded-outside-"));
  try {
    await symlink(outside, path.join(root, "workshop", "node_modules"), "junction");
    const result = run(manifestScript, [root]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /unsupported filesystem entry.*node_modules/i);
  } finally {
    await rm(root, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});

test("download verification permits only valid id and visibility rewrites", async () => {
  const source = await makeFixture();
  const downloaded = await mkdtemp(path.join(tmpdir(), "apollo-download-123456-"));
  try {
    await assignWorkshopId(source);
    let result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    await cp(path.join(source, "workshop"), downloaded, { recursive: true });
    await put(downloaded, "workshop.txt", "version=1\nid=123456\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=private\n");

    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /PASS download verification/);

    await put(downloaded, "workshop.txt", "version=1\nid=not-numeric\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=private\n");
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /invalid Steam id rewrite/i);

    await put(downloaded, "workshop.txt", "version=1\nid=123456\ntitle=Drifted title\nvisibility=private\n");
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /hash mismatch: workshop\.txt/i);
  } finally {
    await rm(source, { recursive: true, force: true });
    await rm(downloaded, { recursive: true, force: true });
  }
});

test("download verification rejects a stale manifest after source drift", async () => {
  const source = await makeFixture();
  const downloaded = await mkdtemp(path.join(tmpdir(), "apollo-download-stale-"));
  try {
    await assignWorkshopId(source);
    let result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    await cp(path.join(source, "workshop"), downloaded, { recursive: true });
    await put(source, "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/Protocol.lua", "local Protocol = { changed = true }\nreturn Protocol\n");

    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /source hash mismatch: Contents\/mods\/ApolloMPSyncB42\/42\/media\/lua\/shared\/ApolloMPSync\/Protocol\.lua/i);
  } finally {
    await rm(source, { recursive: true, force: true });
    await rm(downloaded, { recursive: true, force: true });
  }
});

test("download verification rejects source files added after manifest generation", async () => {
  const source = await makeFixture();
  const downloaded = await mkdtemp(path.join(tmpdir(), "apollo-download-added-"));
  try {
    await assignWorkshopId(source);
    let result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    await cp(path.join(source, "workshop"), downloaded, { recursive: true });
    await put(source, "workshop/Contents/mods/ApolloMPSyncB42/42/media/added-after-manifest.txt", "drift");
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /source path set does not match manifest.*added-after-manifest\.txt/i);
  } finally {
    await rm(source, { recursive: true, force: true });
    await rm(downloaded, { recursive: true, force: true });
  }
});

test("download verification rejects source files removed after manifest generation", async () => {
  const source = await makeFixture();
  const downloaded = await mkdtemp(path.join(tmpdir(), "apollo-download-removed-"));
  try {
    await assignWorkshopId(source);
    let result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    await cp(path.join(source, "workshop"), downloaded, { recursive: true });
    await rm(path.join(source, "workshop", "Contents", "mods", "ApolloMPSyncB42", "42", "media", "lua", "shared", "ApolloMPSync", "Protocol.lua"));
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /source path set does not match manifest.*Protocol\.lua/i);
  } finally {
    await rm(source, { recursive: true, force: true });
    await rm(downloaded, { recursive: true, force: true });
  }
});

test("download verification rejects junctions in downloaded extras", async () => {
  const source = await makeFixture();
  const downloaded = await mkdtemp(path.join(tmpdir(), "apollo-download-link-"));
  const outside = await mkdtemp(path.join(tmpdir(), "apollo-download-outside-"));
  try {
    await assignWorkshopId(source);
    let result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    await cp(path.join(source, "workshop"), downloaded, { recursive: true });
    await symlink(outside, path.join(downloaded, "linked"), "junction");
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /unsupported filesystem entry.*linked/i);
  } finally {
    await rm(source, { recursive: true, force: true });
    await rm(downloaded, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});

test("owner-download wrapper requires one equal numeric Workshop ID", async () => {
  const source = await makeFixture();
  const downloaded = await mkdtemp(path.join(tmpdir(), "apollo-download-id-"));
  try {
    await assignWorkshopId(source, "123456");
    let result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    await cp(path.join(source, "workshop"), downloaded, { recursive: true });
    await put(downloaded, "workshop.txt", "version=1\nid=654321\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=private\n");
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /Workshop id mismatch: source 123456, downloaded 654321/i);

    await put(downloaded, "workshop.txt", "version=1\nid=123456\nid=123456\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=private\n");
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /exactly one numeric assigned id/i);
  } finally {
    await rm(source, { recursive: true, force: true });
    await rm(downloaded, { recursive: true, force: true });
  }
});

test("owner-download verification rejects a missing or duplicate source Workshop ID", async () => {
  const source = await makeFixture();
  const downloaded = await mkdtemp(path.join(tmpdir(), "apollo-download-source-id-"));
  try {
    let result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    await cp(path.join(source, "workshop"), downloaded, { recursive: true });
    await put(downloaded, "workshop.txt", "version=1\nid=123456\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=private\n");
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /source workshop\.txt must contain exactly one numeric assigned id/i);

    await put(source, "workshop/workshop.txt", "version=1\nid=123456\nid=123456\ntitle=Apollo MP Sync [B42.20.2]\nvisibility=hidden\n");
    result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /source workshop\.txt must contain exactly one numeric assigned id/i);
  } finally {
    await rm(source, { recursive: true, force: true });
    await rm(downloaded, { recursive: true, force: true });
  }
});

test("download verification accepts Steam's content-only directory layout", async () => {
  const source = await makeFixture();
  const downloaded = await mkdtemp(path.join(tmpdir(), "apollo-download-content-"));
  try {
    await assignWorkshopId(source);
    let result = run(manifestScript, [source]);
    assert.equal(result.code, 0, result.output);
    await cp(path.join(source, "workshop", "Contents"), downloaded, { recursive: true });

    result = run(verifyScript, [downloaded, "--source", path.join(source, "workshop"), "--manifest", path.join(source, "SHA256SUMS")]);
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /PASS download verification \(6 downloaded files; 1 source metadata file\)/);
  } finally {
    await rm(source, { recursive: true, force: true });
    await rm(downloaded, { recursive: true, force: true });
  }
});

test("Lua checker compiles every module without executing it", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "apollo-lua-"));
  try {
    await put(root, "safe.lua", "error('must not execute')\nreturn {}\n");
    await put(root, "nested/broken.lua", "local =\n");
    let result = run(luaScript, [root]);
    assert.notEqual(result.code, 0, result.output);
    assert.match(result.output, /broken\.lua/);

    await put(root, "nested/broken.lua", "return {}\n");
    result = run(luaScript, [root]);
    assert.equal(result.code, 0, result.output);
    assert.match(result.output, /PASS Lua syntax\/load \(2 modules\)/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
