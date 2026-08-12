package ru.apollot.pzsync.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.apollot.pzsync.bridge.ApolloNativeBridge;
import ru.apollot.pzsync.bridge.BridgeConfiguration;
import ru.apollot.pzsync.bridge.BridgePublisher;
import ru.apollot.pzsync.bridge.BridgeStatus;

/** Closed, atomic publication of the narrow Apollo table into the exact Kahlua runtime. */
final class ExactLuaBridgePublication {
    private static final String GLOBAL = "ApolloNativeAssist";
    private static final long MAX_LUA_EXACT_INTEGER = 9_007_199_254_740_991L;
    private static final List<String> METRIC_KEYS = List.of(
            "hook_transformed_classes",
            "hook_transform_failures",
            "advice_errors_total",
            "advice_errors_player_state",
            "advice_errors_attack_open",
            "advice_errors_hit_gate",
            "advice_errors_zombie_state",
            "advice_errors_zombie_ownership",
            "advice_errors_zombie_keyframe",
            "advice_errors_bridge",
            "advice_errors_thread",
            "hit_gate_requests",
            "hit_gate_accepts",
            "hit_gate_rejections",
            "zombie_state_commits",
            "zombie_owner_changes");
    private static final Set<String> METRIC_KEY_SET = Set.copyOf(METRIC_KEYS);
    private final RuntimeAdapterSpec spec;
    private final RuntimeAccessorChains access;
    private final ClassLoader targetLoader;
    private volatile Compiled compiled;

    ExactLuaBridgePublication(
            RuntimeAdapterSpec spec,
            RuntimeAccessorChains access,
            ClassLoader targetLoader) {
        this.spec = java.util.Objects.requireNonNull(spec, "spec");
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.targetLoader = java.util.Objects.requireNonNull(targetLoader, "targetLoader");
        requireComplete(spec);
    }

    static void requireComplete(RuntimeAdapterSpec spec) {
        RuntimeAdapterSpec.Hook init = spec.hooks().get(RuntimeAdapterSpec.HookRole.LUA_INIT);
        if (init == null || init.invocation() != RuntimeAdapterSpec.Invocation.STATIC
                || init.exactAccess() != 0x0009
                || !"zombie.Lua.LuaManager".equals(init.ownerClass())
                || !"init".equals(init.memberName()) || !"()V".equals(init.descriptor())) {
            throw new RuntimeAdapterException("runtime-adapter-a6-lua-hook-mismatch");
        }
        requireStatic(spec, RuntimeAdapterSpec.AccessorRole.LUA_PLATFORM,
                "platform", "Lse/krka/kahlua/j2se/J2SEPlatform;");
        requireStatic(spec, RuntimeAdapterSpec.AccessorRole.LUA_ENV,
                "env", "Lse/krka/kahlua/vm/KahluaTable;");
        requireCapability(spec, RuntimeAdapterSpec.CapabilityRole.LUA_NEW_TABLE,
                0x0001, "se.krka.kahlua.j2se.J2SEPlatform", "newTable",
                "()Lse/krka/kahlua/vm/KahluaTable;");
        requireCapability(spec, RuntimeAdapterSpec.CapabilityRole.LUA_RAWSET,
                0x0401, "se.krka.kahlua.vm.KahluaTable", "rawset",
                "(Ljava/lang/Object;Ljava/lang/Object;)V");
        requireCapability(spec, RuntimeAdapterSpec.CapabilityRole.LUA_RAWGET,
                0x0401, "se.krka.kahlua.vm.KahluaTable", "rawget",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        requireCapability(spec, RuntimeAdapterSpec.CapabilityRole.LUA_JAVA_FUNCTION_CALL,
                0x0401, "se.krka.kahlua.vm.JavaFunction", "call",
                "(Lse/krka/kahlua/vm/LuaCallFrame;I)I");
        requireCapability(spec, RuntimeAdapterSpec.CapabilityRole.LUA_CALL_FRAME_GET,
                0x0011, "se.krka.kahlua.vm.LuaCallFrame", "get",
                "(I)Ljava/lang/Object;");
        requireCapability(spec, RuntimeAdapterSpec.CapabilityRole.LUA_CALL_FRAME_PUSH,
                0x0001, "se.krka.kahlua.vm.LuaCallFrame", "push",
                "(Ljava/lang/Object;)I");
    }

    private static void requireStatic(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.AccessorRole role,
            String member,
            String descriptor) {
        RuntimeAdapterSpec.AccessorChain chain = spec.chains().get(role);
        if (chain == null || chain.source() != RuntimeAdapterSpec.Source.STATIC
                || chain.steps().size() != 1) {
            throw new RuntimeAdapterException("runtime-adapter-a6-lua-root-mismatch");
        }
        RuntimeAdapterSpec.AccessorStep step = chain.steps().getFirst();
        if (step.index() != 0 || step.kind() != RuntimeAdapterSpec.AccessorKind.STATIC_FIELD
                || step.exactAccess() != 0x0009
                || !"zombie.Lua.LuaManager".equals(step.ownerClass())
                || !member.equals(step.memberName()) || !descriptor.equals(step.descriptor())) {
            throw new RuntimeAdapterException("runtime-adapter-a6-lua-root-mismatch");
        }
    }

    private static void requireCapability(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.CapabilityRole role,
            int access,
            String owner,
            String member,
            String descriptor) {
        RuntimeAdapterSpec.Capability capability = spec.capabilities().get(role);
        if (capability == null || capability.invocation() != RuntimeAdapterSpec.Invocation.VIRTUAL
                || capability.exactAccess() != access
                || !owner.equals(capability.ownerClass())
                || !member.equals(capability.memberName())
                || !descriptor.equals(capability.descriptor())) {
            throw new RuntimeAdapterException("runtime-adapter-a6-lua-capability-mismatch");
        }
    }

    synchronized void verifyDefinitions() {
        try {
            RuntimeAdapterSpec.Capability newTableSpec =
                    spec.capabilities().get(RuntimeAdapterSpec.CapabilityRole.LUA_NEW_TABLE);
            RuntimeAdapterSpec.Capability rawsetSpec =
                    spec.capabilities().get(RuntimeAdapterSpec.CapabilityRole.LUA_RAWSET);
            RuntimeAdapterSpec.Capability rawgetSpec =
                    spec.capabilities().get(RuntimeAdapterSpec.CapabilityRole.LUA_RAWGET);
            RuntimeAdapterSpec.Capability callSpec =
                    spec.capabilities().get(
                            RuntimeAdapterSpec.CapabilityRole.LUA_JAVA_FUNCTION_CALL);
            RuntimeAdapterSpec.Capability getSpec =
                    spec.capabilities().get(RuntimeAdapterSpec.CapabilityRole.LUA_CALL_FRAME_GET);
            RuntimeAdapterSpec.Capability pushSpec =
                    spec.capabilities().get(RuntimeAdapterSpec.CapabilityRole.LUA_CALL_FRAME_PUSH);
            Class<?> platform = Class.forName(newTableSpec.ownerClass(), false, targetLoader);
            Class<?> table = Class.forName(rawsetSpec.ownerClass(), false, targetLoader);
            Class<?> javaFunction = Class.forName(callSpec.ownerClass(), false, targetLoader);
            Class<?> callFrame = Class.forName(getSpec.ownerClass(), false, targetLoader);
            if (!javaFunction.isInterface() || callFrame.isInterface()
                    || java.lang.reflect.Modifier.isAbstract(callFrame.getModifiers())) {
                throw new IllegalStateException("exact Lua callable types unavailable");
            }
            MethodHandle newTable = MethodHandles.privateLookupIn(platform, MethodHandles.lookup())
                    .findVirtual(platform, newTableSpec.memberName(),
                            MethodType.fromMethodDescriptorString(
                                    newTableSpec.descriptor(), targetLoader));
            MethodHandle rawset = MethodHandles.privateLookupIn(table, MethodHandles.lookup())
                    .findVirtual(table, rawsetSpec.memberName(),
                            MethodType.fromMethodDescriptorString(
                                    rawsetSpec.descriptor(), targetLoader));
            MethodHandle rawget = MethodHandles.privateLookupIn(table, MethodHandles.lookup())
                    .findVirtual(table, rawgetSpec.memberName(),
                            MethodType.fromMethodDescriptorString(
                                    rawgetSpec.descriptor(), targetLoader));
            MethodHandle call = MethodHandles.privateLookupIn(javaFunction, MethodHandles.lookup())
                    .findVirtual(javaFunction, callSpec.memberName(),
                            MethodType.fromMethodDescriptorString(
                                    callSpec.descriptor(), targetLoader));
            MethodHandle get = MethodHandles.privateLookupIn(callFrame, MethodHandles.lookup())
                    .findVirtual(callFrame, getSpec.memberName(),
                            MethodType.fromMethodDescriptorString(
                                    getSpec.descriptor(), targetLoader));
            MethodHandle push = MethodHandles.privateLookupIn(callFrame, MethodHandles.lookup())
                    .findVirtual(callFrame, pushSpec.memberName(),
                            MethodType.fromMethodDescriptorString(
                                    pushSpec.descriptor(), targetLoader));
            Method callMethod = javaFunction.getMethod(
                    callSpec.memberName(), callFrame, int.class);
            if (call.type().parameterType(0) != javaFunction
                    || get.type().parameterType(0) != callFrame
                    || push.type().parameterType(0) != callFrame
                    || callMethod.getDeclaringClass() != javaFunction) {
                throw new IllegalStateException("exact Lua callable ABI unavailable");
            }
            compiled = new Compiled(
                    platform, table, javaFunction, callFrame, callMethod,
                    newTable, rawset, rawget, get, push);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new RuntimeAdapterException("runtime-adapter-a6-lua-definition-mismatch");
        }
    }

    void publish(Object receiver, Object[] arguments, BridgePublisher.Exports exports)
            throws Exception {
        Compiled ready = compiled;
        if (ready == null) throw new IllegalStateException("lua definitions not verified");
        Object platform = terminal(RuntimeAdapterSpec.AccessorRole.LUA_PLATFORM);
        Object env = terminal(RuntimeAdapterSpec.AccessorRole.LUA_ENV);
        if (platform == null || platform.getClass() != ready.platformClass()
                || env == null || !ready.tableClass().isInstance(env)) {
            throw new IllegalStateException("exact Lua roots unavailable");
        }
        try {
            Thread publicationThread = Thread.currentThread();
            Object status = callable(
                    ready, platform, publicationThread, Export.STATUS, exports);
            Object metrics = callable(
                    ready, platform, publicationThread, Export.METRICS, exports);
            Object handshake = callable(
                    ready, platform, publicationThread, Export.HANDSHAKE, exports);
            Object temporary = newTable(ready, platform);
            ready.rawset().invokeWithArguments(temporary, "status", status);
            ready.rawset().invokeWithArguments(temporary, "metrics", metrics);
            ready.rawset().invokeWithArguments(temporary, "handshake", handshake);
            ready.rawset().invokeWithArguments(env, GLOBAL, temporary);
        } catch (Throwable error) {
            rethrowFatal(error);
            if (error instanceof Exception exception) throw exception;
            throw new IllegalStateException("exact Lua publication failed", error);
        }
    }

    private Object callable(
            Compiled ready,
            Object platform,
            Thread publicationThread,
            Export export,
            BridgePublisher.Exports exports) {
        return Proxy.newProxyInstance(
                targetLoader,
                new Class<?>[] {ready.javaFunctionClass()},
                (proxy, method, arguments) -> {
                    if (method.equals(ready.callMethod())) {
                        return invokeCallable(
                                ready,
                                platform,
                                publicationThread,
                                export,
                                exports,
                                arguments);
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> arguments != null && arguments.length == 1
                                    && proxy == arguments[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "toString" -> "ApolloNativeAssist." + export.key;
                            default -> throw new IllegalStateException(
                                    "unsupported callable object method");
                        };
                    }
                    throw new IllegalStateException("unexpected exact Lua callable method");
                });
    }

    private static int invokeCallable(
            Compiled ready,
            Object platform,
            Thread publicationThread,
            Export export,
            BridgePublisher.Exports exports,
            Object[] invocation) throws Throwable {
        if (Thread.currentThread() != publicationThread) {
            failClosedAndThrow(
                    exports,
                    "bridge-off-main-thread",
                    new IllegalStateException("Apollo native callable off publication thread"));
        }
        if (invocation == null || invocation.length != 2
                || invocation[0] == null
                || invocation[0].getClass() != ready.callFrameClass()
                || !(invocation[1] instanceof Integer argumentCount)
                || argumentCount != export.argumentCount) {
            throw new IllegalArgumentException("invalid Apollo native callable invocation");
        }
        Object frame = invocation[0];
        boolean handshakeExportStarted = false;
        try {
            Object result;
            if (export == Export.STATUS) {
                result = exports.status().invoke();
            } else if (export == Export.METRICS) {
                result = exports.metrics().invoke();
            } else {
                Object argument = ready.frameGet().invokeWithArguments(frame, 0);
                ApolloNativeBridge.Handshake handshake = handshake(ready, argument);
                handshakeExportStarted = true;
                result = exports.handshake().invoke(handshake);
            }
            Object table = switch (export) {
                case STATUS, HANDSHAKE -> statusTable(ready, platform, result);
                case METRICS -> metricsTable(ready, platform, result);
            };
            Object pushed = ready.framePush().invokeWithArguments(frame, table);
            if (!(pushed instanceof Integer count) || count != 1) {
                throw new IllegalStateException("exact Lua result push failed");
            }
            return 1;
        } catch (Throwable error) {
            rethrowFatal(error);
            if (export == Export.HANDSHAKE && handshakeExportStarted) {
                failClosedAndThrow(exports, "bridge-handshake-boundary-failed", error);
            }
            throw error;
        }
    }

    private static void failClosedAndThrow(
            BridgePublisher.Exports exports, String reasonCode, Throwable failure)
            throws Throwable {
        try {
            exports.failClosed().invoke(reasonCode);
        } catch (Throwable compensationFailure) {
            rethrowFatal(compensationFailure);
            failure.addSuppressed(compensationFailure);
        }
        throw failure;
    }

    private static Object statusTable(Compiled ready, Object platform, Object raw)
            throws Throwable {
        if (raw == null || raw.getClass() != BridgeStatus.class) {
            throw new IllegalStateException("invalid Apollo native status result");
        }
        BridgeStatus status = (BridgeStatus) raw;
        Object table = newTable(ready, platform);
        rawset(ready, table, "state", status.state().name());
        rawset(ready, table, "reasonCode", status.reasonCode());
        rawset(ready, table, "bridgeProtocol", status.bridgeProtocol());
        rawset(ready, table, "fingerprintSha256", status.fingerprintSha256());
        return table;
    }

    private static Object metricsTable(Compiled ready, Object platform, Object raw)
            throws Throwable {
        if (!(raw instanceof Map<?, ?> source) || source.size() != METRIC_KEYS.size()) {
            throw new IllegalStateException("invalid Apollo native metrics result");
        }
        Map<?, ?> snapshot = Map.copyOf(source);
        if (snapshot.size() != METRIC_KEYS.size()
                || !snapshot.keySet().equals(METRIC_KEY_SET)) {
            throw new IllegalStateException("invalid Apollo native metrics result");
        }
        var validated = new LinkedHashMap<String, Long>();
        for (String key : METRIC_KEYS) {
            Object rawValue = snapshot.get(key);
            if (!validMetricKey(key)
                    || rawValue == null || rawValue.getClass() != Long.class) {
                throw new IllegalStateException("invalid Apollo native metric entry");
            }
            long value = (Long) rawValue;
            if (value < 0L || value > MAX_LUA_EXACT_INTEGER) {
                throw new IllegalStateException("invalid Apollo native metric value");
            }
            validated.put(key, value);
        }

        Object table = newTable(ready, platform);
        for (String key : METRIC_KEYS) {
            rawset(ready, table, key, Double.valueOf(validated.get(key)));
        }
        return table;
    }

    private static ApolloNativeBridge.Handshake handshake(Compiled ready, Object raw)
            throws Throwable {
        if (raw == null || !ready.tableClass().isInstance(raw)
                || raw.getClass().getClassLoader() != ready.tableClass().getClassLoader()) {
            throw new IllegalArgumentException("invalid Apollo native handshake table");
        }

        // The exact ABI intentionally has no iterator capability. Only these 25 allowlisted
        // keys can cross; the Lua constructor is closed and discards every other source key.
        String workshopId = stringValue(ready, raw, "workshopId", StringKind.WORKSHOP_ID);
        String luaModId = stringValue(ready, raw, "luaModId", StringKind.MOD_ID);
        String bridgeProtocol = stringValue(
                ready, raw, "bridgeProtocol", StringKind.PROTOCOL);
        boolean nativeAssistEnabled = booleanValue(ready, raw, "nativeAssistEnabled");
        boolean serverRewindEnabled = booleanValue(ready, raw, "serverRewindEnabled");
        boolean pvpRewindEnabled = booleanValue(ready, raw, "pvpRewindEnabled");
        boolean pveRewindEnabled = booleanValue(ready, raw, "pveRewindEnabled");
        boolean playerNativeAssistEnabled = booleanValue(
                ready, raw, "playerNativeAssistEnabled");
        boolean zombieCombatBubbleEnabled = booleanValue(
                ready, raw, "zombieCombatBubbleEnabled");
        boolean vehicleNativeAssistEnabled = booleanValue(
                ready, raw, "vehicleNativeAssistEnabled");
        boolean directPlayerCorrection = booleanValue(
                ready, raw, "directPlayerCorrection");
        boolean directVehicleCorrection = booleanValue(
                ready, raw, "directVehicleCorrection");
        int historyMs = integerValue(ready, raw, "historyMs");
        int combatSampleMs = integerValue(ready, raw, "combatSampleMs");
        int maxRewindMs = integerValue(ready, raw, "maxRewindMs");
        int hardMaxRewindMs = integerValue(ready, raw, "hardMaxRewindMs");
        double rttCutoffMs = doubleValue(ready, raw, "rttCutoffMs");
        double jitterCutoffMs = doubleValue(ready, raw, "jitterCutoffMs");
        double rangeEpsilonTiles = doubleValue(ready, raw, "rangeEpsilonTiles");
        double divergenceRejectTiles = doubleValue(
                ready, raw, "divergenceRejectTiles");
        double combatBubbleInnerRadius = doubleValue(
                ready, raw, "combatBubbleInnerRadius");
        double combatBubbleOuterRadius = doubleValue(
                ready, raw, "combatBubbleOuterRadius");
        int combatBubbleMaxPlayers = integerValue(
                ready, raw, "combatBubbleMaxPlayers");
        int combatBubbleMaxZombies = integerValue(
                ready, raw, "combatBubbleMaxZombies");
        int diagnosticsIntervalSeconds = integerValue(
                ready, raw, "diagnosticsIntervalSeconds");

        var handshake = new ApolloNativeBridge.Handshake(
                workshopId,
                luaModId,
                bridgeProtocol,
                nativeAssistEnabled,
                serverRewindEnabled,
                pvpRewindEnabled,
                pveRewindEnabled,
                playerNativeAssistEnabled,
                zombieCombatBubbleEnabled,
                vehicleNativeAssistEnabled,
                directPlayerCorrection,
                directVehicleCorrection,
                historyMs,
                combatSampleMs,
                maxRewindMs,
                hardMaxRewindMs,
                rttCutoffMs,
                jitterCutoffMs,
                rangeEpsilonTiles,
                divergenceRejectTiles,
                combatBubbleInnerRadius,
                combatBubbleOuterRadius,
                combatBubbleMaxPlayers,
                combatBubbleMaxZombies,
                diagnosticsIntervalSeconds);
        if (!BridgeConfiguration.valid(handshake)) {
            throw new IllegalArgumentException("invalid Apollo native handshake configuration");
        }
        return handshake;
    }

    private static Object handshakeValue(Compiled ready, Object table, String key)
            throws Throwable {
        return ready.rawget().invokeWithArguments(table, key);
    }

    private static String stringValue(
            Compiled ready, Object table, String key, StringKind kind) throws Throwable {
        Object raw = handshakeValue(ready, table, key);
        if (raw == null || raw.getClass() != String.class) {
            throw new IllegalArgumentException("invalid Apollo native handshake string");
        }
        String value = (String) raw;
        if (!kind.canonical(value)) {
            throw new IllegalArgumentException("invalid Apollo native handshake string");
        }
        return value;
    }

    private static boolean booleanValue(Compiled ready, Object table, String key)
            throws Throwable {
        Object raw = handshakeValue(ready, table, key);
        if (raw == null || raw.getClass() != Boolean.class) {
            throw new IllegalArgumentException("invalid Apollo native handshake boolean");
        }
        return (Boolean) raw;
    }

    private static int integerValue(Compiled ready, Object table, String key)
            throws Throwable {
        double value = exactLuaNumber(ready, table, key);
        if (value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid Apollo native handshake integer");
        }
        return (int) value;
    }

    private static double doubleValue(Compiled ready, Object table, String key)
            throws Throwable {
        return exactLuaNumber(ready, table, key);
    }

    private static double exactLuaNumber(Compiled ready, Object table, String key)
            throws Throwable {
        Object raw = handshakeValue(ready, table, key);
        if (raw == null || raw.getClass() != Double.class
                || !Double.isFinite((Double) raw)) {
            throw new IllegalArgumentException("invalid Apollo native handshake number");
        }
        return (Double) raw;
    }

    private static boolean validMetricKey(String key) {
        if (key.isEmpty() || key.length() > 64
                || key.charAt(0) < 'a' || key.charAt(0) > 'z') {
            return false;
        }
        for (int index = 1; index < key.length(); index++) {
            char character = key.charAt(index);
            if ((character < 'a' || character > 'z')
                    && (character < '0' || character > '9')
                    && character != '_') {
                return false;
            }
        }
        return true;
    }

    private static Object newTable(Compiled ready, Object platform) throws Throwable {
        if (platform == null || platform.getClass() != ready.platformClass()) {
            throw new IllegalStateException("exact Lua platform unavailable");
        }
        Object table = ready.newTable().invokeWithArguments(platform);
        if (table == null || !ready.tableClass().isInstance(table)
                || table.getClass().getClassLoader()
                        != ready.tableClass().getClassLoader()) {
            throw new IllegalStateException("exact Lua result table unavailable");
        }
        return table;
    }

    private static void rawset(Compiled ready, Object table, String key, Object value)
            throws Throwable {
        if (value == null) {
            throw new IllegalStateException("null Apollo native Lua value");
        }
        ready.rawset().invokeWithArguments(table, key, value);
    }

    private Object terminal(RuntimeAdapterSpec.AccessorRole role) {
        Optional<List<Object>> trace = access.trace(role, null, new Object[0]);
        return trace.isEmpty() || trace.orElseThrow().isEmpty()
                ? null : trace.orElseThrow().getLast();
    }

    private static void rethrowFatal(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof VirtualMachineError fatal) throw fatal;
            if (current instanceof Error fatal
                    && "java.lang.ThreadDeath".equals(current.getClass().getName())) throw fatal;
            current = current.getCause();
        }
    }

    private record Compiled(
            Class<?> platformClass,
            Class<?> tableClass,
            Class<?> javaFunctionClass,
            Class<?> callFrameClass,
            Method callMethod,
            MethodHandle newTable,
            MethodHandle rawset,
            MethodHandle rawget,
            MethodHandle frameGet,
            MethodHandle framePush) {}

    private enum StringKind {
        WORKSHOP_ID {
            @Override boolean canonical(String value) {
                return value.length() >= 1 && value.length() <= 20 && asciiDigits(value);
            }
        },
        MOD_ID {
            @Override boolean canonical(String value) {
                if (value.isEmpty() || value.length() > 64) return false;
                for (int index = 0; index < value.length(); index++) {
                    char character = value.charAt(index);
                    if (!asciiAlphaNumeric(character)
                            && character != '_' && character != '-') return false;
                }
                return true;
            }
        },
        PROTOCOL {
            @Override boolean canonical(String value) {
                return value.length() >= 1 && value.length() <= 16 && asciiDigits(value)
                        && (value.length() == 1 || value.charAt(0) != '0');
            }
        };

        abstract boolean canonical(String value);

        static boolean asciiDigits(String value) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character < '0' || character > '9') return false;
            }
            return true;
        }

        static boolean asciiAlphaNumeric(char character) {
            return (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9');
        }
    }

    private enum Export {
        STATUS("status", 0),
        METRICS("metrics", 0),
        HANDSHAKE("handshake", 1);

        private final String key;
        private final int argumentCount;

        Export(String key, int argumentCount) {
            this.key = key;
            this.argumentCount = argumentCount;
        }
    }
}
