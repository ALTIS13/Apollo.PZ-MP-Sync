package ru.apollot.pzsync.geometry;

import java.util.Objects;
import ru.apollot.pzsync.attack.AttackBudget;
import ru.apollot.pzsync.attack.WeaponObservation;

public record WeaponProfile(
        WeaponObservation observation,
        double serverRangeTiles,
        double meleeHalfAngleDegrees,
        boolean equipped,
        boolean ammoAuthorized,
        int nativeProjectileBudget) {
    public WeaponProfile {
        Objects.requireNonNull(observation, "observation");
        if (!Double.isFinite(serverRangeTiles) || serverRangeTiles <= 0.0) {
            throw new IllegalArgumentException("serverRangeTiles must be finite and positive");
        }
        if (!Double.isFinite(meleeHalfAngleDegrees)
                || meleeHalfAngleDegrees < 0.0
                || meleeHalfAngleDegrees > 180.0) {
            throw new IllegalArgumentException("meleeHalfAngleDegrees must be between 0 and 180");
        }
        if (nativeProjectileBudget < 0
                || nativeProjectileBudget > AttackBudget.MAX_TARGETS_PER_ATTACK) {
            throw new IllegalArgumentException("nativeProjectileBudget is outside the fixed attack bound");
        }
    }
}
