import { createHash, randomUUID } from "node:crypto";
import {
  constants,
  lstat,
  mkdir,
  open,
  readFile,
  readdir,
  realpath,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { isSensitiveKeyName } from "./package-utils.mjs";

const FORBIDDEN_PATH_SEGMENTS = new Set([
  ".git",
  ".ops-" + "tmp",
  ".super" + "powers",
  "node_modules",
]);
const FORBIDDEN_BINARY_EXTENSIONS = new Set([
  ".7z", ".bin", ".class", ".dll", ".dylib", ".exe", ".gz", ".iso",
  ".pdb", ".so", ".tar", ".tgz", ".war", ".zip",
]);
const KNOWN_BINARY_EXTENSIONS = new Set([".jar", ".png"]);
const GRADLE_WRAPPER_PATH = "native-assist/gradle/wrapper/gradle-wrapper.jar";
const GRADLE_WRAPPER_SHA256 = "497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7";
const PRIVATE_TEXT_MARKERS = ["femboy" + "furrland", ".ops-" + "tmp", ".super" + "powers"];
const MAX_SENSITIVE_CONTINUATION_LINES = 6;
const MAX_SENSITIVE_CONTINUATION_BYTES = 512;
const GOVERNED_TREES = [
  ".github",
  "installer/src",
  "installer/tests",
  "native-assist/src/main",
  "public/.github",
  "workshop/Contents",
];
const GENERATED_DIRECTORY_SEGMENTS = new Set([
  ".gradle",
  "artifacts",
  "bin",
  "build",
  "obj",
  "testresults",
]);
// Task 8 must change this policy explicitly before adding any public category.
const APPROVED_SOURCES = new Set([
  // Exact internal and materialized public-repository source closure.
  ".github/workflows/release.yml",
  ".github/workflows/verify.yml",
  "compatibility-current-live.md",
  "deploy/apollo-native-entrypoint.sh",
  "deploy/apollo-native.env.example",
  "deploy/compose.native-assist.override.yaml",
  "deploy/fingerprint.template.properties",
  "deploy/fingerprints/pz-42.20.2-build-24574884.native-libraries.sha256",
  "deploy/fingerprints/pz-42.20.2-build-24574884.properties",
  "installer/Apollo.NativeAssist.Installer.sln",
  "installer/src/Apollo.NativeAssist.Installer.Core/Apollo.NativeAssist.Installer.Core.csproj",
  "installer/src/Apollo.NativeAssist.Installer.Core/Contracts.cs",
  "installer/src/Apollo.NativeAssist.Installer.Core/InstallationOrchestrator.cs",
  "installer/src/Apollo.NativeAssist.Installer.Core/RedactedLog.cs",
  "installer/src/Apollo.NativeAssist.Installer.Infrastructure/Apollo.NativeAssist.Installer.Infrastructure.csproj",
  "installer/src/Apollo.NativeAssist.Installer.Infrastructure/Docker/DockerComposeConnector.cs",
  "installer/src/Apollo.NativeAssist.Installer.Infrastructure/Properties/AssemblyInfo.cs",
  "installer/src/Apollo.NativeAssist.Installer.Infrastructure/Ssh/IHostKeyProbe.cs",
  "installer/src/Apollo.NativeAssist.Installer.Infrastructure/Ssh/IRemoteSession.cs",
  "installer/src/Apollo.NativeAssist.Installer.Infrastructure/Ssh/SshHostKeyProbe.cs",
  "installer/src/Apollo.NativeAssist.Installer.Infrastructure/Ssh/SshRemoteSession.cs",
  "installer/src/Apollo.NativeAssist.Installer/Apollo.NativeAssist.Installer.csproj",
  "installer/src/Apollo.NativeAssist.Installer/App.xaml",
  "installer/src/Apollo.NativeAssist.Installer/App.xaml.cs",
  "installer/src/Apollo.NativeAssist.Installer/Application/ConnectionInput.cs",
  "installer/src/Apollo.NativeAssist.Installer/Application/IInstallerSession.cs",
  "installer/src/Apollo.NativeAssist.Installer/Application/InstallerSessionFactory.cs",
  "installer/src/Apollo.NativeAssist.Installer/Application/RecoveryRecordStore.cs",
  "installer/src/Apollo.NativeAssist.Installer/Localization/Strings.en-US.xaml",
  "installer/src/Apollo.NativeAssist.Installer/Localization/Strings.ru-RU.xaml",
  "installer/src/Apollo.NativeAssist.Installer/MainWindow.xaml",
  "installer/src/Apollo.NativeAssist.Installer/MainWindow.xaml.cs",
  "installer/src/Apollo.NativeAssist.Installer/Properties/AssemblyInfo.cs",
  "installer/src/Apollo.NativeAssist.Installer/Release/EmbeddedReleaseLoader.cs",
  "installer/src/Apollo.NativeAssist.Installer/Release/IReleaseResourceSource.cs",
  "installer/src/Apollo.NativeAssist.Installer/ViewModels/AsyncCommand.cs",
  "installer/src/Apollo.NativeAssist.Installer/ViewModels/MainWindowViewModel.cs",
  "installer/tests/Apollo.NativeAssist.Installer.Core.Tests/Apollo.NativeAssist.Installer.Core.Tests.csproj",
  "installer/tests/Apollo.NativeAssist.Installer.Core.Tests/InstallationOrchestratorTests.cs",
  "installer/tests/Apollo.NativeAssist.Installer.Infrastructure.Tests/Apollo.NativeAssist.Installer.Infrastructure.Tests.csproj",
  "installer/tests/Apollo.NativeAssist.Installer.Infrastructure.Tests/DockerComposeConnectorTests.cs",
  "installer/tests/Apollo.NativeAssist.Installer.Infrastructure.Tests/SshHostKeyProbeTests.cs",
  "installer/tests/Apollo.NativeAssist.Installer.Infrastructure.Tests/SshRemoteSessionTests.cs",
  "installer/tests/Apollo.NativeAssist.Installer.Ui.Tests/Apollo.NativeAssist.Installer.Ui.Tests.csproj",
  "installer/tests/Apollo.NativeAssist.Installer.Ui.Tests/EmbeddedReleaseLoaderTests.cs",
  "installer/tests/Apollo.NativeAssist.Installer.Ui.Tests/InstallerSessionTests.cs",
  "installer/tests/Apollo.NativeAssist.Installer.Ui.Tests/MainWindowViewModelTests.cs",
  "LICENSE",
  "native-assist/build.gradle.kts",
  "native-assist/gradle.lockfile",
  "native-assist/gradle/wrapper/gradle-wrapper.jar",
  "native-assist/gradle/wrapper/gradle-wrapper.properties",
  "native-assist/gradlew",
  "native-assist/gradlew.bat",
  "native-assist/settings.gradle.kts",
  "native-assist/src/main/java/ru/apollot/pzsync/agent/ApolloNativeAgent.java",
  "native-assist/src/main/java/ru/apollot/pzsync/agent/TargetRuntimeProbe.java",
  "native-assist/src/main/java/ru/apollot/pzsync/attack/AttackBudget.java",
  "native-assist/src/main/java/ru/apollot/pzsync/attack/AttackKey.java",
  "native-assist/src/main/java/ru/apollot/pzsync/attack/AttackLedger.java",
  "native-assist/src/main/java/ru/apollot/pzsync/attack/AttackToken.java",
  "native-assist/src/main/java/ru/apollot/pzsync/attack/ConsumeResult.java",
  "native-assist/src/main/java/ru/apollot/pzsync/attack/TargetGeneration.java",
  "native-assist/src/main/java/ru/apollot/pzsync/attack/WeaponObservation.java",
  "native-assist/src/main/java/ru/apollot/pzsync/bridge/ApolloNativeBridge.java",
  "native-assist/src/main/java/ru/apollot/pzsync/bridge/BridgeConfiguration.java",
  "native-assist/src/main/java/ru/apollot/pzsync/bridge/BridgePublisher.java",
  "native-assist/src/main/java/ru/apollot/pzsync/bridge/BridgeStatus.java",
  "native-assist/src/main/java/ru/apollot/pzsync/gate/AssistState.java",
  "native-assist/src/main/java/ru/apollot/pzsync/gate/CompatibilityGate.java",
  "native-assist/src/main/java/ru/apollot/pzsync/gate/FingerprintLoader.java",
  "native-assist/src/main/java/ru/apollot/pzsync/gate/GateResult.java",
  "native-assist/src/main/java/ru/apollot/pzsync/gate/RuntimeFingerprint.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/CollisionProbe.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/CurrentHitRequest.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/DecisionCode.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/HistoricalPose.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/RewindDecision.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/RewindRequest.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/RewindValidator.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/Vec3.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/WeaponProfile.java",
  "native-assist/src/main/java/ru/apollot/pzsync/geometry/ZombieCurrentHitValidator.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/EntityKey.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/EntityKind.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/HistoryStore.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/HistoryTrack.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/NetworkEstimate.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/NetworkQualityEstimator.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/RewindClock.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/RewindWindow.java",
  "native-assist/src/main/java/ru/apollot/pzsync/history/StateSample.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/AttackOpenAdvice.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/ExactMemberBinding.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/ExactRuntimeBindingsFactory.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/ExactRuntimeBindingsSupport.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/HitGateAdvice.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/HookDescriptor.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/HookInstaller.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/NativeDecisionAdapter.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/PlayerStateObserver.java",
  "native-assist/src/main/java/ru/apollot/pzsync/hooks/ZombieStateObserver.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ConnectionNetworkRegistry.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/DispatcherOrderProof.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ExactAcceptedPacketAccess.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ExactHitPacketAccess.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ExactLuaBridgePublication.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ExactRuntimeAdapterBindings.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ExactWorldCollisionProbe.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ExactZombieRuntimeAccess.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/PlayerIdentityRegistry.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/RuntimeAccessorChains.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/RuntimeAdapterException.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/RuntimeAdapterSpec.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ServerMonotonicClock.java",
  "native-assist/src/main/java/ru/apollot/pzsync/runtime/ZombieIdentityRegistry.java",
  "native-assist/src/main/java/ru/apollot/pzsync/tools/ProductionFingerprintGenerator.java",
  "native-assist/src/main/java/ru/apollot/pzsync/zombie/CombatBubble.java",
  "native-assist/src/main/java/ru/apollot/pzsync/zombie/CombatBubbleIndex.java",
  "native-assist/src/main/java/ru/apollot/pzsync/zombie/ZombieAuthorityEpochs.java",
  "native-assist/src/main/java/ru/apollot/pzsync/zombie/ZombieHandoffPolicy.java",
  "native-assist/src/main/java/ru/apollot/pzsync/zombie/ZombieKeyframePolicy.java",
  "native-assist/src/main/resources/META-INF/MANIFEST.MF",
  "package-lock.json",
  "package.json",
  "public/.github/workflows/release.yml",
  "public/.github/workflows/verify.yml",
  "public/docs/CLIENT_INSTALL_EN.md",
  "public/docs/CLIENT_INSTALL_RU.md",
  "public/docs/COMPATIBILITY.md",
  "public/docs/NATIVE_ASSIST_EN.md",
  "public/docs/NATIVE_ASSIST_RU.md",
  "public/public-export-self.json",
  "public/public-export.json",
  "public/README.md",
  "public/release/release-manifest.json",
  "README.md",
  "scripts/build-manifest.mjs",
  "scripts/build-public-export.mjs",
  "scripts/check-lua.mjs",
  "scripts/check-package.mjs",
  "scripts/check-release.mjs",
  "scripts/fingerprint-properties.mjs",
  "scripts/package-utils.mjs",
  "scripts/stage-native-release.mjs",
  "scripts/verify-download.mjs",
  "scripts/verify-installer-repro.ps1",
  "SHA256SUMS",
  "tests/deploy/fixtures/compose.base.yaml",
  "tests/deploy/test_deploy_contract.mjs",
  "tests/deploy/test_exact_runtime_contract.mjs",
  "tests/fixtures/sandbox-options/checksum-assignment.txt",
  "tests/fixtures/sandbox-options/checksum-option.txt",
  "tests/fixtures/sandbox-options/duplicate-version.txt",
  "tests/fixtures/sandbox-options/missing-version.txt",
  "tests/fixtures/sandbox-options/version-inside-option-indented.txt",
  "tests/fixtures/sandbox-options/version-inside-option-unindented.txt",
  "tests/fixtures/sandbox-options/wrong-version.txt",
  "tests/fixtures/fingerprint/exact-fingerprint.properties",
  "tests/fixtures/fingerprint/hostile-member-suffixes.tsv",
  "tests/print_compatibility_adapters.lua",
  "tests/run.lua",
  "tests/support/assertions.lua",
  "tests/support/fake_pz.lua",
  "tests/test_actions.lua",
  "tests/test_adapters.lua",
  "tests/test_compatibility_adapters.mjs",
  "tests/test_config.lua",
  "tests/test_fingerprint_identity.mjs",
  "tests/test_installer_scope.mjs",
  "tests/test_native_bridge.lua",
  "tests/test_native_only.lua",
  "tests/test_package_tools.mjs",
  "tests/test_production_fingerprint.mjs",
  "tests/test_protocol_core.lua",
  "tests/test_public_docs.mjs",
  "tests/test_public_export.mjs",
  "tests/test_public_workflow.mjs",
  "tests/test_reconciliation.lua",
  "tests/test_release_gates.mjs",
  "tests/test_runtime_round1.lua",
  "tests/test_runtime_round2.lua",
  "tests/test_runtime_round3.lua",
  "tests/test_runtime_round4.lua",
  "tests/test_runtime.lua",
  "tests/test_sandbox_options.mjs",
  "tests/test_vehicles.lua",
  "tests/test_workshop_description.mjs",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/ClientRuntime.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/NativeAssistClient.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/RemoteRenderer.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/client/ApolloMPSync/StateSampler.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/server/ApolloMPSync/NativeAssistServer.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/server/ApolloMPSync/ServerRuntime.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/server/ApolloMPSync/ServerState.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/server/ApolloMPSync/VehicleRepair.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/ActionPolicy.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/ActionRegistry.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/AdaptiveRate.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/Config.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/Protocol.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/RateLimit.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/Reconciliation.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/Sequence.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/TrailerPolicy.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/Validation.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/VehiclePolicy.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/ApolloMPSync/WorkBudget.lua",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/Translate/EN/IG_UI_EN.txt",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/Translate/EN/Sandbox_EN.txt",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/Translate/RU/IG_UI_RU.txt",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/lua/shared/Translate/RU/Sandbox_RU.txt",
  "workshop/Contents/mods/ApolloMPSyncB42/42/media/sandbox-options.txt",
  "workshop/Contents/mods/ApolloMPSyncB42/42/mod.info",
  "workshop/Contents/mods/ApolloMPSyncB42/42/poster.png",
  "workshop/Contents/mods/ApolloMPSyncB42/mod.info",
  "workshop/Contents/mods/ApolloMPSyncB42/poster.png",
  "workshop/preview.png",
  "workshop/workshop.txt",
]);

const APPROVED_DESTINATIONS = new Map([
  ["public/.github/workflows/release.yml", ".github/workflows/release.yml"],
  ["public/.github/workflows/verify.yml", ".github/workflows/verify.yml"],
  ["public/public-export-self.json", "public/public-export.json"],
  ["public/README.md", "README.md"],
]);

function ordinal(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function portableKey(relativePath) {
  return relativePath.normalize("NFC").toLowerCase();
}

function absoluteKey(absolutePath) {
  let value = path.resolve(absolutePath);
  if (process.platform === "win32") {
    value = value.replace(/^\\\\\?\\/, "").toLowerCase();
  }
  return value.replace(/[\\/]+$/, "");
}

function isSameOrInside(candidate, parent) {
  const candidateKey = absoluteKey(candidate);
  const parentKey = absoluteKey(parent);
  return candidateKey === parentKey || candidateKey.startsWith(`${parentKey}${path.sep}`);
}

function pathsOverlap(left, right) {
  return isSameOrInside(left, right) || isSameOrInside(right, left);
}

function validateRelativePath(value, field, index) {
  if (typeof value !== "string" || value.length === 0 || value !== value.normalize("NFC")) {
    throw new Error(`manifest entry ${index} ${field} must be a normalized relative path`);
  }
  if (value.includes("\\") || value.includes("\0") || value.startsWith("/") || path.posix.isAbsolute(value)) {
    throw new Error(`manifest entry ${index} ${field} must be a normalized relative path`);
  }
  const segments = value.split("/");
  if (segments.some((segment) => !segment || segment === "." || segment === ".." ||
    /[\u0000-\u001f\u007f]/u.test(segment) || /[. ]$/u.test(segment) || segment.includes(":"))) {
    throw new Error(`manifest entry ${index} ${field} must be a normalized relative path`);
  }
  if (segments.some((segment) => /^(?:CON|PRN|AUX|NUL|CONIN\$|CONOUT\$|COM(?:[1-9¹²³])|LPT(?:[1-9¹²³]))(?:\.|$)/iu.test(segment))) {
    throw new Error(`manifest entry ${index} ${field} uses a reserved device name`);
  }
  if (path.posix.normalize(value) !== value) {
    throw new Error(`manifest entry ${index} ${field} must be a normalized relative path`);
  }
  return value;
}

function hasForbiddenSegment(relativePath) {
  const segments = relativePath.split("/").map((segment) => segment.toLowerCase());
  return segments.some((segment) => FORBIDDEN_PATH_SEGMENTS.has(segment)) ||
    segments.some((segment, index) => segment === "evidence" && segments[index - 1] === "docs") ||
    segments.some((segment) => ["bin", "obj", "testresults", "artifacts"].includes(segment));
}

function validateSourceShape(source) {
  if (hasForbiddenSegment(source)) {
    throw new Error(`unallowlisted public export source: ${source}`);
  }
  const extension = path.posix.extname(source).toLowerCase();
  if (FORBIDDEN_BINARY_EXTENSIONS.has(extension) ||
      (extension === ".jar" && source !== GRADLE_WRAPPER_PATH)) {
    throw new Error(`forbidden payload in public export source: ${source}`);
  }
}

function assertNoPathCollisions(paths, label) {
  const byKey = new Map();
  for (const value of paths) {
    const key = portableKey(value);
    if (byKey.has(key)) throw new Error(`${label} collision: ${byKey.get(key)} and ${value}`);
    byKey.set(key, value);
  }
  for (const [key, value] of byKey) {
    const segments = key.split("/");
    for (let length = 1; length < segments.length; length += 1) {
      const ancestor = segments.slice(0, length).join("/");
      if (byKey.has(ancestor)) {
        throw new Error(`${label} collision: ${byKey.get(ancestor)} and ${value}`);
      }
    }
  }
}

function validateManifest(entries) {
  if (!Array.isArray(entries)) throw new Error("public export manifest must be a JSON array");
  if (entries.length === 0) throw new Error("public export manifest must not be empty");
  const normalized = entries.map((entry, index) => {
    if (!entry || typeof entry !== "object" || Array.isArray(entry)) {
      throw new Error(`manifest entry ${index} must be an object`);
    }
    const keys = Object.keys(entry).sort(ordinal);
    if (keys.join(",") !== "destination,kind,source") {
      throw new Error(`manifest entry ${index} must contain only source, destination, and kind`);
    }
    const source = validateRelativePath(entry.source, "source", index);
    const destination = validateRelativePath(entry.destination, "destination", index);
    if (entry.kind !== "file") throw new Error(`manifest entry ${index} kind must be file`);
    validateSourceShape(source);
    if (hasForbiddenSegment(destination)) throw new Error(`forbidden destination path: ${destination}`);
    if (portableKey(destination) === "public-sha256sums") {
      throw new Error(`reserved destination: ${destination}`);
    }
    return { source, destination, kind: "file" };
  });
  assertNoPathCollisions(normalized.map((entry) => entry.source), "source");
  assertNoPathCollisions(normalized.map((entry) => entry.destination), "destination");
  for (const entry of normalized) {
    if (!APPROVED_SOURCES.has(entry.source)) {
      throw new Error(`unallowlisted public export source: ${entry.source}`);
    }
    const approvedDestination = APPROVED_DESTINATIONS.get(entry.source) ?? entry.source;
    if (entry.destination !== approvedDestination) {
      throw new Error(`approved source/destination tuple required: ${entry.source} -> ${entry.destination}`);
    }
  }
  return normalized.sort((left, right) => ordinal(left.destination, right.destination));
}

async function assertUnlinkedPath(absolutePath, description, { finalType = "any" } = {}) {
  const resolvedPath = path.resolve(absolutePath);
  const parsed = path.parse(resolvedPath);
  const relativeSegments = resolvedPath.slice(parsed.root.length).split(path.sep).filter(Boolean);
  let current = parsed.root;
  for (let index = 0; index < relativeSegments.length; index += 1) {
    current = path.join(current, relativeSegments[index]);
    let stats;
    try {
      stats = await lstat(current, { bigint: true });
    } catch (error) {
      if (error?.code === "ENOENT") throw new Error(`missing source ${description}: ${absolutePath}`);
      throw error;
    }
    const final = index === relativeSegments.length - 1;
    if (stats.isSymbolicLink()) throw new Error(`link, junction, or reparse point rejected: ${description}`);
    const physical = await realpath(current);
    if (absoluteKey(physical) !== absoluteKey(current)) {
      throw new Error(`link, junction, or reparse point rejected: ${description}`);
    }
    if (!final && !stats.isDirectory()) throw new Error(`non-directory source ancestor: ${description}`);
    if (finalType === "file" && final && !stats.isFile()) throw new Error(`source is not a regular file: ${description}`);
    if (finalType === "directory" && final && !stats.isDirectory()) throw new Error(`source is not a directory: ${description}`);
  }
}

function sameSnapshot(left, right) {
  return left.dev === right.dev && left.ino === right.ino && left.mode === right.mode &&
    left.nlink === right.nlink && left.size === right.size &&
    left.birthtimeNs === right.birthtimeNs && left.ctimeNs === right.ctimeNs &&
    left.mtimeNs === right.mtimeNs;
}

function safeCredentialPlaceholder(value) {
  const trimmed = value.trim();
  return /^(?:""|''|__REQUIRED_AT_RUNTIME__|<REQUIRED_AT_RUNTIME>|REDACTED|null|(["'])(?:__REQUIRED_AT_RUNTIME__|<REQUIRED_AT_RUNTIME>|REDACTED|null)\1)$/u.test(trimmed) ||
    /^\$\{[A-Z][A-Z0-9_]*\}$/u.test(trimmed) ||
    /^\$\{\{\s*github\.token\s*\}\}$/u.test(trimmed) ||
    /^process\.env\.[A-Z][A-Z0-9_]*$/u.test(trimmed) ||
    /^Environment\.GetEnvironmentVariable\(["'][A-Z][A-Z0-9_]*["']\)$/u.test(trimmed);
}

function credentialAssignment(line) {
  const assignment = line.match(/^\s*(?:export\s+)?([A-Z_][A-Z0-9_-]*)\s*[:=]\s*(.*?)\s*[,;]?\s*$/iu) ||
    line.match(/^\s*["']([^"']+)["']\s*:\s*(.*?)\s*[,}]?\s*$/u) ||
    line.match(/^\s*(?:(?:public|private|protected|internal|static|final|readonly|const|let|var)\s+)*(?:(?:String|string|char\[\])\s+)?([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*;?\s*$/iu);
  if (!assignment || !isSensitiveKeyName(assignment[1])) return undefined;
  return { indent: line.match(/^\s*/u)[0].length, value: assignment[2] };
}

function commentOnly(line) {
  return /^\s*(?:(?:#|\/\/|;).*|\/\*.*\*\/)\s*$/u.test(line);
}

function credentialValue(lines, lineIndex, assignment) {
  if (assignment.value.trim()) return assignment.value;
  const chunks = [];
  let byteLength = 0;
  for (let offset = 1; offset <= MAX_SENSITIVE_CONTINUATION_LINES; offset += 1) {
    const continuationIndex = lineIndex + offset;
    if (continuationIndex >= lines.length) break;
    const line = lines[continuationIndex];
    if (!line.trim() || commentOnly(line)) continue;
    const indent = line.match(/^\s*/u)[0].length;
    if (indent <= assignment.indent) break;
    byteLength += Buffer.byteLength(line, "utf8");
    if (byteLength > MAX_SENSITIVE_CONTINUATION_BYTES) return undefined;
    const terminated = /[,;]\s*$/u.test(line);
    chunks.push(line.trim().replace(/[,;]\s*$/u, "").trim());
    if (terminated) return chunks.join(" ");
  }
  return chunks.length > 0 ? chunks.join(" ") : undefined;
}

function assertSafeText(relativePath, bytes, sourceRoot) {
  if (bytes.includes(0)) throw new Error(`binary data in public text source: ${relativePath}`);
  const text = bytes.toString("utf8");
  if (Buffer.from(text, "utf8").compare(bytes) !== 0) throw new Error(`invalid UTF-8 public text source: ${relativePath}`);
  const portableText = text.replaceAll("\\", "/").toLowerCase();
  const portableSourceRoot = path.resolve(sourceRoot).replaceAll("\\", "/").toLowerCase();
  if (portableText.includes(portableSourceRoot) ||
      PRIVATE_TEXT_MARKERS.some((marker) => portableText.includes(marker)) ||
      /docs\/evidence/iu.test(text) ||
      /(?:[A-Z]:|\\\\\?\\[A-Z]:)\\Users\\[^\\/:*?"<>|\r\n]+\\/iu.test(text) ||
      /\/(?:home\/(?!steam(?:\/|$))[^/\s]+|Users\/[^/\s]+|root)(?:\/|$)/u.test(text)) {
    throw new Error(`private path bytes in public export source: ${relativePath}`);
  }
  if (/-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/u.test(text) ||
      /\b[a-z][a-z0-9+.-]*:\/\/[^\s/:@]+:[^\s/@]+@/iu.test(text) ||
      /\bAuthorization\s*[:=]\s*["']?Bearer\s+[A-Za-z0-9._~+/-]{16,}/iu.test(text) ||
      /\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b/u.test(text) ||
      /\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b/u.test(text)) {
    throw new Error(`credential-looking content in public export source: ${relativePath}`);
  }
  const lines = text.split(/\r?\n/u);
  for (const [lineIndex, line] of lines.entries()) {
    const assignment = credentialAssignment(line);
    if (assignment && !safeCredentialPlaceholder(credentialValue(lines, lineIndex, assignment) ?? "")) {
      throw new Error(`credential-looking content in public export source: ${relativePath}:${lineIndex + 1}`);
    }
  }
}

function assertPayload(relativePath, bytes, sourceRoot) {
  const extension = path.posix.extname(relativePath).toLowerCase();
  if (extension === ".jar") {
    if (relativePath !== GRADLE_WRAPPER_PATH ||
        bytes.length < 4 || bytes[0] !== 0x50 || bytes[1] !== 0x4b) {
      throw new Error(`forbidden payload in public export source: ${relativePath}`);
    }
    const digest = createHash("sha256").update(bytes).digest("hex");
    if (digest !== GRADLE_WRAPPER_SHA256) {
      throw new Error(`Gradle wrapper SHA-256 does not match pinned public build tool: ${relativePath}`);
    }
    return;
  }
  if (extension === ".png") {
    if (bytes.length < 4 || bytes[0] !== 0x89 || bytes[1] !== 0x50 || bytes[2] !== 0x4e || bytes[3] !== 0x47) {
      throw new Error(`invalid PNG public export source: ${relativePath}`);
    }
    return;
  }
  if (!KNOWN_BINARY_EXTENSIONS.has(extension)) assertSafeText(relativePath, bytes, sourceRoot);
}

async function assertExactSourceSpelling(sourceRoot, relativePath) {
  let current = sourceRoot;
  for (const segment of relativePath.split("/")) {
    const names = (await readdir(current)).filter((name) => portableKey(name) === portableKey(segment));
    if (!names.includes(segment)) {
      if (names.length > 0) throw new Error(`source path alias rejected: ${relativePath}`);
      throw new Error(`missing source ${relativePath}: ${path.join(current, segment)}`);
    }
    if (names.length !== 1) throw new Error(`source collision: ${relativePath}`);
    current = path.join(current, segment);
  }
}

async function readRegularFileWithoutFollowing(sourceRoot, relativePath, afterSourceOpen) {
  const absolutePath = path.join(sourceRoot, ...relativePath.split("/"));
  await assertExactSourceSpelling(sourceRoot, relativePath);
  await assertUnlinkedPath(absolutePath, relativePath, { finalType: "file" });
  const before = await lstat(absolutePath, { bigint: true });
  if (before.nlink !== 1n) throw new Error(`hard link or unexpected link count rejected: ${relativePath}`);
  const handle = await open(absolutePath, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
  try {
    const openedBefore = await handle.stat({ bigint: true });
    const pathBefore = await lstat(absolutePath, { bigint: true });
    if (!openedBefore.isFile() || openedBefore.nlink !== 1n ||
        !sameSnapshot(before, openedBefore) || !sameSnapshot(openedBefore, pathBefore)) {
      throw new Error(`unstable source snapshot before read: ${relativePath}`);
    }
    await assertUnlinkedPath(absolutePath, relativePath, { finalType: "file" });
    if (afterSourceOpen) await afterSourceOpen({ relativePath, absolutePath });
    const bytes = await handle.readFile();
    const openedAfter = await handle.stat({ bigint: true });
    await assertExactSourceSpelling(sourceRoot, relativePath);
    await assertUnlinkedPath(absolutePath, relativePath, { finalType: "file" });
    const pathAfter = await lstat(absolutePath, { bigint: true });
    if (!sameSnapshot(openedBefore, openedAfter) || !sameSnapshot(openedAfter, pathAfter) ||
        openedAfter.size !== BigInt(bytes.length)) {
      throw new Error(`source changed during snapshot read: ${relativePath}`);
    }
    assertPayload(relativePath, bytes, sourceRoot);
    return { bytes, snapshot: openedAfter };
  } finally {
    await handle.close();
  }
}

async function walkGovernedTree(sourceRoot, relativeRoot) {
  const absoluteRoot = path.join(sourceRoot, ...relativeRoot.split("/"));
  await assertUnlinkedPath(absoluteRoot, relativeRoot, { finalType: "directory" });
  const files = [];
  async function visit(absoluteDirectory, relativeDirectory) {
    const entries = await readdir(absoluteDirectory, { withFileTypes: true });
    entries.sort((left, right) => ordinal(left.name, right.name));
    for (const entry of entries) {
      const relativePath = `${relativeDirectory}/${entry.name}`;
      const absolutePath = path.join(absoluteDirectory, entry.name);
      const stats = await lstat(absolutePath, { bigint: true });
      if (entry.isSymbolicLink() || stats.isSymbolicLink()) {
        throw new Error(`link, junction, or reparse point rejected: ${relativePath}`);
      }
      const physical = await realpath(absolutePath);
      if (absoluteKey(physical) !== absoluteKey(absolutePath)) {
        throw new Error(`link, junction, or reparse point rejected: ${relativePath}`);
      }
      if (stats.isDirectory() && GENERATED_DIRECTORY_SEGMENTS.has(entry.name.toLowerCase())) continue;
      if (stats.isDirectory()) await visit(absolutePath, relativePath);
      else if (stats.isFile()) files.push(relativePath);
      else throw new Error(`unsupported source material: ${relativePath}`);
    }
  }
  await visit(absoluteRoot, relativeRoot);
  return files;
}

async function assertNoUnexpectedSourceMaterial(sourceRoot, entries) {
  const listed = new Set(entries.map((entry) => portableKey(entry.source)));
  for (const governedRoot of GOVERNED_TREES) {
    if (!entries.some((entry) => entry.source === governedRoot || entry.source.startsWith(`${governedRoot}/`))) continue;
    const governedFiles = await walkGovernedTree(sourceRoot, governedRoot);
    assertNoPathCollisions(governedFiles, "source");
    for (const relativePath of governedFiles) {
      if (!listed.has(portableKey(relativePath))) {
        throw new Error(`unexpected source material in governed tree: ${relativePath}`);
      }
    }
  }
}

async function removeOutput(outputRoot) {
  try {
    const stats = await lstat(outputRoot);
    if (stats.isSymbolicLink()) {
      await rm(outputRoot, { force: true });
      throw new Error(`output root was a link, junction, or reparse point: ${outputRoot}`);
    }
    if (!stats.isDirectory()) throw new Error(`output root is not a directory: ${outputRoot}`);
    await rm(outputRoot, { recursive: true, force: true });
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
}

async function prepareOutputParent(outputRoot) {
  const parent = path.dirname(outputRoot);
  await mkdir(parent, { recursive: true });
  await assertUnlinkedPath(parent, "output parent", { finalType: "directory" });
}

async function writePublicChecksums(outputRoot, files) {
  const lines = [...files]
    .sort((left, right) => ordinal(left.destination, right.destination))
    .map(({ destination, bytes }) => `${createHash("sha256").update(bytes).digest("hex")}  ${destination}`);
  await writeFile(path.join(outputRoot, "PUBLIC-SHA256SUMS"), `${lines.join("\n")}\n`, { mode: 0o644 });
}

async function assertSourceStillMatchesSnapshot(sourceRoot, file) {
  const absolutePath = path.join(sourceRoot, ...file.source.split("/"));
  await assertExactSourceSpelling(sourceRoot, file.source);
  await assertUnlinkedPath(absolutePath, file.source, { finalType: "file" });
  const current = await lstat(absolutePath, { bigint: true });
  if (!current.isFile() || current.nlink !== 1n || !sameSnapshot(current, file.snapshot)) {
    throw new Error(`source changed after snapshot read: ${file.source}`);
  }
}

function assertSafeOutputLocation(sourceRoot, outputRoot, manifestPath, entries) {
  const filesystemRoot = path.parse(outputRoot).root;
  if (absoluteKey(outputRoot) === absoluteKey(filesystemRoot) || isSameOrInside(sourceRoot, outputRoot)) {
    throw new Error("source and output collision: unsafe output root");
  }
  if (isSameOrInside(outputRoot, sourceRoot)) {
    const permittedGeneratedOutput = path.join(sourceRoot, "build", "public-export");
    if (absoluteKey(outputRoot) !== absoluteKey(permittedGeneratedOutput)) {
      throw new Error("source and output collision: only build/public-export is permitted inside sourceRoot");
    }
  }
  if (pathsOverlap(outputRoot, manifestPath)) {
    throw new Error("output overlap with public export manifest");
  }
  for (const entry of entries) {
    const source = path.join(sourceRoot, ...entry.source.split("/"));
    if (pathsOverlap(outputRoot, source)) {
      throw new Error(`source and output collision: output overlaps listed source ${entry.source}`);
    }
  }
  for (const governedRoot of GOVERNED_TREES) {
    const governed = path.join(sourceRoot, ...governedRoot.split("/"));
    if (pathsOverlap(outputRoot, governed)) {
      throw new Error(`source and output collision: output overlaps governed source ${governedRoot}`);
    }
  }
}

export async function buildPublicExport({ sourceRoot, outputRoot, manifestPath, _testHooks } = {}) {
  if (![sourceRoot, outputRoot, manifestPath].every((value) => typeof value === "string" && value.length > 0)) {
    throw new Error("sourceRoot, outputRoot, and manifestPath are required");
  }
  const absoluteSourceRoot = path.resolve(sourceRoot);
  const absoluteOutputRoot = path.resolve(outputRoot);
  const absoluteManifestPath = path.resolve(manifestPath);
  if (_testHooks !== undefined &&
      (!_testHooks || typeof _testHooks !== "object" ||
       (_testHooks.afterSourceOpen !== undefined && typeof _testHooks.afterSourceOpen !== "function") ||
       (_testHooks.afterAllSourcesRead !== undefined && typeof _testHooks.afterAllSourcesRead !== "function"))) {
    throw new Error("invalid public export test hook configuration");
  }
  await assertUnlinkedPath(absoluteSourceRoot, "source root", { finalType: "directory" });
  let parsed;
  try {
    parsed = JSON.parse(await readFile(absoluteManifestPath, "utf8"));
  } catch (error) {
    throw new Error(`invalid public export manifest JSON: ${error.message}`, { cause: error });
  }
  const entries = validateManifest(parsed);
  await assertNoUnexpectedSourceMaterial(absoluteSourceRoot, entries);
  const files = [];
  for (const entry of entries) {
    const snapshot = await readRegularFileWithoutFollowing(
      absoluteSourceRoot,
      entry.source,
      _testHooks?.afterSourceOpen,
    );
    files.push({
      ...entry,
      ...snapshot,
    });
  }
  if (_testHooks?.afterAllSourcesRead) await _testHooks.afterAllSourcesRead();
  for (const file of files) await assertSourceStillMatchesSnapshot(absoluteSourceRoot, file);
  assertSafeOutputLocation(absoluteSourceRoot, absoluteOutputRoot, absoluteManifestPath, entries);

  await prepareOutputParent(absoluteOutputRoot);
  await removeOutput(absoluteOutputRoot);
  const stagingRoot = path.join(path.dirname(absoluteOutputRoot), `.${path.basename(absoluteOutputRoot)}.tmp-${process.pid}-${randomUUID()}`);
  try {
    await rm(stagingRoot, { recursive: true, force: true });
    await mkdir(stagingRoot);
    for (const file of files) {
      const destination = path.join(stagingRoot, ...file.destination.split("/"));
      await mkdir(path.dirname(destination), { recursive: true });
      await writeFile(destination, file.bytes, { flag: "wx", mode: 0o644 });
    }
    await writePublicChecksums(stagingRoot, files);
    await rename(stagingRoot, absoluteOutputRoot);
    return absoluteOutputRoot;
  } catch (error) {
    await rm(stagingRoot, { recursive: true, force: true }).catch(() => {});
    await removeOutput(absoluteOutputRoot).catch(() => {});
    throw error;
  }
}

async function main() {
  const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const outputRoot = path.join(projectRoot, "build", "public-export");
  const manifestPath = path.join(projectRoot, "public", "public-export.json");
  await buildPublicExport({ sourceRoot: projectRoot, outputRoot, manifestPath });
  process.stdout.write(`Public export written to ${outputRoot}\n`);
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    process.stderr.write(`${error.stack ?? error.message}\n`);
    process.exitCode = 1;
  });
}
