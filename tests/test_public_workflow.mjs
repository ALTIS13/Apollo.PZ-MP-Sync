import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { buildPublicExport } from "../scripts/build-public-export.mjs";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const npmCommand = process.platform === "win32" ? process.execPath : "npm";
const npmPrefix = process.platform === "win32"
  ? [path.join(path.dirname(process.execPath), "node_modules", "npm", "bin", "npm-cli.js")]
  : [];

async function requireFile(root, relativePath) {
  const value = await stat(path.join(root, ...relativePath.split("/")));
  assert.equal(value.isFile(), true, `${relativePath} must be a regular file`);
}

async function run(command, args, cwd) {
  return await new Promise((resolve, reject) => {
    const childEnvironment = { ...process.env, npm_config_audit: "false", npm_config_fund: "false" };
    delete childEnvironment.NODE_TEST_CONTEXT;
    const child = spawn(command, args, {
      cwd,
      env: childEnvironment,
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => { stdout += chunk; });
    child.stderr.setEncoding("utf8").on("data", (chunk) => { stderr += chunk; });
    child.on("error", reject);
    child.on("close", (code, signal) => {
      const output = `${stdout}\n${stderr}`;
      const description = `${command} ${args.join(" ")}`;
      try {
        assert.equal(code, 0, [
          `${description} failed with code ${code} signal ${signal ?? "none"}`,
          output,
        ].join("\n"));
        assert.equal(signal, null, `${description} terminated by signal ${signal}`);
        assert.ok(output.trim().length > 0, `${description} produced no auditable output`);
        assert.doesNotMatch(output, /(?:^|\n)(?:not ok\b|✖)|(?:^|\n)ℹ fail [1-9][0-9]*\b/iu, `${description} reported a nested test failure`);
        resolve({ code, signal, stdout, stderr });
      } catch (error) {
        reject(error);
      }
    });
  });
}

async function runNpm(args, cwd, { nodeTestSummary = false } = {}) {
  const result = await run(npmCommand, [...npmPrefix, ...args], cwd);
  if (nodeTestSummary) {
    const output = `${result.stdout}\n${result.stderr}`;
    assert.match(output, /(?:^|\n)ℹ fail 0\b/u, `npm ${args.join(" ")} did not report a passing Node test summary:\n${output}`);
  }
  return result;
}

test("materialized public source executes its documented verification prerequisites", async (t) => {
  const temporaryRoot = await mkdtemp(path.join(tmpdir(), "apollo-public-workflow-"));
  const publicRoot = path.join(temporaryRoot, "repository");
  t.after(() => rm(temporaryRoot, { recursive: true, force: true }));

  await buildPublicExport({
    sourceRoot: projectRoot,
    outputRoot: publicRoot,
    manifestPath: path.join(projectRoot, "public", "public-export.json"),
  });

  const required = [
    ".github/workflows/release.yml",
    ".github/workflows/verify.yml",
    "installer/Apollo.NativeAssist.Installer.sln",
    "installer/src/Apollo.NativeAssist.Installer/Apollo.NativeAssist.Installer.csproj",
    "native-assist/gradlew",
    "native-assist/gradlew.bat",
    "package-lock.json",
    "package.json",
    "public/public-export.json",
    "public/release/release-manifest.json",
    "scripts/build-public-export.mjs",
    "scripts/stage-native-release.mjs",
    "scripts/verify-installer-repro.ps1",
    "tests/test_public_docs.mjs",
    "tests/test_public_export.mjs",
  ];
  await Promise.all(required.map((relativePath) => requireFile(publicRoot, relativePath)));

  const packageJson = JSON.parse(await readFile(path.join(publicRoot, "package.json"), "utf8"));
  const verifyWorkflow = await readFile(path.join(publicRoot, ".github", "workflows", "verify.yml"), "utf8");
  for (const match of verifyWorkflow.matchAll(/npm run ([a-z0-9:-]+)/giu)) {
    assert.equal(typeof packageJson.scripts[match[1]], "string", `workflow npm script is missing: ${match[1]}`);
  }

  await runNpm(["clean-install", "--offline"], publicRoot);
  await runNpm(["run", "test:public-docs"], publicRoot, { nodeTestSummary: true });
  await runNpm(["run", "test:public-export"], publicRoot, { nodeTestSummary: true });
  await runNpm(["run", "check:release"], publicRoot);

  const gradle = path.join(publicRoot, "native-assist", "gradlew");
  if (process.platform !== "win32") await chmod(gradle, 0o755);
  await run("java", [
    "-classpath", "native-assist/gradle/wrapper/gradle-wrapper.jar",
    "org.gradle.wrapper.GradleWrapperMain",
    "-p", "native-assist", "clean", "agentJar", "--no-daemon",
  ], publicRoot);
  const agentJar = "native-assist/build/libs/apollo-native-assist-0.2.2-agent.jar";
  await runNpm(["run", "stage:native-release", "--", "--agent-jar", agentJar], publicRoot);
  await run("dotnet", ["sln", "installer/Apollo.NativeAssist.Installer.sln", "list"], publicRoot);

  if (process.platform === "win32") {
    await run("pwsh", [
      "-NoProfile",
      "-File", "scripts/verify-installer-repro.ps1",
      "-NativeReleaseDir", "build/native-release/staging",
      "-OutputRoot", "build/installer-repro-check",
    ], publicRoot);
  }

  await runNpm(["run", "build:public-export"], publicRoot);
  const first = await readFile(path.join(publicRoot, "build", "public-export", "PUBLIC-SHA256SUMS"), "utf8");
  await runNpm(["run", "build:public-export"], publicRoot);
  const second = await readFile(path.join(publicRoot, "build", "public-export", "PUBLIC-SHA256SUMS"), "utf8");
  assert.equal(second, first, "two self-exports from materialized public source must be byte-identical");
});
