package ru.apollot.pzsync.gate;

import java.util.Objects;

public record GateResult(AssistState state, String reasonCode, String detail) {
    public GateResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(detail, "detail");
    }
}
