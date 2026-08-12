package ru.apollot.pzsync.gate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FingerprintLoader {
    private static final String CLASS_HASH_PREFIX = "classHashes.";
    private static final String METHOD_DESCRIPTOR_PREFIX = "methodDescriptors.";
    private static final String IDENTITY_KEY = "fingerprintSha256";
    private static final List<String> ENTRYPOINT_FIELDS = List.of(
            "path", "kind", "mode", "sha256");
    private static final List<String> BEFORE_IDENTITY = List.of(
            "appId", "buildId", "gameVersionRevision", "serverJarSha256",
            "nativeLibrarySha256", "agentSha256");
    private static final List<String> AFTER_IDENTITY = List.of("jvmFeature", "os", "arch");
    private static final List<String> TRAILING = List.of("workshopId", "luaModId", "bridgeProtocol");
    private static final List<String> REQUIRED_SCALARS = List.of(
            "appId", "buildId", "gameVersionRevision", "serverJarSha256",
            "nativeLibrarySha256", "agentSha256", "imageReference",
            "originalEntrypointCount", "imageCmd", "runtimeLockMode",
            IDENTITY_KEY, "jvmFeature",
            "os", "arch", "workshopId", "luaModId", "bridgeProtocol");
    private static final Set<String> SCALAR_FIELDS = Set.of(
            "appId", "buildId", "gameVersionRevision", "serverJarSha256",
            "nativeLibrarySha256", "agentSha256", "imageReference",
            "originalEntrypointCount", "imageCmd", "runtimeLockMode",
            IDENTITY_KEY, "jvmFeature",
            "os", "arch", "workshopId", "luaModId", "bridgeProtocol");
    private static final String PRODUCTION_SERVER_JAR_SHA256 =
            "09a80a46e4febe9b436c0f4ec539bdfe9e9113b673eeaf8db22415ac34bef416";
    private static final String PRODUCTION_NATIVE_MANIFEST_SHA256 =
            "86dcfd62671e7a8618c9bbba8433a82425b9c2e896a635c4f21aa70de17108ba";
    private static final String PRODUCTION_IMAGE_REFERENCE =
            "ghcr.io/renegade-master/zomboid-dedicated-server@sha256:"
                    + "5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951";
    private static final List<RuntimeFingerprint.EntrypointElement> PRODUCTION_ENTRYPOINT =
            List.of(
                    new RuntimeFingerprint.EntrypointElement(
                            "/bin/bash", "file", "0755",
                            "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58"),
                    new RuntimeFingerprint.EntrypointElement(
                            "/home/steam/run_server.sh", "file", "0755",
                            "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8"));

    public RuntimeFingerprint load(Path path) throws IOException {
        return loadBytes(Files.readAllBytes(path));
    }

    public RuntimeFingerprint loadBytes(byte[] bytes) {
        var parsed = parse(bytes.clone());
        var values = parsed.values();
        var classHashes = prefixed(values, CLASS_HASH_PREFIX);
        var methodDescriptors = prefixed(values, METHOD_DESCRIPTOR_PREFIX);

        var fingerprint = new RuntimeFingerprint(
                required(values, "appId"),
                required(values, "buildId"),
                required(values, "gameVersionRevision"),
                required(values, "serverJarSha256"),
                required(values, "nativeLibrarySha256"),
                required(values, "agentSha256"),
                required(values, "imageReference"),
                entrypoint(values),
                required(values, "imageCmd"),
                required(values, "runtimeLockMode"),
                required(values, IDENTITY_KEY),
                integer(values, "jvmFeature"),
                required(values, "os"),
                required(values, "arch"),
                classHashes,
                methodDescriptors,
                required(values, "workshopId"),
                required(values, "luaModId"),
                required(values, "bridgeProtocol"));
        enforceProductionTuple(fingerprint);
        return fingerprint;
    }

    public static String canonicalIdentity(byte[] bytes) {
        return parse(bytes).identity();
    }

    private static Parsed parse(byte[] bytes) {
        var contents = strictUtf8(bytes);
        if (contents.isEmpty() || contents.charAt(contents.length() - 1) != '\n'
                || contents.endsWith("\n\n")) {
            throw new IllegalArgumentException("fingerprint must end with exactly one LF");
        }
        if (contents.charAt(0) == '\ufeff' || contents.indexOf('\r') >= 0
                || contents.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("fingerprint contains forbidden BOM, NUL, or CR bytes");
        }

        var lines = contents.substring(0, contents.length() - 1).split("\n", -1);
        var values = new LinkedHashMap<String, String>();
        var keys = new ArrayList<String>();
        for (var line : lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                throw new IllegalArgumentException("fingerprint comments and blank lines are forbidden");
            }
            var separator = line.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("malformed fingerprint line");
            var key = line.substring(0, separator);
            var value = line.substring(separator + 1);
            if (!isAsciiToken(key) || !isAsciiToken(value) || value.indexOf('=') >= 0) {
                throw new IllegalArgumentException(
                        "fingerprint properties must use canonical ASCII tokens");
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("duplicate fingerprint field: " + key);
            }
            keys.add(key);
        }
        var entrypointCount = canonicalEntrypointCount(values);
        var entrypointKeys = entrypointKeys(entrypointCount);
        rejectUnknownFields(values, entrypointKeys);
        for (var scalar : REQUIRED_SCALARS) required(values, scalar);
        for (var key : entrypointKeys) required(values, key);
        requireCanonicalOrder(keys, entrypointCount);
        if (!"25".equals(required(values, "jvmFeature"))) {
            throw new IllegalArgumentException("fingerprint jvmFeature must use canonical decimal 25");
        }

        var identity = sha256(omitIdentityLine(contents));
        if (!identity.equals(required(values, IDENTITY_KEY))) {
            throw new IllegalArgumentException("stale fingerprintSha256");
        }
        return new Parsed(values, identity);
    }

    private static String strictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("fingerprint must be strict UTF-8", error);
        }
    }

    private static boolean isAsciiToken(String value) {
        if (value.isEmpty()) return false;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character < 0x21 || character > 0x7e) return false;
        }
        return true;
    }

    private static byte[] omitIdentityLine(String contents) {
        var kept = new StringBuilder(contents.length());
        for (var line : contents.substring(0, contents.length() - 1).split("\n", -1)) {
            if (!line.startsWith(IDENTITY_KEY + "=")) kept.append(line).append('\n');
        }
        return kept.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireCanonicalOrder(List<String> actual, int entrypointCount) {
        var expected = new ArrayList<String>();
        expected.addAll(BEFORE_IDENTITY);
        expected.add("imageReference");
        expected.add("originalEntrypointCount");
        for (var index = 0; index < entrypointCount; index++) {
            for (var field : ENTRYPOINT_FIELDS) {
                expected.add("originalEntrypoint." + index + "." + field);
            }
        }
        expected.add("imageCmd");
        expected.add("runtimeLockMode");
        expected.add(IDENTITY_KEY);
        expected.addAll(AFTER_IDENTITY);
        actual.stream().filter(key -> key.startsWith(CLASS_HASH_PREFIX)).sorted()
                .forEach(expected::add);
        actual.stream().filter(key -> key.startsWith(METHOD_DESCRIPTOR_PREFIX)).sorted()
                .forEach(expected::add);
        expected.addAll(TRAILING);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("fingerprint fields are not in strict canonical order");
        }
    }

    private static Map<String, String> prefixed(Map<String, String> values, String prefix) {
        var selected = new LinkedHashMap<String, String>();
        values.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                var encoded = key.substring(prefix.length());
                var decodedKey = decodeCanonicalMemberName(key, encoded);
                if (selected.putIfAbsent(decodedKey, value) != null) {
                    throw new IllegalArgumentException("duplicate fingerprint field: " + prefix + decodedKey);
                }
            }
        });
        return selected;
    }

    private static String decodeCanonicalMemberName(String key, String encoded) {
        if (encoded.isEmpty()) {
            throw new IllegalArgumentException("empty fingerprint member name: " + key);
        }
        var decoded = new StringBuilder(encoded.length());
        for (var index = 0; index < encoded.length(); index++) {
            var character = encoded.charAt(index);
            if (character == '%') {
                if (index + 2 >= encoded.length() || encoded.charAt(index + 1) != '2'
                        || encoded.charAt(index + 2) != '3') {
                    throw new IllegalArgumentException(
                            "non-canonical fingerprint member name: " + key);
                }
                decoded.append('#');
                index += 2;
            } else if (character < 0x21 || character > 0x7e
                    || character == '#' || character == '=') {
                throw new IllegalArgumentException(
                        "non-canonical fingerprint member name: " + key);
            } else {
                decoded.append(character);
            }
        }
        return decoded.toString();
    }

    private static void rejectUnknownFields(
            Map<String, String> values, Set<String> entrypointKeys) {
        for (var key : values.keySet()) {
            if (!SCALAR_FIELDS.contains(key)
                    && !entrypointKeys.contains(key)
                    && !key.startsWith(CLASS_HASH_PREFIX)
                    && !key.startsWith(METHOD_DESCRIPTOR_PREFIX)) {
                throw new IllegalArgumentException("unknown fingerprint field: " + key);
            }
            if (key.startsWith(CLASS_HASH_PREFIX)) {
                decodeCanonicalMemberName(key, key.substring(CLASS_HASH_PREFIX.length()));
            } else if (key.startsWith(METHOD_DESCRIPTOR_PREFIX)) {
                decodeCanonicalMemberName(key, key.substring(METHOD_DESCRIPTOR_PREFIX.length()));
            }
        }
    }

    private static int canonicalEntrypointCount(Map<String, String> values) {
        var value = required(values, "originalEntrypointCount");
        if (!value.matches("(?:[1-9]|[12][0-9]|3[0-2])")) {
            throw new IllegalArgumentException(
                    "fingerprint originalEntrypointCount must be canonical decimal 1..32");
        }
        return Integer.parseInt(value);
    }

    private static Set<String> entrypointKeys(int count) {
        var keys = new java.util.LinkedHashSet<String>();
        for (var index = 0; index < count; index++) {
            for (var field : ENTRYPOINT_FIELDS) {
                keys.add("originalEntrypoint." + index + "." + field);
            }
        }
        return Set.copyOf(keys);
    }

    private static List<RuntimeFingerprint.EntrypointElement> entrypoint(
            Map<String, String> values) {
        var result = new ArrayList<RuntimeFingerprint.EntrypointElement>();
        var count = canonicalEntrypointCount(values);
        for (var index = 0; index < count; index++) {
            var prefix = "originalEntrypoint." + index + ".";
            result.add(new RuntimeFingerprint.EntrypointElement(
                    required(values, prefix + "path"),
                    required(values, prefix + "kind"),
                    required(values, prefix + "mode"),
                    required(values, prefix + "sha256")));
        }
        return List.copyOf(result);
    }

    private static void enforceProductionTuple(RuntimeFingerprint fingerprint) {
        if (!"380870".equals(fingerprint.appId())
                || !"24574884".equals(fingerprint.buildId())
                || !"42.20.2".equals(fingerprint.gameVersionRevision())
                || !"1".equals(fingerprint.methodDescriptors().get(
                        "RUNTIME_ADAPTER|PZ_42_20_2"))) {
            return;
        }
        requireProduction("server JAR SHA-256", fingerprint.serverJarSha256(),
                PRODUCTION_SERVER_JAR_SHA256);
        requireProduction("native manifest SHA-256", fingerprint.nativeLibrarySha256(),
                PRODUCTION_NATIVE_MANIFEST_SHA256);
        requireProduction("image reference", fingerprint.imageReference(),
                PRODUCTION_IMAGE_REFERENCE);
        if (!PRODUCTION_ENTRYPOINT.equals(fingerprint.originalEntrypoint())) {
            throw new IllegalArgumentException("production fingerprint ENTRYPOINT mismatch");
        }
        requireProduction("image CMD", fingerprint.imageCmd(), "null");
        requireProduction("runtime lock mode", fingerprint.runtimeLockMode(), "none-captured");
        if (fingerprint.jvmFeature() != 25 || !"linux".equals(fingerprint.os())
                || !"amd64".equals(fingerprint.arch())) {
            throw new IllegalArgumentException("production fingerprint platform mismatch");
        }
    }

    private static void requireProduction(String label, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("production fingerprint " + label + " mismatch");
        }
    }

    private static String required(Map<String, String> values, String key) {
        var value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing fingerprint field: " + key);
        }
        return value;
    }

    private static int integer(Map<String, String> values, String key) {
        var value = required(values, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("invalid integer fingerprint field: " + key, error);
        }
    }

    private record Parsed(Map<String, String> values, String identity) {
    }
}
