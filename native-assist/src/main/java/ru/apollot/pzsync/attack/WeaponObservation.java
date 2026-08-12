package ru.apollot.pzsync.attack;

import java.util.Objects;

public record WeaponObservation(long instanceId, String attackMode, boolean projectile) {
    public static final int MAX_ATTACK_MODE_LENGTH = 64;

    public WeaponObservation {
        Objects.requireNonNull(attackMode, "attackMode");
        if (instanceId < 0) {
            throw new IllegalArgumentException("instanceId must be non-negative");
        }
        if (attackMode.isBlank() || attackMode.length() > MAX_ATTACK_MODE_LENGTH) {
            throw new IllegalArgumentException("attackMode must be non-blank and bounded");
        }
    }
}
