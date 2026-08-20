package ru.apollot.pzsync.agent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import ru.apollot.pzsync.gate.AssistState;
import ru.apollot.pzsync.gate.CompatibilityGate;
import ru.apollot.pzsync.gate.FingerprintLoader;
import ru.apollot.pzsync.gate.GateResult;
import ru.apollot.pzsync.gate.RuntimeFingerprint;
import ru.apollot.pzsync.hooks.HookInstaller;

public final class ApolloNativeAgent {
    private static final String ENABLED_PROPERTY = "apollo.nativeAssist.enabled";
    private static final String EXPECTED_FINGERPRINT_PROPERTY =
            "apollo.nativeAssist.expectedFingerprint";
    private static final String OBSERVED_FINGERPRINT_PROPERTY =
            "apollo.nativeAssist.observedFingerprint";
    private static final String EXPECTED_FINGERPRINT_IDENTITY_PROPERTY =
            "apollo.nativeAssist.expectedFingerprintSha256";

    private ApolloNativeAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        var result = guardedBootstrap(() -> evaluateCompatibility(instrumentation));
        System.err.println(structured(result));
    }

    static GateResult guardedBootstrap(Setup setup) {
        try {
            var result = setup.evaluate();
            if (result == null) {
                return circuitOpen("setup returned no result");
            }
            return result;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Exception | LinkageError error) {
            return circuitOpen("setup failed: " + error.getClass().getName());
        }
    }

    private static GateResult evaluateCompatibility(Instrumentation instrumentation)
            throws Exception {
        return evaluateCompatibility(
                instrumentation,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader());
    }

    static GateResult evaluateCompatibility(
            Instrumentation instrumentation,
            ClassLoader targetLoader,
            ClassLoader systemLoader) throws Exception {
        var gate = CompatibilityGate.exact(
                () -> "true".equals(System.getProperty(ENABLED_PROPERTY)));
        if (!"true".equals(System.getProperty(ENABLED_PROPERTY))) {
            return gate.evaluate(null, null);
        }

        var loader = new FingerprintLoader();
        var expected = load(loader, EXPECTED_FINGERPRINT_PROPERTY);
        var observed = load(loader, OBSERVED_FINGERPRINT_PROPERTY);
        var launcherIdentity = System.getProperty(EXPECTED_FINGERPRINT_IDENTITY_PROPERTY);
        if (launcherIdentity == null || launcherIdentity.isBlank()) {
            return new GateResult(AssistState.INCOMPATIBLE, "fingerprint-identity-missing",
                    "verified launcher fingerprint identity is unavailable");
        }
        if (expected == null || observed == null
                || !launcherIdentity.equals(expected.fingerprintSha256())
                || !launcherIdentity.equals(observed.fingerprintSha256())) {
            return new GateResult(AssistState.INCOMPATIBLE, "fingerprint-identity-mismatch",
                    "verified launcher fingerprint identity does not match runtime fingerprint");
        }
        var result = gate.evaluate(expected, observed);
        if (result.state() != AssistState.READY) {
            return result;
        }
        if ("1".equals(expected.methodDescriptors().get("RUNTIME_ADAPTER|PZ_42_20_3"))) {
            var target = TargetRuntimeProbe.evaluate(
                    expected,
                    targetLoader,
                    systemLoader);
            if (target.state() != AssistState.READY) {
                return target;
            }
        }
        if (instrumentation == null) {
            return new GateResult(
                    AssistState.INCOMPATIBLE,
                    "instrumentation-missing",
                    "JVM instrumentation is unavailable");
        }
        return HookInstaller.bootstrap(
                instrumentation, expected, Thread.currentThread());
    }

    private static RuntimeFingerprint load(FingerprintLoader loader, String property) throws Exception {
        var location = System.getProperty(property);
        if (location == null || location.isBlank()) {
            return null;
        }
        return loader.load(Path.of(location));
    }

    private static GateResult circuitOpen(String detail) {
        return new GateResult(AssistState.CIRCUIT_OPEN, "setup-error", detail);
    }

    private static String structured(GateResult result) {
        return "{\"component\":\"apollo-native-assist\",\"state\":\""
                + result.state()
                + "\",\"reasonCode\":\""
                + escape(result.reasonCode())
                + "\",\"detail\":\""
                + escape(result.detail())
                + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @FunctionalInterface
    interface Setup {
        GateResult evaluate() throws Exception;
    }
}
