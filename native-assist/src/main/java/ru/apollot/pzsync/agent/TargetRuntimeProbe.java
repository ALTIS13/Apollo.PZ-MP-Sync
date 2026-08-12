package ru.apollot.pzsync.agent;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import ru.apollot.pzsync.gate.AssistState;
import ru.apollot.pzsync.gate.GateResult;
import ru.apollot.pzsync.gate.RuntimeFingerprint;

final class TargetRuntimeProbe {
    private static final int MAXIMUM_CLASS_BYTES = 16 * 1024 * 1024;
    private static final List<String> SENTINELS = List.of(
            "zombie.characters.NetworkPlayerAI",
            "zombie.network.packets.hit.HitCharacter");

    private TargetRuntimeProbe() {}

    static GateResult evaluate(
            RuntimeFingerprint fingerprint,
            ClassLoader targetLoader,
            ClassLoader systemLoader) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        ClassLoader resourceLoader = targetLoader == null ? systemLoader : targetLoader;
        if (resourceLoader == null) {
            return absent("target-runtime-not-present");
        }

        var observations = new ArrayList<Observation>(SENTINELS.size());
        boolean targetEvidence = false;
        for (String owner : SENTINELS) {
            String expectedHash = fingerprint.classHashes().get(owner);
            if (expectedHash == null) {
                return incompatible("target-runtime-sentinel-fingerprint-missing");
            }
            Observation observation;
            try {
                observation = observe(resourceLoader, owner, expectedHash);
            } catch (TargetEvidenceFailure error) {
                return incompatible("target-runtime-probe-failed");
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (IOException | RuntimeException | LinkageError error) {
                return targetEvidence
                        ? incompatible("target-runtime-probe-failed")
                        : absent("target-runtime-probe-unavailable");
            }
            observations.add(observation);
            targetEvidence |= observation.present();
        }

        long present = observations.stream().filter(Observation::present).count();
        if (present == 0) {
            return absent("target-runtime-not-present");
        }
        if (present != SENTINELS.size()) {
            return incompatible("target-runtime-partial");
        }
        for (Observation observation : observations) {
            if (!observation.hashMatches()) {
                return incompatible("target-runtime-class-hash-mismatch");
            }
        }
        if (targetLoader == null || targetLoader != systemLoader) {
            return incompatible("target-runtime-loader-mismatch");
        }
        String domain = observations.getFirst().domain();
        if (domain == null || observations.stream()
                .anyMatch(observation -> !domain.equals(observation.domain()))) {
            return incompatible("target-runtime-domain-mismatch");
        }
        return new GateResult(
                AssistState.READY,
                "target-runtime-present",
                "exact target runtime resources are present");
    }

    private static Observation observe(
            ClassLoader loader, String owner, String expectedHash) throws IOException {
        String resource = owner.replace('.', '/') + ".class";
        var resources = loader.getResources(resource);
        URL selected = null;
        int count = 0;
        try {
            while (resources.hasMoreElements()) {
                URL candidate = resources.nextElement();
                count++;
                if (candidate == null || count > 1) {
                    throw new TargetEvidenceFailure();
                }
                selected = candidate;
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException | LinkageError error) {
            if (count > 0) {
                throw new TargetEvidenceFailure();
            }
            throw error;
        }
        if (selected == null) {
            return new Observation(false, false, null);
        }
        try {
            String domain = localDomain(selected, resource);
            var connection = selected.openConnection();
            connection.setUseCaches(false);
            byte[] bytes;
            try (var input = connection.getInputStream()) {
                bytes = input.readNBytes(MAXIMUM_CLASS_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAXIMUM_CLASS_BYTES) {
                throw new IOException("target runtime sentinel size is invalid");
            }
            return new Observation(true, expectedHash.equals(sha256(bytes)), domain);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (IOException | RuntimeException | LinkageError error) {
            throw new TargetEvidenceFailure();
        }
    }

    private static String localDomain(URL resourceUrl, String resource) throws IOException {
        if ("jar".equals(resourceUrl.getProtocol())) {
            var connection = (JarURLConnection) resourceUrl.openConnection();
            connection.setUseCaches(false);
            URL jar = connection.getJarFileURL();
            if (!"file".equals(jar.getProtocol())
                    || !resource.equals(connection.getEntryName())) {
                throw new IOException("target runtime sentinel domain is not a local exact JAR");
            }
            return "jar:" + jar.toExternalForm();
        }
        if ("file".equals(resourceUrl.getProtocol())) {
            String external = resourceUrl.toExternalForm();
            if (resourceUrl.getQuery() != null || resourceUrl.getRef() != null
                    || !external.endsWith(resource)) {
                throw new IOException("target runtime sentinel file domain is malformed");
            }
            return external.substring(0, external.length() - resource.length());
        }
        throw new IOException("target runtime sentinel domain is not local");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static GateResult absent(String reasonCode) {
        return new GateResult(
                AssistState.ABSENT, reasonCode, "exact target runtime was not selected");
    }

    private static GateResult incompatible(String reasonCode) {
        return new GateResult(
                AssistState.INCOMPATIBLE, reasonCode, "target runtime probe was not accepted");
    }

    private record Observation(boolean present, boolean hashMatches, String domain) {}

    private static final class TargetEvidenceFailure extends IOException {}
}
