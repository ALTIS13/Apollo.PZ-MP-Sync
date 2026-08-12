package ru.apollot.pzsync.history;

public record NetworkEstimate(double rttEmaMs, double floorRttMs, double jitterMs) {
    public NetworkEstimate {
        if (!isValid(rttEmaMs) || !isValid(floorRttMs) || !isValid(jitterMs)) {
            throw new IllegalArgumentException("network estimates must be finite and non-negative");
        }
    }

    private static boolean isValid(double value) {
        return Double.isFinite(value) && value >= 0.0;
    }
}
