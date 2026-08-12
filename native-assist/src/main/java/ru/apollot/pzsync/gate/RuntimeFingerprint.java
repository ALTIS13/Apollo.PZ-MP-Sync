package ru.apollot.pzsync.gate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record RuntimeFingerprint(
        String appId,
        String buildId,
        String gameVersionRevision,
        String serverJarSha256,
        String nativeLibrarySha256,
        String agentSha256,
        String imageReference,
        List<EntrypointElement> originalEntrypoint,
        String imageCmd,
        String runtimeLockMode,
        String fingerprintSha256,
        int jvmFeature,
        String os,
        String arch,
        Map<String, String> classHashes,
        Map<String, String> methodDescriptors,
        String workshopId,
        String luaModId,
        String bridgeProtocol) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile(".+@sha256:[0-9a-f]{64}");
    private static final Pattern MODE = Pattern.compile("0[0-7]{3}");

    public RuntimeFingerprint {
        appId = required("appId", appId);
        buildId = required("buildId", buildId);
        gameVersionRevision = required("gameVersionRevision", gameVersionRevision);
        serverJarSha256 = hash("serverJarSha256", serverJarSha256);
        nativeLibrarySha256 = hash("nativeLibrarySha256", nativeLibrarySha256);
        agentSha256 = hash("agentSha256", agentSha256);
        imageReference = required("imageReference", imageReference);
        if (!IMAGE_REFERENCE.matcher(imageReference).matches()) {
            throw new IllegalArgumentException(
                    "imageReference must be a digest-pinned image reference");
        }
        Objects.requireNonNull(originalEntrypoint, "originalEntrypoint");
        originalEntrypoint = List.copyOf(originalEntrypoint);
        if (originalEntrypoint.isEmpty() || originalEntrypoint.size() > 32) {
            throw new IllegalArgumentException(
                    "originalEntrypoint must contain 1..32 elements");
        }
        if (!"null".equals(required("imageCmd", imageCmd))) {
            throw new IllegalArgumentException("imageCmd must use canonical null representation");
        }
        if (!"none-captured".equals(required("runtimeLockMode", runtimeLockMode))) {
            throw new IllegalArgumentException(
                    "runtimeLockMode must truthfully be none-captured");
        }
        fingerprintSha256 = hash("fingerprintSha256", fingerprintSha256);
        if (jvmFeature < 1) {
            throw new IllegalArgumentException("jvmFeature must be positive");
        }
        os = required("os", os);
        arch = required("arch", arch);
        classHashes = hashes("classHashes", classHashes);
        methodDescriptors = strings("methodDescriptors", methodDescriptors);
        workshopId = required("workshopId", workshopId);
        luaModId = required("luaModId", luaModId);
        bridgeProtocol = required("bridgeProtocol", bridgeProtocol);
    }

    public record EntrypointElement(String path, String kind, String mode, String sha256) {
        public EntrypointElement {
            path = required("originalEntrypoint.path", path);
            if (!path.startsWith("/") || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("originalEntrypoint.path must be absolute");
            }
            if (!"file".equals(required("originalEntrypoint.kind", kind))) {
                throw new IllegalArgumentException("originalEntrypoint.kind must be file");
            }
            mode = required("originalEntrypoint.mode", mode);
            if (!MODE.matcher(mode).matches()) {
                throw new IllegalArgumentException(
                        "originalEntrypoint.mode must be four-digit octal");
            }
            sha256 = hash("originalEntrypoint.sha256", sha256);
        }
    }

    private static String required(String name, String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String hash(String name, String value) {
        required(name, value);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static Map<String, String> hashes(String name, Map<String, String> values) {
        var copy = strings(name, values);
        copy.forEach((key, value) -> hash(name + "." + key, value));
        return copy;
    }

    private static Map<String, String> strings(String name, Map<String, String> values) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        values.forEach((key, value) -> {
            required(name + " key", key);
            required(name + "." + key, value);
        });
        return Map.copyOf(values);
    }
}
