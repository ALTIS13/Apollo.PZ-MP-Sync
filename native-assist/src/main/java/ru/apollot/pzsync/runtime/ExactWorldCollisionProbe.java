package ru.apollot.pzsync.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.apollot.pzsync.geometry.CollisionProbe;
import ru.apollot.pzsync.geometry.HistoricalPose;

/** Current-world-only topology checks backed by the two literal A2 capabilities. */
final class ExactWorldCollisionProbe implements CollisionProbe {
    private final RuntimeAdapterSpec spec;
    private final RuntimeAccessorChains access;
    private final ClassLoader targetLoader;
    private volatile MethodHandle squareLookup;
    private volatile MethodHandle lineOfSight;
    private volatile Class<?> serverMapClass;

    ExactWorldCollisionProbe(
            RuntimeAdapterSpec spec, RuntimeAccessorChains access, ClassLoader targetLoader) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.access = Objects.requireNonNull(access, "access");
        this.targetLoader = Objects.requireNonNull(targetLoader, "targetLoader");
    }

    synchronized void verifyDefinitions() {
        squareLookup = compile(RuntimeAdapterSpec.CapabilityRole.WORLD_SQUARES_LOADED);
        lineOfSight = compile(RuntimeAdapterSpec.CapabilityRole.WORLD_LINE_OF_SIGHT);
        try {
            serverMapClass = Class.forName("zombie.network.ServerMap", false, targetLoader);
        } catch (ClassNotFoundException error) {
            throw new RuntimeAdapterException("runtime-adapter-a5-world-mismatch");
        }
    }

    @Override
    public boolean squaresLoaded(HistoricalPose attacker, HistoricalPose target) {
        Coordinates coordinates = coordinates(attacker, target);
        if (coordinates == null) return false;
        Object map = staticValue(RuntimeAdapterSpec.AccessorRole.WORLD_SERVER_MAP);
        if (map == null || serverMapClass == null || map.getClass() != serverMapClass
                || squareLookup == null) {
            return false;
        }
        return square(map, coordinates.ax(), coordinates.ay(), coordinates.az()) != null
                && square(map, coordinates.tx(), coordinates.ty(), coordinates.tz()) != null;
    }

    @Override
    public boolean currentLineOfSight(HistoricalPose attacker, HistoricalPose target) {
        Coordinates coordinates = coordinates(attacker, target);
        if (coordinates == null) return false;
        Object map = staticValue(RuntimeAdapterSpec.AccessorRole.WORLD_SERVER_MAP);
        if (map == null || serverMapClass == null || map.getClass() != serverMapClass
                || squareLookup == null || lineOfSight == null) {
            return false;
        }
        Object attackerSquare = square(map, coordinates.ax(), coordinates.ay(), coordinates.az());
        Object targetSquare = square(map, coordinates.tx(), coordinates.ty(), coordinates.tz());
        if (attackerSquare == null || targetSquare == null) return false;
        Object cell = access.support(RuntimeAdapterSpec.SupportRole.SQUARE_CELL, attackerSquare);
        if (cell == null) return false;
        Object observed;
        try {
            observed = lineOfSight.invokeWithArguments(
                    cell,
                    coordinates.ax(), coordinates.ay(), coordinates.az(),
                    coordinates.tx(), coordinates.ty(), coordinates.tz(),
                    false);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("exact-world-los-invoke-failed", error);
        }
        Object clear = staticValue(RuntimeAdapterSpec.AccessorRole.WORLD_LOS_CLEAR);
        Object openDoor = staticValue(RuntimeAdapterSpec.AccessorRole.WORLD_LOS_OPEN_DOOR);
        return observed != null && (observed == clear || observed == openDoor);
    }

    private Object square(Object map, int x, int y, int z) {
        try {
            return squareLookup.invokeWithArguments(map, x, y, z);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new IllegalStateException("exact-world-square-invoke-failed", error);
        }
    }

    private Object staticValue(RuntimeAdapterSpec.AccessorRole role) {
        Optional<List<Object>> trace = access.trace(role, null, new Object[0]);
        return trace.isPresent() && trace.orElseThrow().size() == 1
                ? trace.orElseThrow().getFirst()
                : null;
    }

    private MethodHandle compile(RuntimeAdapterSpec.CapabilityRole role) {
        RuntimeAdapterSpec.Capability capability = spec.capabilities().get(role);
        if (capability == null) {
            throw new RuntimeAdapterException("runtime-adapter-a5-world-mismatch");
        }
        try {
            Class<?> owner = Class.forName(capability.ownerClass(), false, targetLoader);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
            MethodType type = MethodType.fromMethodDescriptorString(
                    capability.descriptor(), targetLoader);
            return capability.invocation() == RuntimeAdapterSpec.Invocation.STATIC
                    ? lookup.findStatic(owner, capability.memberName(), type)
                    : lookup.findVirtual(owner, capability.memberName(), type);
        } catch (Throwable error) {
            rethrowFatal(error);
            throw new RuntimeAdapterException("runtime-adapter-a5-world-mismatch");
        }
    }

    private static Coordinates coordinates(HistoricalPose attacker, HistoricalPose target) {
        if (attacker == null || target == null) return null;
        double ax = attacker.position().x();
        double ay = attacker.position().y();
        double tx = target.position().x();
        double ty = target.position().y();
        if (!Double.isFinite(ax) || !Double.isFinite(ay)
                || !Double.isFinite(tx) || !Double.isFinite(ty)
                || ax < Integer.MIN_VALUE || ax > Integer.MAX_VALUE
                || ay < Integer.MIN_VALUE || ay > Integer.MAX_VALUE
                || tx < Integer.MIN_VALUE || tx > Integer.MAX_VALUE
                || ty < Integer.MIN_VALUE || ty > Integer.MAX_VALUE) {
            return null;
        }
        return new Coordinates(
                (int) Math.floor(ax), (int) Math.floor(ay), attacker.position().integerFloor(),
                (int) Math.floor(tx), (int) Math.floor(ty), target.position().integerFloor());
    }

    private static void rethrowFatal(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof VirtualMachineError fatal) throw fatal;
            if (current instanceof Error fatal
                    && "java.lang.ThreadDeath".equals(current.getClass().getName())) {
                throw fatal;
            }
            current = current.getCause();
        }
    }

    private record Coordinates(int ax, int ay, int az, int tx, int ty, int tz) {}
}
