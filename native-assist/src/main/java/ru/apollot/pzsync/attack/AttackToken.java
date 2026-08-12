package ru.apollot.pzsync.attack;

import java.util.Objects;

public record AttackToken(
        AttackKey key, long openedAtMs, WeaponObservation weapon, int nativeMaxHits) {
    public AttackToken {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(weapon, "weapon");
        if (openedAtMs < 0) {
            throw new IllegalArgumentException("openedAtMs must be non-negative");
        }
        if (nativeMaxHits <= 0 || nativeMaxHits > AttackBudget.MAX_TARGETS_PER_ATTACK) {
            throw new IllegalArgumentException("nativeMaxHits is outside the fixed attack bound");
        }
    }
}
