import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const workshopPath = path.join(root, "workshop", "workshop.txt");

async function productionWorkshop() {
  const source = await readFile(workshopPath, "utf8");
  const lines = source.replaceAll("\r\n", "\n").split("\n");
  const description = lines
    .filter((line) => line.startsWith("description="))
    .map((line) => line.slice("description=".length))
    .join("\n");
  return { source, description };
}

test("production Workshop description is balanced evergreen English then Russian copy", async () => {
  const { description } = await productionWorkshop();
  const separators = description.match(/^\[hr\]\[\/hr\]$/gm) ?? [];
  assert.equal(separators.length, 1, "expected one exact EN/RU separator");

  const [english, russian] = description.split("[hr][/hr]");
  assert.match(english, /^\[h1\]APOLLO MP SYNC\[\/h1\]/);
  assert.match(russian, /\[h1\]APOLLO MP SYNC — РУССКИЙ\[\/h1\]/);

  for (const required of [
    "Project Zomboid 42.20.x",
    "Workshop ID:[/b] 3780069702",
    "Mod ID:[/b] ApolloMPSyncB42",
    "WorkshopItems=...;3780069702",
    "Mods=...;ApolloMPSyncB42",
    "150 ms",
    "200 ms",
    "MIT License",
  ]) {
    assert.ok(english.includes(required), `missing English fact: ${required}`);
  }

  for (const required of [
    "Project Zomboid 42.20.x",
    "Workshop ID:[/b] 3780069702",
    "Mod ID:[/b] ApolloMPSyncB42",
    "WorkshopItems=...;3780069702",
    "Mods=...;ApolloMPSyncB42",
    "150 мс",
    "200 мс",
    "лицензией MIT",
  ]) {
    assert.ok(russian.includes(required), `missing Russian fact: ${required}`);
  }

  assert.match(english, /clients receive only the Lua mod and its assets/i);
  assert.match(russian, /клиенты получают только Lua-мод и его ресурсы/i);
  assert.match(english, /server-only.*exact-runtime gated.*fail-closed/is);
  assert.match(russian, /только на сервере.*точному окружению.*безопасно отключается/is);
  assert.match(english, /never applies damage or bypasses vanilla anti-cheat/i);
  assert.match(russian, /не наносит урон и не обходит штатный anti-cheat/i);
});

test("production Workshop description fits the Steam UTF-8 byte limit", async () => {
  const { description } = await productionWorkshop();
  assert.ok(
    Buffer.byteLength(description, "utf8") < 8000,
    "Steam Workshop descriptions must fit k_cchPublishedDocumentDescriptionMax",
  );
});

test("production Workshop copy is evergreen and links the public admin installer", async () => {
  const { description } = await productionWorkshop();
  assert.doesNotMatch(description, /WHAT IS NEW|ЧТО НОВОГО|new in 0\.2\.0/i);
  assert.match(description, /https:\/\/github\.com\/ALTIS13\/Apollo\.PZ-MP-Sync/);
  assert.match(description, /https:\/\/github\.com\/ALTIS13\/Apollo\.PZ-MP-Sync\/releases\/latest/);
  assert.match(description, /Workshop clients receive only the Lua mod and its assets/i);
  assert.match(description, /Workshop-клиенты получают только Lua-мод и его ресурсы/i);
  assert.match(description, /graphical installer/i);
  assert.match(description, /графическ(?:ий|ого) установщик/i);
});

test("production Workshop description keeps publication headroom", async () => {
  const { description } = await productionWorkshop();
  assert.ok(Buffer.byteLength(description, "utf8") <= 7500);
});

test("production Workshop metadata preserves publication identity and exact tags", async () => {
  const { source } = await productionWorkshop();
  assert.match(source, /^id=3780069702$/m);
  assert.match(source, /^title=Apollo MP Sync \[B42\.20\+\]$/m);
  assert.match(source, /^visibility=public$/m);
  assert.match(source, /^tags=Build 42, Multiplayer, QoL$/m);
  assert.equal((source.match(/^tags=/gm) ?? []).length, 1);
});

test("production Workshop description contains no stale correction promises", async () => {
  const { description } = await productionWorkshop();
  for (const stale of [
    /position and floor reconciliation/i,
    /vehicle and trailer convergence/i,
    /maximum step:? 0\.25/i,
    /коррекц(?:ия|ии) позиции и этажа/i,
    /сведени(?:е|я) положения транспорта/i,
    /максимальн(?:ый|ого) шаг:? 0[.,]25/i,
    /hard snap/i,
    /authoritative assist/i,
  ]) {
    assert.doesNotMatch(description, stale);
  }
});
