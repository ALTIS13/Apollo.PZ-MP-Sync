import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

import { parseFingerprintProperties, serializeFingerprintProperties } from "../../scripts/fingerprint-properties.mjs";

const root = path.resolve(import.meta.dirname, "../..");
const deploy = path.join(root, "deploy");
const golden = path.join(root, "tests", "fixtures", "fingerprint", "exact-fingerprint.properties");
const hostileMembers = path.join(path.dirname(golden), "hostile-member-suffixes.tsv");

function hash(contents) { return createHash("sha256").update(contents).digest("hex"); }
function slash(value) {
  if (process.platform !== "win32") return value;
  const match = value.match(/^([A-Za-z]):[\\/](.*)$/);
  return match ? `/${match[1].toLowerCase()}/${match[2].replaceAll("\\", "/")}` : value.replaceAll("\\", "/");
}
function bashPath() {
  for (const candidate of process.platform === "win32"
    ? ["C:/Program Files/Git/bin/bash.exe", "bash.exe", "bash"] : ["bash"]) {
    if (spawnSync(candidate, ["--version"], { encoding: "utf8" }).status === 0) return candidate;
  }
  throw new Error("bash is required");
}

function trustedEnvPath() {
  return process.platform === "win32"
    ? path.resolve(path.dirname(bashPath()), "../usr/bin/env.exe")
    : "/usr/bin/env";
}

function toolMetadata(posixPath) {
  const environment = { ...process.env };
  delete environment.BASH_ENV;
  delete environment.ENV;
  delete environment.BASHOPTS;
  delete environment.SHELLOPTS;
  const result = spawnSync(bashPath(), ["--noprofile", "--norc", "-c",
    "printf '%s\\n' \"$(/usr/bin/stat -c '%a' -- \"$1\")\"; /usr/bin/sha256sum -- \"$1\" | /usr/bin/awk '{print $1}'",
    "metadata", posixPath], { encoding: "utf8", env: environment });
  assert.equal(result.status, 0, result.stderr);
  const [mode, sha256] = result.stdout.trim().split("\n");
  return { mode: `0${mode}`, sha256 };
}

function trustedPrefix() {
  return ["-u", "BASH_ENV", "-u", "ENV", "-u", "BASHOPTS", "-u", "SHELLOPTS",
    "-u", "BASH_FUNC_builtin%%", "/bin/bash", "--noprofile", "--norc"];
}

function trustedArgs(wrapper, frame) {
  return [...trustedPrefix(), slash(wrapper), ...frame];
}

function refreshIdentity(contents) {
  const omitted = contents.split("\n").filter((line) => !line.startsWith("fingerprintSha256=")).join("\n");
  return contents.replace(/^fingerprintSha256=.*$/m, `fingerprintSha256=${hash(omitted)}`);
}

function exactFingerprint(agent, serverJar, nativeManifest, entrypoint) {
  return serializeFingerprintProperties({
    appId: "380870", buildId: "24574884", gameVersionRevision: "42.20.2",
    serverJarSha256: hash(serverJar), nativeLibrarySha256: hash(nativeManifest), agentSha256: hash(agent),
    imageReference: `fixture.invalid/apollo/native-assist@sha256:${"1".repeat(64)}`,
    originalEntrypoint: Array.isArray(entrypoint) ? entrypoint : [entrypoint],
    imageCmd: "null", runtimeLockMode: "none-captured",
    jvmFeature: 25, os: "linux", arch: "amd64",
    classHashes: { "fixture.Packet": "a".repeat(64) },
    methodDescriptors: { "fixture.Packet#process": "()V" },
    workshopId: "3780069702", luaModId: "ApolloMPSyncB42", bridgeProtocol: "1",
  });
}

async function fixture({ exactTwoElementVector = false } = {}) {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "apollo-native-deploy-"));
  const companion = path.join(temporary, "companion");
  const server = path.join(temporary, "server");
  const trace = path.join(temporary, "trace.bin");
  await Promise.all([mkdir(companion), mkdir(server)]);
  const agent = Buffer.from("deterministic-agent-fixture");
  const serverJar = Buffer.from("dedicated-server-fixture");
  const nativeLibrary = Buffer.from("native-library-fixture");
  const nativeManifest = Buffer.from(`${hash(nativeLibrary)}  native.so\n`);
  const commandContents = [
    "#!/bin/bash",
    ": > \"$APOLLO_TEST_TRACE\"",
    "printf 'jto=%s\\n' \"${JAVA_TOOL_OPTIONS-unset}\" >> \"$APOLLO_TEST_TRACE\"",
    "for value in \"$@\"; do printf '%s\\0' \"$value\" >> \"$APOLLO_TEST_TRACE\"; done",
    "/usr/bin/env -0 > \"${APOLLO_TEST_TRACE}.env\"",
    "",
  ].join("\n");
  const command = path.join(server, "server-command.sh");
  await writeFile(command, commandContents);
  await chmod(command, 0o755);
  const commandEntrypoint = {
    path: slash(command), kind: "file", mode: "0755", sha256: hash(commandContents),
  };
  const bashEntrypoint = { path: "/bin/bash", kind: "file", ...toolMetadata("/bin/bash") };
  const entrypoint = exactTwoElementVector
    ? [bashEntrypoint, commandEntrypoint] : [commandEntrypoint];
  const fingerprint = exactFingerprint(agent, serverJar, nativeManifest, entrypoint);
  await Promise.all([
    writeFile(path.join(companion, "apollo-native-agent.jar"), agent),
    writeFile(path.join(companion, "native-libraries.sha256"), nativeManifest),
    writeFile(path.join(companion, "fingerprint.properties"), fingerprint),
    writeFile(path.join(server, "projectzomboid.jar"), serverJar),
    writeFile(path.join(server, "native.so"), nativeLibrary),
  ]);
  const wrapper = path.join(companion, "wrapper.sh");
  await writeFile(wrapper, (await readFile(path.join(deploy, "apollo-native-entrypoint.sh"), "utf8"))
    .replace("readonly APOLLO_COMPANION_ROOT=/opt/apollo-native",
      `readonly APOLLO_COMPANION_ROOT=${slash(companion)}`));
  await chmod(wrapper, 0o755);

  const environment = { ...process.env,
    APOLLO_NATIVE_ASSIST: "on", APOLLO_RUNTIME_LOCK_MODE: "none-captured",
    APOLLO_SERVER_ROOT: slash(server), APOLLO_SERVER_JAR_RELATIVE: "projectzomboid.jar",
    APOLLO_FINGERPRINT_FILE_SHA256: hash(fingerprint),
    APOLLO_ORIGINAL_ENTRYPOINT_COUNT: String(entrypoint.length),
    APOLLO_TEST_TRACE: slash(trace),
  };
  entrypoint.forEach((element, index) => Object.assign(environment, {
    [`APOLLO_ORIGINAL_ENTRYPOINT_${index}_PATH`]: element.path,
    [`APOLLO_ORIGINAL_ENTRYPOINT_${index}_KIND`]: element.kind,
    [`APOLLO_ORIGINAL_ENTRYPOINT_${index}_MODE`]: element.mode,
    [`APOLLO_ORIGINAL_ENTRYPOINT_${index}_SHA256`]: element.sha256,
  }));
  const envMetadata = toolMetadata("/usr/bin/env");
  const bashMetadata = toolMetadata("/bin/bash");
  Object.assign(environment, {
    APOLLO_BOOTSTRAP_ENV_PATH: "/usr/bin/env", APOLLO_BOOTSTRAP_ENV_KIND: "file",
    APOLLO_BOOTSTRAP_ENV_MODE: envMetadata.mode, APOLLO_BOOTSTRAP_ENV_SHA256: envMetadata.sha256,
    APOLLO_BOOTSTRAP_BASH_PATH: "/bin/bash", APOLLO_BOOTSTRAP_BASH_KIND: "file",
    APOLLO_BOOTSTRAP_BASH_MODE: bashMetadata.mode, APOLLO_BOOTSTRAP_BASH_SHA256: bashMetadata.sha256,
  });
  for (const key of ["JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
    "APOLLO_NATIVE_AGENT_PATH", "APOLLO_NATIVE_FINGERPRINT_PATH", "APOLLO_NATIVE_LIBRARY_MANIFEST_PATH"]) delete environment[key];
  return {
    temporary, companion, server, trace, wrapper, command: slash(command), environment,
    frame: [String(entrypoint.length), ...entrypoint.map((element) => element.path)],
  };
}

function run(instance, extra = {}, args = ["alpha", "two words"]) {
  return spawnSync(trustedEnvPath(), trustedArgs(instance.wrapper, [...instance.frame, ...args]), {
    cwd: instance.temporary, env: { ...instance.environment, ...extra }, encoding: "utf8",
  });
}

async function executedEnvironment(instance) {
  const entries = (await readFile(`${instance.trace}.env`)).toString("utf8").split("\0").filter(Boolean);
  return new Map(entries.map((entry) => {
    const separator = entry.indexOf("=");
    return [entry.slice(0, separator), entry.slice(separator + 1)];
  }));
}

function runWithEntryState(instance, state, sentinels, extra = {}, args = []) {
  const setup = [
    "case \"$1\" in",
    "  value) PATH=$2; LC_ALL=$3; builtin export PATH LC_ALL ;;",
    "  empty) PATH=; LC_ALL=; builtin export PATH LC_ALL ;;",
    "  unset) builtin unset PATH LC_ALL ;;",
    "  *) builtin exit 97 ;;",
    "esac",
    "builtin shift 3",
    "builtin source \"$@\"",
  ].join("\n");
  return spawnSync(trustedEnvPath(), [...trustedPrefix(), "-c", setup, "entry-state", state,
    sentinels.PATH ?? "", sentinels.LC_ALL ?? "", slash(instance.wrapper), ...instance.frame, ...args], {
    cwd: instance.temporary, env: { ...instance.environment, ...extra }, encoding: "utf8",
  });
}

test("compose override keeps core read-only but gives the existing Workshop cache its required writable submount", async () => {
  const compose = await readFile(path.join(deploy, "compose.native-assist.override.yaml"), "utf8");
  const wrapper = await readFile(path.join(deploy, "apollo-native-entrypoint.sh"), "utf8");
  assert.match(compose, /target:\s*\/opt\/apollo-native[\s\S]*?read_only:\s*true/);
  assert.match(compose, /source:\s*"\$\{APOLLO_WORKSHOP_HOST_DIR[\s\S]*?target:\s*"\$\{APOLLO_SERVER_ROOT[^\n]*\/steamapps\/workshop"[\s\S]*?read_only:\s*false/);
  assert.match(compose, /APOLLO_ORIGINAL_ENTRYPOINT_COUNT[\s\S]*APOLLO_ORIGINAL_ENTRYPOINT_0_PATH[\s\S]*APOLLO_ORIGINAL_ENTRYPOINT_1_PATH/);
  assert.match(compose, /no-new-privileges:true/);
  assert.doesNotMatch(compose, /privileged:\s*true|docker\.sock|APOLLO_RUNTIME_LOCK_RELATIVE/i);
  assert.match(compose, /entrypoint:[\s\S]*\/usr\/bin\/env[\s\S]*-u[\s\S]*BASH_ENV[\s\S]*ENV[\s\S]*BASHOPTS[\s\S]*SHELLOPTS[\s\S]*BASH_FUNC_builtin%%[\s\S]*\/bin\/bash[\s\S]*--noprofile[\s\S]*--norc[\s\S]*apollo-native-entrypoint\.sh/);
  assert.match(wrapper, /exec_original_after_final_validation\(\)[\s\S]*validate_launcher_vector[\s\S]*validate_bootstrap_env_contract[\s\S]*restore_original_execution_environment[\s\S]*exec "\$APOLLO_BOOTSTRAP_ENV_PATH" "\$\{scrub_argv\[@\]\}" --/);
});

test("strict fingerprint grammar is identical at the wrapper boundary", async () => {
  const cases = (await readFile(hostileMembers, "utf8")).trimEnd().split("\n")
    .map((line) => line.split("\t"))
    .map(([label, suffix]) => [label, suffix === "<empty>" ? "" : suffix]);
  for (const [label, suffix] of cases) {
    const instance = await fixture();
    try {
      const file = path.join(instance.companion, "fingerprint.properties");
      const line = `classHashes.${suffix}=${"a".repeat(64)}`;
      const changed = refreshIdentity((await readFile(file, "utf8")).replace(/^classHashes\..*$/m, line));
      await writeFile(file, changed);
      const result = run(instance, { APOLLO_FINGERPRINT_FILE_SHA256: hash(changed) });
      assert.equal(result.status, 0, `${label}: ${result.stderr}`);
      assert.match(result.stderr, /fingerprint-invalid/, label);
    } finally { await rm(instance.temporary, { recursive: true, force: true }); }
  }
});

test("trusted Bash prefix suppresses startup payloads/functions but preserves reserved Java rejection", async () => {
  const instance = await fixture();
  try {
    const marker = path.join(instance.temporary, "startup-marker");
    const payload = path.join(instance.temporary, "bash-env.sh");
    await writeFile(payload, `printf exploited > ${JSON.stringify(slash(marker))}\nsha256sum(){ printf shadowed; }\nexport -f sha256sum\n`);
    const prefix = trustedArgs(instance.wrapper, instance.frame);
    const result = spawnSync(trustedEnvPath(), prefix, {
      cwd: instance.temporary,
      env: { ...instance.environment, BASH_ENV: slash(payload),
        "BASH_FUNC_sha256sum%%": "() { printf shadowed; }" },
      encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr);
    await assert.rejects(readFile(marker), { code: "ENOENT" });

    const reserved = spawnSync(trustedEnvPath(), prefix, {
      cwd: instance.temporary,
      env: { ...instance.environment, JAVA_TOOL_OPTIONS: "foreign" },
      encoding: "utf8",
    });
    assert.notEqual(reserved.status, 0);
    assert.match(reserved.stderr, /JAVA_TOOL_OPTIONS is reserved/);
  } finally { await rm(instance.temporary, { recursive: true, force: true }); }
});

test("enabled wrapper passes the recomputed identity to Java when CMD matches null", async () => {
  const instance = await fixture();
  try {
    const result = run(instance, {}, []);
    assert.equal(result.status, 0, result.stderr);
    const trace = await readFile(instance.trace);
    const identity = parseFingerprintProperties(await readFile(path.join(instance.companion, "fingerprint.properties"), "utf8")).fingerprintSha256;
    assert.match(trace.toString("utf8"), new RegExp(`expectedFingerprintSha256=${identity}`));
    assert.equal(trace.includes(0), false);
  } finally { await rm(instance.temporary, { recursive: true, force: true }); }
});

test("native-only mismatches fall back to exact vanilla argv while launcher failures stay fatal", async () => {
  for (const scenario of [
    ["transport", async (i) => writeFile(path.join(i.companion, "fingerprint.properties"), "tampered"), /fingerprint-file-sha256-mismatch/],
    ["server", async (i) => writeFile(path.join(i.server, "projectzomboid.jar"), "tampered"), /server-jar-sha256-mismatch/],
    ["agent", async (i) => writeFile(path.join(i.companion, "apollo-native-agent.jar"), "tampered"), /agent-jar-sha256-mismatch/],
    ["native", async (i) => writeFile(path.join(i.server, "native.so"), "tampered"), /native-library-sha256-mismatch/],
  ]) {
    const instance = await fixture();
    try {
      await scenario[1](instance);
      const result = run(instance, {}, []);
      assert.equal(result.status, 0, `${scenario[0]}: ${result.stderr}`);
      assert.match(result.stderr, scenario[2]);
      assert.match((await readFile(instance.trace)).toString("utf8"), /^jto=unset\n/);
    } finally { await rm(instance.temporary, { recursive: true, force: true }); }
  }

  const launcher = await fixture();
  try {
    const result = run(launcher, { APOLLO_ORIGINAL_ENTRYPOINT_0_SHA256: "0".repeat(64) });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /ENTRYPOINT element 0 SHA-256 mismatch/);
    await assert.rejects(readFile(launcher.trace), { code: "ENOENT" });
  } finally { await rm(launcher.temporary, { recursive: true, force: true }); }
});

test("launcher is revalidated after deterministic mutation on off, fallback and enabled exec paths", async () => {
  for (const mode of ["off", "fallback", "enabled"]) {
    const instance = await fixture();
    try {
      const hook = path.join(instance.companion, "test-before-exec-hook");
      await writeFile(hook, `#!/bin/bash\nprintf mutation >> ${JSON.stringify(instance.command)}\n`);
      await chmod(hook, 0o755);
      const extra = { APOLLO_NATIVE_ASSIST: mode === "off" ? "off" : "on" };
      if (mode === "fallback") extra.APOLLO_FINGERPRINT_FILE_SHA256 = "0".repeat(64);
      const result = run(instance, extra);
      assert.notEqual(result.status, 0, mode);
      assert.match(result.stderr, /ENTRYPOINT element 0 SHA-256 mismatch/i, mode);
      await assert.rejects(readFile(instance.trace), { code: "ENOENT" }, mode);
    } finally { await rm(instance.temporary, { recursive: true, force: true }); }
  }
});

test("original PATH and LC_ALL presence and bytes are restored only at off, fallback and enabled exec", async () => {
  const inheritedCmd = ["two words", "", "*.jar", "line one\nline two"];
  const states = [
    ["value", { PATH: "/sentinel path=one\n/sentinel-two", LC_ALL: "C" }, []],
    ["empty", { PATH: "", LC_ALL: "" }, []],
    ["unset", {}, ["PATH", "LC_ALL"]],
  ];
  for (const branch of ["off", "fallback", "enabled"]) {
    for (const [state, sentinels, absent] of states) {
      const instance = await fixture();
      try {
        const extra = { ...sentinels,
          APOLLO_NATIVE_ASSIST: branch === "off" ? "off" : "on",
          LANG: "C.UTF-8", LC_TIME: "C",
          APOLLO_ORDINARY_SENTINEL: "ordinary value=kept\nbyte-for-byte",
          BASH_ENV: "/must/not/run", ENV: "/must/not/run",
          "BASH_FUNC_bad%%": "nonsense",
          "BASH_FUNC_sha256sum%%": "() { printf shadowed; }",
          "BASH_FUNC_declare%%": "() { printf declare-shadowed >&2; return 91; }",
          "BASH_FUNC_builtin%%": "() { printf builtin-shadowed >&2; return 92; }",
          "BASH_FUNC_mapfile%%": "() { printf mapfile-shadowed >&2; return 93; }",
          "BASH_FUNC_compgen%%": "() { printf compgen-shadowed >&2; return 94; }",
          "BASH_FUNC_unset%%": "() { printf unset-shadowed >&2; return 95; }",
        };
        if (branch === "fallback") extra.APOLLO_FINGERPRINT_FILE_SHA256 = "0".repeat(64);
        const effectiveCmd = branch === "enabled" ? [] : inheritedCmd;
        const result = runWithEntryState(instance, state, sentinels, extra, effectiveCmd);
        assert.equal(result.status, 0, `${branch}/${state}: ${result.stderr}`);
        const environment = await executedEnvironment(instance);
        for (const name of ["PATH", "LC_ALL"]) {
          if (absent.includes(name)) assert.equal(environment.has(name), false, `${branch}/${state}/${name}`);
          else assert.equal(environment.get(name), sentinels[name], `${branch}/${state}/${name}`);
        }
        assert.equal(environment.get("LANG"), "C.UTF-8", `${branch}/${state}/LANG`);
        assert.equal(environment.get("LC_TIME"), "C", `${branch}/${state}/LC_TIME`);
        assert.equal(environment.get("APOLLO_ORDINARY_SENTINEL"),
          "ordinary value=kept\nbyte-for-byte", `${branch}/${state}/ordinary`);
        for (const name of ["BASH_ENV", "ENV", "BASHOPTS", "SHELLOPTS", "_JAVA_OPTIONS",
          "JDK_JAVA_OPTIONS", "APOLLO_NATIVE_AGENT_PATH", "APOLLO_NATIVE_FINGERPRINT_PATH",
          "APOLLO_NATIVE_LIBRARY_MANIFEST_PATH"]) {
          assert.equal(environment.has(name), false, `${branch}/${state}/${name}`);
        }
        assert.equal([...environment.keys()].some((name) => name.startsWith("BASH_FUNC_")), false,
          `${branch}/${state}/functions`);
        if (branch === "enabled") assert.match(environment.get("JAVA_TOOL_OPTIONS"), /^-javaagent:/);
        else assert.equal(environment.has("JAVA_TOOL_OPTIONS"), false, `${branch}/${state}/JAVA_TOOL_OPTIONS`);
        const expectedCmd = Buffer.from(effectiveCmd.map((value) => `${value}\0`).join(""));
        const trace = await readFile(instance.trace);
        if (expectedCmd.length > 0) {
          assert.deepEqual(trace.subarray(-expectedCmd.length), expectedCmd, `${branch}/${state}/CMD`);
        } else {
          assert.equal(trace.includes(0), false, `${branch}/${state}/CMD`);
        }
      } finally { await rm(instance.temporary, { recursive: true, force: true }); }
    }
  }
});

test("raw function environment scrubber bounds hostile name count and byte length", async () => {
  for (const [label, hostile] of [
    ["count", Object.fromEntries(Array.from({ length: 129 }, (_, index) =>
      [`BASH_FUNC_raw${index}%%`, "nonsense"]))],
    ["name", { [`BASH_FUNC_${"x".repeat(1020)}%%`]: "nonsense" }],
  ]) {
    const instance = await fixture();
    try {
      const result = run(instance, hostile, []);
      assert.notEqual(result.status, 0, label);
      assert.match(result.stderr, /raw function environment.*(count|name)/i, label);
      await assert.rejects(readFile(instance.trace), { code: "ENOENT" }, label);
    } finally { await rm(instance.temporary, { recursive: true, force: true }); }
  }
});

test("golden fingerprint reaches post-canonical validation in the wrapper", async () => {
  const instance = await fixture();
  try {
    const fingerprint = parseFingerprintProperties(await readFile(golden, "utf8"));
    fingerprint.originalEntrypoint = [{
      path: instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_0_PATH,
      kind: instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_0_KIND,
      mode: instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_0_MODE,
      sha256: instance.environment.APOLLO_ORIGINAL_ENTRYPOINT_0_SHA256,
    }];
    delete fingerprint.fingerprintSha256;
    const contents = serializeFingerprintProperties(fingerprint);
    await writeFile(path.join(instance.companion, "fingerprint.properties"), contents);
    const result = run(instance, { APOLLO_FINGERPRINT_FILE_SHA256: hash(contents) }, []);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stderr, /agent-jar-sha256-mismatch/);
    assert.doesNotMatch(result.stderr, /fingerprint-invalid/);
  } finally { await rm(instance.temporary, { recursive: true, force: true }); }
});

test("wrapper classifies the exact adapter by tuple instead of filename", async () => {
  const instance = await fixture();
  try {
    const file = path.join(instance.companion, "fingerprint.properties");
    const fingerprint = parseFingerprintProperties(await readFile(file, "utf8"));
    fingerprint.methodDescriptors["RUNTIME_ADAPTER|PZ_42_20_2"] = "1";
    delete fingerprint.fingerprintSha256;
    const changed = serializeFingerprintProperties(fingerprint);
    await writeFile(file, changed);
    const result = run(instance, { APOLLO_FINGERPRINT_FILE_SHA256: hash(changed) });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stderr, /fingerprint-invalid/);
    assert.match((await readFile(instance.trace)).toString("utf8"), /^jto=unset\n/);
  } finally { await rm(instance.temporary, { recursive: true, force: true }); }
});

test("wrapper binds fingerprint launcher metadata to the validated runtime vector", async () => {
  const instance = await fixture();
  try {
    const file = path.join(instance.companion, "fingerprint.properties");
    const fingerprint = parseFingerprintProperties(await readFile(file, "utf8"));
    fingerprint.originalEntrypoint[0].path = "/fixture/other-entrypoint";
    delete fingerprint.fingerprintSha256;
    const changed = serializeFingerprintProperties(fingerprint);
    await writeFile(file, changed);
    const result = run(instance, { APOLLO_FINGERPRINT_FILE_SHA256: hash(changed) });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stderr, /fingerprint-runtime-tuple-mismatch/);
    assert.match((await readFile(instance.trace)).toString("utf8"), /^jto=unset\n/);
  } finally { await rm(instance.temporary, { recursive: true, force: true }); }
});

test("image CMD null arms only with zero inherited CMD arguments", async () => {
  const instance = await fixture({ exactTwoElementVector: true });
  const mismatch = "{\"component\":\"apollo-native-deploy\",\"state\":\"INCOMPATIBLE\","
    + "\"reasonCode\":\"fingerprint-runtime-tuple-mismatch\",\"fallback\":\"vanilla\"}\n";
  try {
    const enabled = run(instance, {}, []);
    assert.equal(enabled.status, 0, enabled.stderr);
    assert.equal(enabled.stderr, "");
    const enabledTrace = await readFile(instance.trace);
    assert.match(enabledTrace.toString("utf8"), /^jto=-javaagent:/);
    assert.equal(enabledTrace.includes(0), false);

    const overridden = run(instance, {}, ["override-command"]);
    assert.equal(overridden.status, 0, overridden.stderr);
    assert.equal(overridden.stderr, mismatch);
    assert.deepEqual(await readFile(instance.trace),
      Buffer.from("jto=unset\noverride-command\0"));
    assert.equal((await executedEnvironment(instance)).has("JAVA_TOOL_OPTIONS"), false);
  } finally { await rm(instance.temporary, { recursive: true, force: true }); }
});

test("image CMD null counts hostile trailing arguments and preserves them on fallback", async () => {
  const mismatch = "{\"component\":\"apollo-native-deploy\",\"state\":\"INCOMPATIBLE\","
    + "\"reasonCode\":\"fingerprint-runtime-tuple-mismatch\",\"fallback\":\"vanilla\"}\n";
  for (const args of [[""], ["two words", "line one\nline two", ""]]) {
    const instance = await fixture({ exactTwoElementVector: true });
    try {
      const result = run(instance, {}, args);
      assert.equal(result.status, 0, result.stderr);
      assert.equal(result.stderr, mismatch);
      assert.deepEqual(await readFile(instance.trace),
        Buffer.from(`jto=unset\n${args.map((value) => `${value}\0`).join("")}`));
      assert.equal((await executedEnvironment(instance)).has("JAVA_TOOL_OPTIONS"), false);
    } finally { await rm(instance.temporary, { recursive: true, force: true }); }
  }
});

test("reserved Java variables are fatal before launcher inspection or execution", async () => {
  for (const name of ["JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"]) {
    const instance = await fixture();
    try {
      const result = run(instance, { [name]: "foreign" });
      assert.notEqual(result.status, 0);
      assert.match(result.stderr, new RegExp(`${name} is reserved`));
      await assert.rejects(readFile(instance.trace), { code: "ENOENT" });
    } finally { await rm(instance.temporary, { recursive: true, force: true }); }
  }
});

test("kill switch ignores native artifacts but still validates and executes the exact launcher", async () => {
  const instance = await fixture();
  try {
    await rm(path.join(instance.companion, "fingerprint.properties"));
    const result = run(instance, { APOLLO_NATIVE_ASSIST: "off", APOLLO_FINGERPRINT_FILE_SHA256: "invalid" });
    assert.equal(result.status, 0, result.stderr);
    assert.match((await readFile(instance.trace)).toString("utf8"), /^jto=unset\n/);
  } finally { await rm(instance.temporary, { recursive: true, force: true }); }
});

test("public deployment inputs remain non-production and document count-frame, identity, none-captured and rollback", async () => {
  const env = await readFile(path.join(deploy, "apollo-native.env.example"), "utf8");
  assert.match(env, /APOLLO_NATIVE_ASSIST=off/);
  assert.match(env, /APOLLO_RUNTIME_LOCK_MODE=none-captured/);
  assert.match(env, /APOLLO_ORIGINAL_ENTRYPOINT_COUNT=2/);
  assert.match(env, /APOLLO_ORIGINAL_ENTRYPOINT_0_PATH=\/bin\/bash/);
  assert.match(env, /APOLLO_ORIGINAL_ENTRYPOINT_1_PATH=\/home\/steam\/run_server\.sh/);
  assert.match(env, /Full-file transport hash/);
  const fingerprint = await readFile(path.join(deploy, "fingerprints", "pz-42.20.2-build-24574884.properties"), "utf8");
  const readme = await readFile(path.join(root, "public", "docs", "NATIVE_ASSIST_RU.md"), "utf8");
  assert.match(fingerprint, /fingerprintSha256=/i);
  assert.match(fingerprint, /runtimeLockMode=none-captured/i);
  assert.match(readme, /откат/i);
  assert.doesNotMatch(`${env}\n${fingerprint}\n${readme}`, /BEGIN [A-Z ]+PRIVATE KEY|(?:password|passwd|token)\s*[:=]\s*[^_\s]/i);
});

test("merged Compose preserves the exact base service invariants", async () => {
  const docker = spawnSync("docker", ["compose", "version"], { encoding: "utf8" });
  if (docker.status !== 0) return;
  const environment = { ...process.env,
    APOLLO_NATIVE_HOST_DIR: slash(deploy), APOLLO_SERVER_HOST_DIR: slash(root),
    APOLLO_WORKSHOP_HOST_DIR: slash(path.join(root, "tests/deploy/fixtures/workshop")), APOLLO_SERVER_ROOT: "/server",
    APOLLO_SERVER_JAR_RELATIVE: "projectzomboid.jar", APOLLO_FINGERPRINT_FILE_SHA256: "a".repeat(64),
    APOLLO_ORIGINAL_ENTRYPOINT_COUNT: "2", APOLLO_ORIGINAL_ENTRYPOINT_0_PATH: "/bin/bash",
    APOLLO_ORIGINAL_ENTRYPOINT_0_KIND: "file", APOLLO_ORIGINAL_ENTRYPOINT_0_MODE: "0755",
    APOLLO_ORIGINAL_ENTRYPOINT_0_SHA256: "b".repeat(64), APOLLO_ORIGINAL_ENTRYPOINT_1_PATH: "/home/steam/run_server.sh",
    APOLLO_ORIGINAL_ENTRYPOINT_1_KIND: "file", APOLLO_ORIGINAL_ENTRYPOINT_1_MODE: "0755",
    APOLLO_ORIGINAL_ENTRYPOINT_1_SHA256: "c".repeat(64),
  };
  const envMetadata = toolMetadata("/usr/bin/env");
  const bashMetadata = toolMetadata("/bin/bash");
  Object.assign(environment, {
    APOLLO_BOOTSTRAP_ENV_PATH: "/usr/bin/env", APOLLO_BOOTSTRAP_ENV_KIND: "file",
    APOLLO_BOOTSTRAP_ENV_MODE: envMetadata.mode, APOLLO_BOOTSTRAP_ENV_SHA256: envMetadata.sha256,
    APOLLO_BOOTSTRAP_BASH_PATH: "/bin/bash", APOLLO_BOOTSTRAP_BASH_KIND: "file",
    APOLLO_BOOTSTRAP_BASH_MODE: bashMetadata.mode, APOLLO_BOOTSTRAP_BASH_SHA256: bashMetadata.sha256,
  });
  const rendered = spawnSync("docker", ["compose", "-f", path.join(root, "tests/deploy/fixtures/compose.base.yaml"),
    "-f", path.join(deploy, "compose.native-assist.override.yaml"), "config", "--format", "json"],
  { cwd: root, env: environment, encoding: "utf8" });
  assert.equal(rendered.status, 0, rendered.stderr);
  const model = JSON.parse(rendered.stdout);
  assert.deepEqual(Object.keys(model.services), ["project-zomboid"]);
  const service = model.services["project-zomboid"];
  assert.deepEqual(service.entrypoint, ["/usr/bin/env", "-u", "BASH_ENV", "-u", "ENV", "-u", "BASHOPTS", "-u", "SHELLOPTS",
    "-u", "BASH_FUNC_builtin%%", "/bin/bash", "--noprofile", "--norc", "/opt/apollo-native/apollo-native-entrypoint.sh",
    "2", "/bin/bash", "/home/steam/run_server.sh"]);
  assert.equal(service.environment.APOLLO_BOOTSTRAP_ENV_PATH, "/usr/bin/env");
  assert.equal(service.environment.APOLLO_BOOTSTRAP_ENV_SHA256, envMetadata.sha256);
  assert.equal(service.environment.APOLLO_BOOTSTRAP_BASH_PATH, "/bin/bash");
  assert.equal(service.environment.APOLLO_BOOTSTRAP_BASH_SHA256, bashMetadata.sha256);
  assert.equal(service.command, null);
  assert.match(service.image, /@sha256:/);
  assert.equal(service.restart, "unless-stopped");
  assert.equal(service.user, "root");
  assert.equal(service.working_dir, "/home/root");
  assert.ok(service.ports.length === 1 && Object.hasOwn(service.networks, "default") && service.healthcheck);
  assert.ok(service.volumes.some((volume) => volume.target === "/existing-config"));
  assert.ok(service.volumes.some((volume) => volume.target === "/opt/apollo-native" && volume.read_only));
  assert.ok(service.volumes.some((volume) => volume.target === "/server" && volume.read_only));
  assert.ok(service.volumes.some((volume) => volume.target === "/server/steamapps/workshop" && !volume.read_only));
  assert.ok(service.security_opt.includes("no-new-privileges:true"));
});

test("wrapper has valid Bash syntax", () => {
  const result = spawnSync(bashPath(), ["-n", slash(path.join(deploy, "apollo-native-entrypoint.sh"))], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
});
