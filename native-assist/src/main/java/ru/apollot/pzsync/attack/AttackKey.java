package ru.apollot.pzsync.attack;

public record AttackKey(
        long sessionNonce, int connectionGeneration, short playerOnlineId, long counter) {
    public AttackKey {
        if (connectionGeneration <= 0) {
            throw new IllegalArgumentException("connectionGeneration must be positive");
        }
        if (playerOnlineId < 0) {
            throw new IllegalArgumentException("playerOnlineId must be non-negative");
        }
        if (counter <= 0) {
            throw new IllegalArgumentException("counter must be positive");
        }
    }
}
