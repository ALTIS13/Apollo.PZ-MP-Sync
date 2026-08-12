package ru.apollot.pzsync.bridge;

import java.util.Objects;
import ru.apollot.pzsync.gate.AssistState;

public record BridgeStatus(
        AssistState state,
        String reasonCode,
        String bridgeProtocol,
        String fingerprintSha256) {
    public BridgeStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(bridgeProtocol, "bridgeProtocol");
        Objects.requireNonNull(fingerprintSha256, "fingerprintSha256");
    }
}
