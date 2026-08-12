package ru.apollot.pzsync.geometry;

import java.util.Objects;
import ru.apollot.pzsync.history.EntityKind;

/** Current-state-only validation input for native attacks without a player token. */
public record CurrentHitRequest(
        long trustedCurrentTimeMs,
        EntityKind attackerKind,
        EntityKind targetKind,
        WeaponProfile weapon,
        HistoricalPose currentAttacker,
        HistoricalPose currentTarget,
        boolean nativeAccepted) {
    public CurrentHitRequest {
        if (trustedCurrentTimeMs < 0) {
            throw new IllegalArgumentException("trustedCurrentTimeMs must be non-negative");
        }
        Objects.requireNonNull(attackerKind, "attackerKind");
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(weapon, "weapon");
        Objects.requireNonNull(currentAttacker, "currentAttacker");
        Objects.requireNonNull(currentTarget, "currentTarget");
    }
}
