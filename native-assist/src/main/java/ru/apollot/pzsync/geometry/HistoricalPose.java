package ru.apollot.pzsync.geometry;

import java.util.Objects;
import ru.apollot.pzsync.history.EntityKey;

public record HistoricalPose(
        EntityKey identity,
        long serverTimeMs,
        Vec3 position,
        double yawRadians,
        long ownerEpoch,
        boolean aliveAndEligible) {
    public HistoricalPose {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(position, "position");
        if (serverTimeMs < 0) {
            throw new IllegalArgumentException("serverTimeMs must be non-negative");
        }
        if (!Double.isFinite(yawRadians)) {
            throw new IllegalArgumentException("yawRadians must be finite");
        }
        if (ownerEpoch < 0) {
            throw new IllegalArgumentException("ownerEpoch must be non-negative");
        }
    }
}
