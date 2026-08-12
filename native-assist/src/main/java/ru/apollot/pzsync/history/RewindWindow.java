package ru.apollot.pzsync.history;

import java.util.Objects;

public record RewindWindow(long requestedMs, boolean historyAllowed, String reasonCode) {
    private static final int MAX_REASON_LENGTH = 64;

    public RewindWindow {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (requestedMs < 0 || requestedMs > RewindClock.HARD_CAP_MS) {
            throw new IllegalArgumentException("requestedMs is outside the immutable hard cap");
        }
        if (historyAllowed && requestedMs > RewindClock.NORMAL_CAP_MS) {
            throw new IllegalArgumentException("history request is outside the normal cap");
        }
        if (reasonCode.isBlank() || reasonCode.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reasonCode must be non-blank and bounded");
        }
        if (!historyAllowed && requestedMs != 0) {
            throw new IllegalArgumentException("strict current-state results cannot request history");
        }
    }
}
