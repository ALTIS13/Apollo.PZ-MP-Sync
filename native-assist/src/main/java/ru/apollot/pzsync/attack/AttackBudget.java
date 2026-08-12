package ru.apollot.pzsync.attack;

import java.util.Objects;

public record AttackBudget(int nativeMaxHitCount, int nativeProjectileCount) {
    public static final int MAX_TARGETS_PER_ATTACK = 64;

    public AttackBudget {
        if (nativeMaxHitCount <= 0 || nativeMaxHitCount > MAX_TARGETS_PER_ATTACK) {
            throw new IllegalArgumentException("nativeMaxHitCount is outside the fixed attack bound");
        }
        if (nativeProjectileCount < 0 || nativeProjectileCount > MAX_TARGETS_PER_ATTACK) {
            throw new IllegalArgumentException(
                    "nativeProjectileCount is outside the fixed attack bound");
        }
    }

    public int nativeMaxHitsFor(WeaponObservation weapon) {
        Objects.requireNonNull(weapon, "weapon");
        if (!weapon.projectile()) {
            return nativeMaxHitCount;
        }
        return Math.min(nativeMaxHitCount, nativeProjectileCount);
    }
}
