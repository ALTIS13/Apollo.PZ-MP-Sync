package ru.apollot.pzsync.hooks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import net.bytebuddy.jar.asm.Type;
import ru.apollot.pzsync.gate.RuntimeFingerprint;

public record HookDescriptor(
        HookPoint point,
        ReturnPolicy returnPolicy,
        String className,
        String classSha256,
        String methodName,
        String methodDescriptor) {
    private static final Pattern BINARY_CLASS_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern METHOD_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public HookDescriptor {
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(returnPolicy, "returnPolicy");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(classSha256, "classSha256");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(methodDescriptor, "methodDescriptor");
        if (!BINARY_CLASS_NAME.matcher(className).matches()) {
            throw new IllegalArgumentException("hook class name must be exact binary name");
        }
        if (!SHA_256.matcher(classSha256).matches()) {
            throw new IllegalArgumentException("hook class hash must be lowercase SHA-256");
        }
        if (!METHOD_NAME.matcher(methodName).matches()) {
            throw new IllegalArgumentException("hook method name must be exact JVM name");
        }
        try {
            Type methodType = Type.getMethodType(methodDescriptor);
            if (!returnPolicy.matches(methodType.getReturnType())
                    || (point == HookPoint.HIT_GATE)
                            != (returnPolicy == ReturnPolicy.PREVALIDATED_VOID)
                    || (point == HookPoint.BRIDGE_PUBLISH
                            && returnPolicy != ReturnPolicy.VOID_SUCCESS)) {
                throw new IllegalArgumentException(
                        "hook return policy does not match descriptor: " + point);
            }
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null
                    && error.getMessage().startsWith("hook return policy")) {
                throw error;
            }
            throw new IllegalArgumentException("hook method descriptor must be a JVM descriptor", error);
        }
    }

    public boolean nativeAccepted(Object returned) {
        return returnPolicy.accepted(returned);
    }

    public static List<HookDescriptor> requiredFrom(RuntimeFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        var selected = new EnumMap<HookPoint, HookDescriptor>(HookPoint.class);
        fingerprint.methodDescriptors().forEach((key, descriptor) -> {
            int separator = key.indexOf('|');
            if (separator < 0) {
                return;
            }
            String[] parts = key.split("\\|", 3);
            HookPoint point;
            try {
                point = HookPoint.valueOf(parts[0]);
            } catch (IllegalArgumentException error) {
                if (key.startsWith("RUNTIME_BINDINGS_FACTORY|")
                        || key.startsWith("BINDING|")) {
                    return;
                }
                throw new IllegalArgumentException("unknown typed hook descriptor role", error);
            }
            if (parts.length != 3) {
                throw new IllegalArgumentException("missing hook return policy: " + point);
            }
            final ReturnPolicy returnPolicy;
            try {
                returnPolicy = ReturnPolicy.valueOf(parts[1]);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("unknown hook return policy: " + point, error);
            }
            String methodKey = parts[2];
            int methodSeparator = methodKey.lastIndexOf('#');
            if (methodSeparator <= 0 || methodSeparator == methodKey.length() - 1) {
                throw new IllegalArgumentException("malformed typed hook descriptor: " + point);
            }
            String className = methodKey.substring(0, methodSeparator);
            String methodName = methodKey.substring(methodSeparator + 1);
            String classHash = fingerprint.classHashes().get(className);
            if (classHash == null) {
                throw new IllegalArgumentException(
                        "missing class hash for required hook: " + point);
            }
            HookDescriptor hook =
                    new HookDescriptor(
                            point,
                            returnPolicy,
                            className,
                            classHash,
                            methodName,
                            descriptor);
            if (selected.putIfAbsent(point, hook) != null) {
                throw new IllegalArgumentException("duplicate required hook descriptor: " + point);
            }
        });

        var ordered = new ArrayList<HookDescriptor>(HookPoint.values().length);
        for (HookPoint point : HookPoint.values()) {
            HookDescriptor descriptor = selected.get(point);
            if (descriptor == null) {
                throw new IllegalArgumentException("missing required hook descriptor: " + point);
            }
            ordered.add(descriptor);
        }
        long uniqueMethods = ordered.stream()
                .map(value -> value.className()
                        + "#"
                        + value.methodName()
                        + value.methodDescriptor())
                .distinct()
                .count();
        if (uniqueMethods != ordered.size()) {
            throw new IllegalArgumentException("one exact method cannot carry multiple hook roles");
        }
        return List.copyOf(ordered);
    }

    public enum ReturnPolicy {
        VOID_SUCCESS(Type.VOID),
        BOOLEAN_TRUE(Type.BOOLEAN),
        INT_ONE(Type.INT),
        PREVALIDATED_VOID(Type.VOID);

        private final int returnSort;

        ReturnPolicy(int returnSort) {
            this.returnSort = returnSort;
        }

        private boolean matches(Type returnType) {
            return returnType.getSort() == returnSort;
        }

        private boolean accepted(Object returned) {
            return switch (this) {
                case VOID_SUCCESS, PREVALIDATED_VOID -> true;
                case BOOLEAN_TRUE -> Boolean.TRUE.equals(returned);
                case INT_ONE -> returned instanceof Number number && number.intValue() == 1;
            };
        }
    }

    public enum HookPoint {
        PLAYER_STATE,
        ATTACK_OPEN,
        HIT_GATE,
        ZOMBIE_STATE,
        ZOMBIE_OWNERSHIP,
        ZOMBIE_KEYFRAME,
        BRIDGE_PUBLISH
    }
}
