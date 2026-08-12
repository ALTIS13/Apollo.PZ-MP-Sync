package ru.apollot.pzsync.history;

public record StateSample(
        long serverTimeMs, double x, double y, int floor, double yaw, long ownerEpoch) {
    public StateSample {
        if (serverTimeMs < 0) {
            throw new IllegalArgumentException("serverTimeMs must not be negative");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(yaw)) {
            throw new IllegalArgumentException("coordinates and yaw must be finite");
        }
        if (ownerEpoch < 0) {
            throw new IllegalArgumentException("ownerEpoch must not be negative");
        }
    }
}
