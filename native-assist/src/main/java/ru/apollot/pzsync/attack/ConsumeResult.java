package ru.apollot.pzsync.attack;

public enum ConsumeResult {
    ACCEPT,
    UNKNOWN,
    EXPIRED,
    REPLAY,
    TARGET_LIMIT,
    WEAPON_MISMATCH
}
