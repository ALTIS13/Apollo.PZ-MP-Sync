package ru.apollot.pzsync.bridge;

/** The one bounded native-assist configuration accepted by the 42.20.2 bridge. */
public final class BridgeConfiguration {
    private BridgeConfiguration() {}

    public static boolean valid(ApolloNativeBridge.Handshake handshake) {
        return handshake != null
                && !handshake.directPlayerCorrection()
                && !handshake.directVehicleCorrection()
                && handshake.historyMs() == 750
                && handshake.combatSampleMs() == 100
                && handshake.maxRewindMs() >= 0
                && handshake.maxRewindMs() <= ApolloNativeBridge.MAX_CONFIGURABLE_REWIND_MS
                && handshake.hardMaxRewindMs() == ApolloNativeBridge.HARD_MAX_REWIND_MS
                && Double.compare(handshake.rttCutoffMs(), 300.0) == 0
                && Double.compare(handshake.jitterCutoffMs(), 50.0) == 0
                && Double.compare(handshake.rangeEpsilonTiles(), 0.25) == 0
                && Double.compare(handshake.divergenceRejectTiles(), 2.5) == 0
                && Double.compare(handshake.combatBubbleInnerRadius(), 8.0) == 0
                && Double.compare(handshake.combatBubbleOuterRadius(), 15.0) == 0
                && handshake.combatBubbleMaxPlayers() == 12
                && handshake.combatBubbleMaxZombies() == 48
                && handshake.diagnosticsIntervalSeconds() == 60;
    }
}
