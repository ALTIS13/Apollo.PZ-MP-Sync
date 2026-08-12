package ru.apollot.pzsync.bridge;

import java.util.Map;
import java.util.Objects;

public final class ApolloNativeBridge {
    public static final int HARD_MAX_REWIND_MS = 200;
    public static final int MAX_CONFIGURABLE_REWIND_MS = 150;
    private final Thread mainThread;
    private final BridgePublisher.Control control;

    ApolloNativeBridge(Thread mainThread, BridgePublisher.Control control) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.control = Objects.requireNonNull(control, "control");
    }

    public BridgeStatus status() {
        if (!onMainThread()) {
            control.bridgeFailure("bridge-off-main-thread");
        }
        return control.status();
    }

    public Map<String, Long> metrics() {
        if (!onMainThread()) {
            control.bridgeFailure("bridge-off-main-thread");
        }
        return control.metrics();
    }

    public BridgeStatus handshake(Handshake handshake) {
        if (!onMainThread()) {
            control.bridgeFailure("bridge-off-main-thread");
            return control.status();
        }
        return control.handshake(handshake);
    }

    private boolean onMainThread() {
        return Thread.currentThread() == mainThread;
    }

    public record Handshake(
            String workshopId,
            String luaModId,
            String bridgeProtocol,
            boolean nativeAssistEnabled,
            boolean serverRewindEnabled,
            boolean pvpRewindEnabled,
            boolean pveRewindEnabled,
            boolean playerNativeAssistEnabled,
            boolean zombieCombatBubbleEnabled,
            boolean vehicleNativeAssistEnabled,
            boolean directPlayerCorrection,
            boolean directVehicleCorrection,
            int historyMs,
            int combatSampleMs,
            int maxRewindMs,
            int hardMaxRewindMs,
            double rttCutoffMs,
            double jitterCutoffMs,
            double rangeEpsilonTiles,
            double divergenceRejectTiles,
            double combatBubbleInnerRadius,
            double combatBubbleOuterRadius,
            int combatBubbleMaxPlayers,
            int combatBubbleMaxZombies,
            int diagnosticsIntervalSeconds) {
        public Handshake {
            Objects.requireNonNull(workshopId, "workshopId");
            Objects.requireNonNull(luaModId, "luaModId");
            Objects.requireNonNull(bridgeProtocol, "bridgeProtocol");
        }
    }
}
