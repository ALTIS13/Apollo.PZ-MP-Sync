package ru.apollot.pzsync.runtime;

import java.util.Optional;
import ru.apollot.pzsync.gate.RuntimeFingerprint;
import ru.apollot.pzsync.geometry.CollisionProbe;
import ru.apollot.pzsync.geometry.HistoricalPose;
import ru.apollot.pzsync.history.HistoryStore;
import ru.apollot.pzsync.hooks.HookInstaller;
import ru.apollot.pzsync.hooks.NativeDecisionAdapter;

/** A2 structural binding. Typed fact extraction is deliberately added by A3-A6. */
public final class ExactRuntimeAdapterBindings {
    private ExactRuntimeAdapterBindings() {}

    public static HookInstaller.ProductionBindings create(
            RuntimeFingerprint fingerprint, ClassLoader targetLoader) {
        RuntimeAdapterSpec spec = RuntimeAdapterSpec.requiredFrom(fingerprint);
        spec.preflight(targetLoader);
        ExactAcceptedPacketAccess.requireComplete(spec);
        ExactHitPacketAccess.requireComplete(spec);
        boolean exactA6 = ExactZombieRuntimeAccess.isDeclared(spec);
        if (exactA6) {
            ExactZombieRuntimeAccess.requireComplete(spec);
            ExactLuaBridgePublication.requireComplete(spec);
        }
        if (ExactAcceptedPacketAccess.isDeclared(spec)) {
            var chains = new RuntimeAccessorChains(spec, targetLoader);
            var players = new PlayerIdentityRegistry();
            var clock = new ServerMonotonicClock();
            var history = new HistoryStore();
            var zombies = new ZombieIdentityRegistry(history);
            ExactLuaBridgePublication lua = exactA6
                    ? new ExactLuaBridgePublication(spec, chains, targetLoader)
                    : null;
            var access = new ExactAcceptedPacketAccess(
                    chains, players, clock);
            if (ExactHitPacketAccess.isDeclared(spec)) {
                NativeDecisionAdapter.PacketAccess packetAccess = new ExactHitPacketAccess(
                        access,
                        chains,
                        players,
                        zombies,
                        clock);
                if (exactA6) {
                    packetAccess = new ExactZombieRuntimeAccess(
                            packetAccess, chains, history, zombies, clock, targetLoader);
                }
                var collision = new ExactWorldCollisionProbe(spec, chains, targetLoader);
                ExactZombieRuntimeAccess zombieAccess = packetAccess
                        instanceof ExactZombieRuntimeAccess exact ? exact : null;
                return HookInstaller.ProductionBindings.exactAdapter(
                        packetAccess,
                        collision,
                        (receiver, arguments, exports) -> {
                            if (lua != null) lua.publish(receiver, arguments, exports);
                        },
                        targetLoader,
                        spec,
                        spec.identity(),
                        () -> {
                            chains.verifyDefinitions();
                            if (zombieAccess != null) zombieAccess.verifyDefinitions();
                            collision.verifyDefinitions();
                            if (lua != null) lua.verifyDefinitions();
                        },
                        history);
            }
            return HookInstaller.ProductionBindings.exactAdapter(
                    access,
                    new UnavailableCollision(),
                    (receiver, arguments, exports) -> {
                        if (lua != null) lua.publish(receiver, arguments, exports);
                    },
                    targetLoader,
                    spec,
                    spec.identity(),
                    () -> {
                        chains.verifyDefinitions();
                        if (lua != null) lua.verifyDefinitions();
                    },
                    history);
        }
        var unavailable = new UnavailableAccess();
        return HookInstaller.ProductionBindings.exactAdapter(
                unavailable, unavailable, (receiver, arguments, exports) -> {}, targetLoader,
                spec, spec.identity());
    }

    private static final class UnavailableCollision implements CollisionProbe {
        @Override public boolean squaresLoaded(HistoricalPose attacker, HistoricalPose target) { return false; }
        @Override public boolean currentLineOfSight(HistoricalPose attacker, HistoricalPose target) { return false; }
    }

    private static final class UnavailableAccess
            implements NativeDecisionAdapter.PacketAccess, CollisionProbe {
        @Override public Optional<NativeDecisionAdapter.PlayerFacts> playerState(Object receiver, Object[] arguments) { return Optional.empty(); }
        @Override public Optional<NativeDecisionAdapter.AttackFacts> nativeAttack(Object receiver, Object[] arguments) { return Optional.empty(); }
        @Override public Optional<NativeDecisionAdapter.HitFacts> nativeHit(Object receiver, Object[] arguments) { return Optional.empty(); }
        @Override public Optional<NativeDecisionAdapter.ZombieStateFacts> zombieState(Object receiver, Object[] arguments) { return Optional.empty(); }
        @Override public Optional<NativeDecisionAdapter.ZombieOwnershipFacts> zombieOwnership(Object receiver, Object[] arguments) { return Optional.empty(); }
        @Override public Optional<NativeDecisionAdapter.ZombieKeyframeFacts> zombieKeyframe(Object receiver, Object[] arguments) { return Optional.empty(); }
        @Override public boolean squaresLoaded(HistoricalPose attacker, HistoricalPose target) { return false; }
        @Override public boolean currentLineOfSight(HistoricalPose attacker, HistoricalPose target) { return false; }
    }
}
