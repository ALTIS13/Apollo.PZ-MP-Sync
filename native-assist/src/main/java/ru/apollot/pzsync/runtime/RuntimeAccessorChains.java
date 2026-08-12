package ru.apollot.pzsync.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Compiles only the already-validated closed adapter chains after target definition. */
final class RuntimeAccessorChains implements ExactAcceptedPacketAccess.TraceAccess {
    private final RuntimeAdapterSpec spec;
    private final ClassLoader targetLoader;
    private volatile Map<RuntimeAdapterSpec.AccessorRole, CompiledChain> compiled = Map.of();
    private volatile Map<SupportKey, CompiledSupport> supports = Map.of();
    private volatile Map<RuntimeAdapterSpec.SupportRole, CompiledSupport> exactSupports = Map.of();
    private volatile Map<RuntimeAdapterSpec.HitVariant, Class<?>> variantClasses = Map.of();

    RuntimeAccessorChains(RuntimeAdapterSpec spec, ClassLoader targetLoader) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.targetLoader = Objects.requireNonNull(targetLoader, "targetLoader");
    }

    synchronized void verifyDefinitions() {
        var ready = new EnumMap<RuntimeAdapterSpec.AccessorRole, CompiledChain>(
                RuntimeAdapterSpec.AccessorRole.class);
        for (var entry : spec.chains().entrySet()) {
            ready.put(entry.getKey(), compile(entry.getValue()));
        }
        var readyExactSupports = new EnumMap<RuntimeAdapterSpec.SupportRole, CompiledSupport>(
                RuntimeAdapterSpec.SupportRole.class);
        for (var entry : spec.supports().entrySet()) {
            readyExactSupports.put(entry.getKey(), compileSupport(entry.getValue()));
        }
        Map<SupportKey, CompiledSupport> readySupports =
                compileA4SupportMembers(readyExactSupports);
        var readyVariants = new EnumMap<RuntimeAdapterSpec.HitVariant, Class<?>>(
                RuntimeAdapterSpec.HitVariant.class);
        for (var entry : spec.variants().entrySet()) {
            try {
                readyVariants.put(entry.getKey(),
                        Class.forName(entry.getValue().ownerClass(), false, targetLoader));
            } catch (ClassNotFoundException error) {
                throw new RuntimeAdapterException("runtime-adapter-definition-access-mismatch");
            }
        }
        compiled = Map.copyOf(ready);
        supports = readySupports;
        exactSupports = Map.copyOf(readyExactSupports);
        variantClasses = Map.copyOf(readyVariants);
    }

    @Override
    public Optional<List<Object>> trace(
            RuntimeAdapterSpec.AccessorRole role, Object receiver, Object[] arguments) {
        CompiledChain chain = compiled.get(role);
        if (chain == null) {
            return Optional.empty();
        }
        Object root = switch (chain.source()) {
            case THIS -> receiver;
            case ARG0 -> argument(arguments, 0);
            case ARG1 -> argument(arguments, 1);
            case ARG2 -> argument(arguments, 2);
            case ARG3 -> argument(arguments, 3);
            case STATIC -> null;
        };
        if (chain.source() != RuntimeAdapterSpec.Source.STATIC && root == null) {
            return Optional.empty();
        }
        var stages = new ArrayList<Object>(chain.steps().size());
        Object value = root;
        try {
            for (CompiledStep step : chain.steps()) {
                if (!step.isStatic()
                        && (value == null || value.getClass() != step.expectedOwner())) {
                    return Optional.empty();
                }
                value = step.isStatic()
                        ? step.handle().invokeWithArguments()
                        : step.handle().invokeWithArguments(value);
                if (value == null) {
                    return Optional.empty();
                }
                stages.add(value);
            }
            return Optional.of(List.copyOf(stages));
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("exact-accessor-invoke-failed", error);
        }
    }

    Optional<List<Object>> traceVariant(
            RuntimeAdapterSpec.AccessorRole role, Object receiver, Object[] arguments) {
        RuntimeAdapterSpec.HitVariant variant = role.hitVariant();
        CompiledChain chain = compiled.get(role);
        Class<?> exactVariant = variant == null ? null : variantClasses.get(variant);
        if (chain == null || exactVariant == null || receiver == null
                || receiver.getClass() != exactVariant) {
            return Optional.empty();
        }
        Object root = receiver;
        var stages = new ArrayList<Object>(chain.steps().size());
        Object value = root;
        try {
            for (int index = 0; index < chain.steps().size(); index++) {
                CompiledStep step = chain.steps().get(index);
                boolean exactOwner = value != null && value.getClass() == step.expectedOwner();
                boolean provenVariantRoot = index == 0
                        && step.expectedOwner().isAssignableFrom(exactVariant);
                if (!step.isStatic() && (!exactOwner && !provenVariantRoot)) {
                    return Optional.empty();
                }
                value = step.isStatic()
                        ? step.handle().invokeWithArguments()
                        : step.handle().invokeWithArguments(value);
                if (value == null) return Optional.empty();
                stages.add(value);
            }
            return Optional.of(List.copyOf(stages));
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("exact-variant-accessor-invoke-failed", error);
        }
    }

    NullableValue traceNullableTerminal(
            RuntimeAdapterSpec.AccessorRole role, Object receiver, Object[] arguments) {
        CompiledChain chain = compiled.get(role);
        if (chain == null) return new NullableValue(false, null);
        Object root = switch (chain.source()) {
            case THIS -> receiver;
            case ARG0 -> argument(arguments, 0);
            case ARG1 -> argument(arguments, 1);
            case ARG2 -> argument(arguments, 2);
            case ARG3 -> argument(arguments, 3);
            case STATIC -> null;
        };
        if (chain.source() != RuntimeAdapterSpec.Source.STATIC && root == null) {
            return new NullableValue(false, null);
        }
        Object value = root;
        try {
            for (int index = 0; index < chain.steps().size(); index++) {
                CompiledStep step = chain.steps().get(index);
                if (!step.isStatic()
                        && (value == null || value.getClass() != step.expectedOwner())) {
                    return new NullableValue(false, null);
                }
                value = step.isStatic()
                        ? step.handle().invokeWithArguments()
                        : step.handle().invokeWithArguments(value);
                if (value == null && index + 1 < chain.steps().size()) {
                    return new NullableValue(false, null);
                }
            }
            return new NullableValue(true, value);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("exact-accessor-invoke-failed", error);
        }
    }

    boolean exactVariant(RuntimeAdapterSpec.HitVariant variant, Object receiver) {
        Class<?> expected = variantClasses.get(variant);
        return expected != null && receiver != null && receiver.getClass() == expected;
    }

    Object support(RuntimeAdapterSpec.SupportRole role, Object receiver) {
        CompiledSupport support = exactSupports.get(role);
        if (support == null || receiver == null || !support.accepts(receiver, false)) {
            return null;
        }
        try {
            return support.handle().invokeWithArguments(receiver);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("exact-support-invoke-failed", error);
        }
    }

    @Override
    public Object field(Object receiver, String name, Class<?> expectedType) {
        Objects.requireNonNull(receiver, "receiver");
        Class<?> primitive = primitive(expectedType);
        CompiledSupport support = supports.get(new SupportKey(false, name, primitive));
        if (support == null || !support.accepts(receiver, true)) {
            return null;
        }
        try {
            return support.handle().invokeWithArguments(receiver);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("exact-field-invoke-failed", error);
        }
    }

    @Override
    public Object virtual0(Object receiver, String name, Class<?> expectedType) {
        Objects.requireNonNull(receiver, "receiver");
        Class<?> primitive = primitive(expectedType);
        CompiledSupport support = supports.get(new SupportKey(true, name, primitive));
        if (support == null || !support.accepts(receiver, true)) {
            return null;
        }
        try {
            return support.handle().invokeWithArguments(receiver);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("exact-method-invoke-failed", error);
        }
    }

    private CompiledChain compile(RuntimeAdapterSpec.AccessorChain chain) {
        var steps = new ArrayList<CompiledStep>(chain.steps().size());
        for (RuntimeAdapterSpec.AccessorStep step : chain.steps()) {
            try {
                Class<?> owner = Class.forName(step.ownerClass(), false, targetLoader);
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
                MethodHandle handle = switch (step.kind()) {
                    case INSTANCE_FIELD -> lookup.findGetter(
                            owner, step.memberName(), fieldType(step.descriptor()));
                    case STATIC_FIELD -> lookup.findStaticGetter(
                            owner, step.memberName(), fieldType(step.descriptor()));
                    case VIRTUAL0 -> lookup.findVirtual(
                            owner,
                            step.memberName(),
                            MethodType.fromMethodDescriptorString(step.descriptor(), targetLoader));
                    case STATIC0 -> lookup.findStatic(
                            owner,
                            step.memberName(),
                            MethodType.fromMethodDescriptorString(step.descriptor(), targetLoader));
                };
                steps.add(new CompiledStep(handle, step.kind().isStatic(), owner));
            } catch (Throwable error) {
                rethrowFatal(error);
                throw new RuntimeAdapterException("runtime-adapter-definition-access-mismatch");
            }
        }
        return new CompiledChain(chain.source(), List.copyOf(steps));
    }

    private Map<SupportKey, CompiledSupport> compileA4SupportMembers(
            Map<RuntimeAdapterSpec.SupportRole, CompiledSupport> exact) {
        if (!ExactAcceptedPacketAccess.isDeclared(spec)) {
            return Map.of();
        }
        try {
            var ready = new java.util.HashMap<SupportKey, CompiledSupport>();
            Class<?> prediction = Class.forName(
                    "zombie.network.fields.character.Prediction", false, targetLoader);
            MethodHandles.Lookup predictionLookup = MethodHandles.privateLookupIn(
                    prediction, MethodHandles.lookup());
            addSupport(ready, false, "y", float.class, prediction,
                    predictionLookup.findGetter(prediction, "y", float.class));
            addSupport(ready, false, "z", byte.class, prediction,
                    predictionLookup.findGetter(prediction, "z", byte.class));
            addSupport(ready, false, "direction", float.class, prediction,
                    predictionLookup.findGetter(prediction, "direction", float.class));

            Class<?> weapon = Class.forName(
                    "zombie.inventory.types.HandWeapon", false, targetLoader);
            MethodHandles.Lookup weaponLookup = MethodHandles.privateLookupIn(
                    weapon, MethodHandles.lookup());
            CompiledSupport inheritedId = exact.get(RuntimeAdapterSpec.SupportRole.WEAPON_ID);
            if (inheritedId == null) {
                addSupport(ready, true, "getID", int.class, weapon,
                        weaponLookup.findVirtual(
                                weapon, "getID", MethodType.methodType(int.class)));
            } else {
                addSupport(ready, true, "getID", int.class, inheritedId);
            }
            addSupport(ready, true, "isRanged", boolean.class, weapon,
                    weaponLookup.findVirtual(
                            weapon, "isRanged", MethodType.methodType(boolean.class)));
            addSupport(ready, true, "getProjectileCount", int.class, weapon,
                    weaponLookup.findVirtual(
                            weapon, "getProjectileCount", MethodType.methodType(int.class)));
            return Map.copyOf(ready);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new RuntimeAdapterException("runtime-adapter-a4-support-mismatch");
        }
    }

    private CompiledSupport compileSupport(RuntimeAdapterSpec.Support support) {
        try {
            Class<?> owner = Class.forName(support.ownerClass(), false, targetLoader);
            Class<?> receiver = support.receiverHierarchyProof().isEmpty()
                    ? owner
                    : Class.forName(
                            support.receiverHierarchyProof().getFirst(), false, targetLoader);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            MethodHandle handle = support.invocation() == RuntimeAdapterSpec.Invocation.STATIC
                    ? lookup.findStatic(
                            owner,
                            support.memberName(),
                            MethodType.fromMethodDescriptorString(
                                    support.descriptor(), targetLoader))
                    : lookup.findVirtual(
                            owner,
                            support.memberName(),
                            MethodType.fromMethodDescriptorString(
                                    support.descriptor(), targetLoader));
            return new CompiledSupport(
                    handle, owner, receiver, !support.receiverHierarchyProof().isEmpty());
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new RuntimeAdapterException("runtime-adapter-a5-support-mismatch");
        }
    }

    private static void addSupport(
            Map<SupportKey, CompiledSupport> target,
            boolean method,
            String name,
            Class<?> scalarType,
            Class<?> owner,
            MethodHandle handle) {
        SupportKey key = new SupportKey(method, name, scalarType);
        if (target.putIfAbsent(key, new CompiledSupport(handle, owner, owner, true)) != null) {
            throw new RuntimeAdapterException("runtime-adapter-a4-support-duplicate");
        }
    }

    private static void addSupport(
            Map<SupportKey, CompiledSupport> target,
            boolean method,
            String name,
            Class<?> scalarType,
            CompiledSupport support) {
        SupportKey key = new SupportKey(method, name, scalarType);
        if (target.putIfAbsent(key, support) != null) {
            throw new RuntimeAdapterException("runtime-adapter-a4-support-duplicate");
        }
    }

    private Class<?> fieldType(String descriptor) {
        return MethodType.fromMethodDescriptorString("()" + descriptor, targetLoader).returnType();
    }

    private static Object argument(Object[] arguments, int index) {
        return arguments != null && index >= 0 && index < arguments.length ? arguments[index] : null;
    }

    private static Class<?> primitive(Class<?> expectedType) {
        if (expectedType == Integer.class) return int.class;
        if (expectedType == Short.class) return short.class;
        if (expectedType == Byte.class) return byte.class;
        if (expectedType == Float.class) return float.class;
        if (expectedType == Double.class) return double.class;
        if (expectedType == Long.class) return long.class;
        if (expectedType == Boolean.class) return boolean.class;
        throw new IllegalArgumentException("unsupported exact scalar type");
    }

    private static void rethrowFatal(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (current instanceof Error fatal
                    && "java.lang.ThreadDeath".equals(current.getClass().getName())) {
                throw fatal;
            }
            current = current.getCause();
        }
    }

    private record CompiledChain(
            RuntimeAdapterSpec.Source source, List<CompiledStep> steps) {}

    private record CompiledStep(
            MethodHandle handle, boolean isStatic, Class<?> expectedOwner) {}

    private record SupportKey(boolean method, String name, Class<?> scalarType) {}

    private record CompiledSupport(
            MethodHandle handle,
            Class<?> declaredOwner,
            Class<?> expectedReceiver,
            boolean exactReceiver) {
        private boolean accepts(Object receiver, boolean forceExact) {
            return receiver != null
                    && ((forceExact || exactReceiver)
                            ? receiver.getClass() == expectedReceiver
                            : declaredOwner.isAssignableFrom(receiver.getClass()));
        }
    }

    record NullableValue(boolean available, Object value) {}
}
