import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, lstat, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const root = path.resolve(import.meta.dirname, "../..");
const wrapperSource = path.join(root, "deploy", "apollo-native-entrypoint.sh");

function hash(bytes) { return createHash("sha256").update(bytes).digest("hex"); }
function slash(value) {
  if (process.platform !== "win32") return value;
  const match = value.match(/^([A-Za-z]):[\\/](.*)$/);
  return match ? `/${match[1].toLowerCase()}/${match[2].replaceAll("\\", "/")}` : value.replaceAll("\\", "/");
}
function bash() {
  for (const candidate of process.platform === "win32"
    ? ["C:/Program Files/Git/bin/bash.exe", "bash.exe", "bash"] : ["bash"]) {
    if (spawnSync(candidate, ["--version"], { encoding: "utf8" }).status === 0) return candidate;
  }
  throw new Error("bash required");
}

function trustedEnvPath() {
  return process.platform === "win32"
    ? path.resolve(path.dirname(bash()), "../usr/bin/env.exe")
    : "/usr/bin/env";
}

function toolMetadata(posixPath) {
  const environment = { ...process.env };
  for (const key of ["BASH_ENV", "ENV", "BASHOPTS", "SHELLOPTS"]) delete environment[key];
  const result = spawnSync(bash(), ["--noprofile", "--norc", "-c",
    "printf '%s\\n' \"$(/usr/bin/stat -c '%a' -- \"$1\")\"; /usr/bin/sha256sum -- \"$1\" | /usr/bin/awk '{print $1}'",
    "metadata", posixPath], { encoding: "utf8", env: environment });
  assert.equal(result.status, 0, result.stderr);
  const [mode, sha256] = result.stdout.trim().split("\n");
  return { mode: `0${mode}`, sha256 };
}

function trustedArgs(wrapper, frame) {
  return ["-u", "BASH_ENV", "-u", "ENV", "-u", "BASHOPTS", "-u", "SHELLOPTS",
    "-u", "BASH_FUNC_builtin%%", "/bin/bash", "--noprofile", "--norc", slash(wrapper), ...frame];
}

async function fixture() {
  const directory = await mkdtemp(path.join(os.tmpdir(), "apollo-a1-"));
  const companion = path.join(directory, "companion");
  const launchers = path.join(directory, "launchers");
  const trace = path.join(directory, "argv.bin");
  await Promise.all([mkdir(companion), mkdir(launchers)]);
  const firstBytes = "#!/bin/bash\nexec \"$@\"\n";
  const secondBytes = "#!/bin/bash\n: > \"$APOLLO_TEST_TRACE\"\nfor value in \"$@\"; do printf '%s\\0' \"$value\" >> \"$APOLLO_TEST_TRACE\"; done\n";
  const first = path.join(launchers, "first launcher.sh");
  const second = path.join(launchers, "second launcher.sh");
  await writeFile(first, firstBytes);
  await writeFile(second, secondBytes);
  await Promise.all([chmod(first, 0o755), chmod(second, 0o755)]);

  const wrapper = path.join(companion, "wrapper.sh");
  await writeFile(wrapper, (await readFile(wrapperSource, "utf8")).replace(
    "readonly APOLLO_COMPANION_ROOT=/opt/apollo-native",
    `readonly APOLLO_COMPANION_ROOT=${slash(companion)}`,
  ));
  await chmod(wrapper, 0o755);
  const environment = { ...process.env,
    APOLLO_NATIVE_ASSIST: "off",
    APOLLO_RUNTIME_LOCK_MODE: "none-captured",
    APOLLO_SERVER_ROOT: slash(directory),
    APOLLO_SERVER_JAR_RELATIVE: "server.jar",
    APOLLO_ORIGINAL_ENTRYPOINT_COUNT: "2",
    APOLLO_ORIGINAL_ENTRYPOINT_0_PATH: slash(first),
    APOLLO_ORIGINAL_ENTRYPOINT_0_KIND: "file",
    APOLLO_ORIGINAL_ENTRYPOINT_0_MODE: "0755",
    APOLLO_ORIGINAL_ENTRYPOINT_0_SHA256: hash(firstBytes),
    APOLLO_ORIGINAL_ENTRYPOINT_1_PATH: slash(second),
    APOLLO_ORIGINAL_ENTRYPOINT_1_KIND: "file",
    APOLLO_ORIGINAL_ENTRYPOINT_1_MODE: "0755",
    APOLLO_ORIGINAL_ENTRYPOINT_1_SHA256: hash(secondBytes),
    APOLLO_TEST_TRACE: slash(trace),
  };
  const envMetadata = toolMetadata("/usr/bin/env");
  const bashMetadata = toolMetadata("/bin/bash");
  Object.assign(environment, {
    APOLLO_BOOTSTRAP_ENV_PATH: "/usr/bin/env", APOLLO_BOOTSTRAP_ENV_KIND: "file",
    APOLLO_BOOTSTRAP_ENV_MODE: envMetadata.mode, APOLLO_BOOTSTRAP_ENV_SHA256: envMetadata.sha256,
    APOLLO_BOOTSTRAP_BASH_PATH: "/bin/bash", APOLLO_BOOTSTRAP_BASH_KIND: "file",
    APOLLO_BOOTSTRAP_BASH_MODE: bashMetadata.mode, APOLLO_BOOTSTRAP_BASH_SHA256: bashMetadata.sha256,
  });
  for (const key of ["JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"]) delete environment[key];
  return { directory, wrapper, trace, second, environment, argv: ["2", slash(first), slash(second)] };
}

function run(instance, extraEnvironment = {}, cmd = []) {
  return spawnSync(trustedEnvPath(), trustedArgs(instance.wrapper, [...instance.argv, ...cmd]), {
    cwd: instance.directory,
    env: { ...instance.environment, ...extraEnvironment },
    encoding: "utf8",
  });
}

test("count-framed launcher preserves null CMD and arbitrary future CMD bytes without shell splitting", async () => {
  for (const cmd of [[], ["two words", "", "*.jar", "line one\nline two"]]) {
    const instance = await fixture();
    try {
      const result = run(instance, {}, cmd);
      assert.equal(result.status, 0, result.stderr);
      const actual = await readFile(instance.trace);
      assert.deepEqual(actual, Buffer.from(cmd.map((value) => `${value}\0`).join("")));
    } finally { await rm(instance.directory, { recursive: true, force: true }); }
  }
});

for (const scenario of [
  ["zero count", ["0"], {}, /count/i],
  ["count over 32", ["33"], {}, /count/i],
  ["malformed count", ["2x"], {}, /count/i],
  ["count metadata mismatch", undefined, { APOLLO_ORIGINAL_ENTRYPOINT_COUNT: "1" }, /count/i],
  ["relative path", ["2", "relative", "unused"], { APOLLO_ORIGINAL_ENTRYPOINT_0_PATH: "relative" }, /absolute/i],
  ["wrong kind", undefined, { APOLLO_ORIGINAL_ENTRYPOINT_0_KIND: "directory" }, /kind/i],
  ["wrong mode", undefined, { APOLLO_ORIGINAL_ENTRYPOINT_0_MODE: "0700" }, /mode/i],
  ["wrong SHA", undefined, { APOLLO_ORIGINAL_ENTRYPOINT_0_SHA256: "0".repeat(64) }, /SHA-256/i],
  ["extra metadata", undefined, { APOLLO_ORIGINAL_ENTRYPOINT_2_PATH: "/extra" }, /extra.*metadata/i],
  ["runtime lock lie", undefined, { APOLLO_RUNTIME_LOCK_MODE: "host-lock" }, /runtime lock/i],
]) {
  test(`launcher integrity rejects ${scenario[0]} fatally`, async () => {
    const instance = await fixture();
    try {
      if (scenario[1]) instance.argv = scenario[1];
      const result = run(instance, scenario[2]);
      assert.notEqual(result.status, 0, result.stderr);
      assert.match(result.stderr, scenario[3]);
    } finally { await rm(instance.directory, { recursive: true, force: true }); }
  });
}

test("launcher rejects a final symlink before any server argv executes", async () => {
  const instance = await fixture();
  try {
    const target = instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_1_PATH;
    const link = path.join(instance.directory, "launcher-link");
    await symlink(target, link, "file");
    assert.equal((await lstat(link)).isSymbolicLink(), true);
    instance.argv[2] = slash(link);
    instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_1_PATH = slash(link);
    const result = run(instance);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /symlink/i);
  } finally { await rm(instance.directory, { recursive: true, force: true }); }
});

test("launcher rejects missing, non-executable and recursive elements", async () => {
  for (const scenario of [
    ["missing", async (instance) => {
      const missing = slash(path.join(instance.directory, "missing"));
      instance.argv[2] = missing;
      instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_1_PATH = missing;
    }, /mode mismatch|readable executable regular file/i],
    ["non-executable", async (instance) => {
      await chmod(instance.second, 0o644);
      instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_1_MODE = "0644";
    }, /mode mismatch|readable executable regular file/i],
    ["recursive", async (instance) => {
      instance.argv[2] = slash(instance.wrapper);
      instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_1_PATH = slash(instance.wrapper);
    }, /recursion/i],
  ]) {
    const instance = await fixture();
    try {
      await scenario[1](instance);
      const result = run(instance);
      assert.notEqual(result.status, 0, scenario[0]);
      assert.match(result.stderr, scenario[2], scenario[0]);
    } finally { await rm(instance.directory, { recursive: true, force: true }); }
  }
});

test("captured two-element vector and none-captured runtime lock are represented by Compose", async () => {
  const compose = await readFile(path.join(root, "deploy", "compose.native-assist.override.yaml"), "utf8");
  assert.match(compose, /entrypoint:[\s\S]*APOLLO_ORIGINAL_ENTRYPOINT_COUNT[\s\S]*APOLLO_ORIGINAL_ENTRYPOINT_0_PATH[\s\S]*APOLLO_ORIGINAL_ENTRYPOINT_1_PATH/);
  assert.match(compose, /APOLLO_RUNTIME_LOCK_MODE:[\s\S]*none-captured/);
  assert.doesNotMatch(compose, /APOLLO_RUNTIME_LOCK_RELATIVE/);
});
