package ru.apollot.pzsync.geometry;

import java.util.Objects;

public record RewindDecision(DecisionCode code) {
    public RewindDecision {
        Objects.requireNonNull(code, "code");
    }

    public boolean accepted() {
        return code == DecisionCode.ACCEPT_REWIND || code == DecisionCode.ACCEPT_CURRENT;
    }
}
