package ru.apollot.pzsync.gate;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public interface CompatibilityGate {
    GateResult evaluate(RuntimeFingerprint expected, RuntimeFingerprint observed);

    static CompatibilityGate exact(BooleanSupplier enabled) {
        return new ExactCompatibilityGate(enabled);
    }
}

final class ExactCompatibilityGate implements CompatibilityGate {
    private static final String APP_ID = "380870";
    private static final String BUILD_ID = "24574884";
    private static final String GAME_VERSION_REVISION = "42.20.2";
    private static final int JVM_FEATURE = 25;
    private static final String OS = "linux";
    private static final String ARCH = "amd64";
    private static final String WORKSHOP_ID = "3780069702";
    private static final String LUA_MOD_ID = "ApolloMPSyncB42";
    private static final String BRIDGE_PROTOCOL = "1";

    private final BooleanSupplier enabled;

    ExactCompatibilityGate(BooleanSupplier enabled) {
        this.enabled = Objects.requireNonNull(enabled, "enabled");
    }

    @Override
    public GateResult evaluate(RuntimeFingerprint expected, RuntimeFingerprint observed) {
        if (!enabled.getAsBoolean()) {
            return result(AssistState.DISABLED, "kill-switch-off", "native assist is disabled");
        }
        if (expected == null) {
            return result(AssistState.ABSENT, "expected-fingerprint-missing",
                    "expected compatibility fingerprint is absent");
        }
        if (observed == null) {
            return result(AssistState.ABSENT, "observed-fingerprint-missing",
                    "observed runtime fingerprint is absent");
        }

        var canonicalFailure = canonicalFailure(expected);
        if (canonicalFailure != null) {
            return canonicalFailure;
        }

        var mismatch = mismatch("app-id-mismatch", "app ID", expected.appId(), observed.appId());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("build-id-mismatch", "BuildID", expected.buildId(), observed.buildId());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("game-version-revision-mismatch", "game version/revision",
                expected.gameVersionRevision(), observed.gameVersionRevision());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("server-jar-hash-mismatch", "server JAR hash",
                expected.serverJarSha256(), observed.serverJarSha256());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("native-library-hash-mismatch", "native library hash",
                expected.nativeLibrarySha256(), observed.nativeLibrarySha256());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("agent-hash-mismatch", "agent hash",
                expected.agentSha256(), observed.agentSha256());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("fingerprint-hash-mismatch", "fingerprint hash",
                expected.fingerprintSha256(), observed.fingerprintSha256());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("jvm-feature-mismatch", "JVM feature",
                expected.jvmFeature(), observed.jvmFeature());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("os-mismatch", "operating system", expected.os(), observed.os());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("arch-mismatch", "architecture", expected.arch(), observed.arch());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("class-hashes-mismatch", "class hashes",
                expected.classHashes(), observed.classHashes());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("method-descriptors-mismatch", "JVM method descriptors",
                expected.methodDescriptors(), observed.methodDescriptors());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("workshop-id-mismatch", "Workshop ID",
                expected.workshopId(), observed.workshopId());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("lua-mod-id-mismatch", "Lua mod ID",
                expected.luaModId(), observed.luaModId());
        if (mismatch != null) return mismatch;
        mismatch = mismatch("bridge-protocol-mismatch", "bridge protocol",
                expected.bridgeProtocol(), observed.bridgeProtocol());
        if (mismatch != null) return mismatch;

        return result(AssistState.READY, "exact-match", "all compatibility fields matched");
    }

    private GateResult canonicalFailure(RuntimeFingerprint expected) {
        var failure = unsupported("expected-app-id-unsupported", "app ID", APP_ID, expected.appId());
        if (failure != null) return failure;
        failure = unsupported("expected-build-id-unsupported", "BuildID", BUILD_ID, expected.buildId());
        if (failure != null) return failure;
        failure = unsupported("expected-game-version-revision-unsupported", "game version/revision",
                GAME_VERSION_REVISION, expected.gameVersionRevision());
        if (failure != null) return failure;
        failure = unsupported("expected-jvm-feature-unsupported", "JVM feature",
                JVM_FEATURE, expected.jvmFeature());
        if (failure != null) return failure;
        failure = unsupported("expected-os-unsupported", "operating system", OS, expected.os());
        if (failure != null) return failure;
        failure = unsupported("expected-arch-unsupported", "architecture", ARCH, expected.arch());
        if (failure != null) return failure;
        failure = unsupported("expected-workshop-id-unsupported", "Workshop ID",
                WORKSHOP_ID, expected.workshopId());
        if (failure != null) return failure;
        failure = unsupported("expected-lua-mod-id-unsupported", "Lua mod ID",
                LUA_MOD_ID, expected.luaModId());
        if (failure != null) return failure;
        return unsupported("expected-bridge-protocol-unsupported", "bridge protocol",
                BRIDGE_PROTOCOL, expected.bridgeProtocol());
    }

    private GateResult mismatch(String reasonCode, String field, Object expected, Object observed) {
        return Objects.equals(expected, observed)
                ? null
                : result(AssistState.INCOMPATIBLE, reasonCode, field + " did not match");
    }

    private GateResult unsupported(
            String reasonCode, String field, Object supported, Object candidate) {
        return Objects.equals(supported, candidate)
                ? null
                : result(AssistState.INCOMPATIBLE, reasonCode, field + " is not supported");
    }

    private GateResult result(AssistState state, String reasonCode, String detail) {
        return new GateResult(state, reasonCode, detail);
    }
}
