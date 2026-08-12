package ru.apollot.pzsync.attack;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

public final class AttackLedger {
    public static final int DEFAULT_MAX_PLAYERS = 512;
    public static final int MAX_PLAYER_CAPACITY = 1_024;
    public static final int MAX_PENDING_ATTACKS_PER_PLAYER = 8;
    public static final int MAX_ATTACK_RECORDS_PER_PLAYER = 512;
    public static final long DEFAULT_ATTACK_TTL_MS = 2_000;
    public static final long MIN_TOMBSTONE_TTL_MS = 60_000;

    private static final long PROCESS_SESSION_NONCE = new SecureRandom().nextLong();

    private final long sessionNonce;
    private final int maxPlayers;
    private final long attackTtlMs;
    private final long tombstoneTtlMs;
    private final int initialConnectionGeneration;
    private final long initialCounter;
    private final Map<Short, PlayerState> players;

    public AttackLedger() {
        this(
                PROCESS_SESSION_NONCE,
                DEFAULT_MAX_PLAYERS,
                DEFAULT_ATTACK_TTL_MS,
                MIN_TOMBSTONE_TTL_MS,
                1,
                0);
    }

    AttackLedger(
            long sessionNonce,
            int maxPlayers,
            long attackTtlMs,
            long tombstoneTtlMs,
            int initialConnectionGeneration,
            long initialCounter) {
        if (maxPlayers <= 0 || maxPlayers > MAX_PLAYER_CAPACITY) {
            throw new IllegalArgumentException("maxPlayers is outside the fixed capacity bound");
        }
        if (attackTtlMs <= 0) {
            throw new IllegalArgumentException("attackTtlMs must be positive");
        }
        if (tombstoneTtlMs < MIN_TOMBSTONE_TTL_MS) {
            throw new IllegalArgumentException("tombstoneTtlMs must be at least 60 seconds");
        }
        if (initialConnectionGeneration <= 0) {
            throw new IllegalArgumentException("initialConnectionGeneration must be positive");
        }
        if (initialCounter < 0) {
            throw new IllegalArgumentException("initialCounter must be non-negative");
        }
        this.sessionNonce = sessionNonce;
        this.maxPlayers = maxPlayers;
        this.attackTtlMs = attackTtlMs;
        this.tombstoneTtlMs = tombstoneTtlMs;
        this.initialConnectionGeneration = initialConnectionGeneration;
        this.initialCounter = initialCounter;
        players = new LinkedHashMap<>(maxPlayers);
    }

    public synchronized OptionalInt beginConnection(short playerOnlineId) {
        if (playerOnlineId < 0) {
            return OptionalInt.empty();
        }

        PlayerState state = players.get(playerOnlineId);
        if (state == null) {
            if (players.size() >= maxPlayers) {
                return OptionalInt.empty();
            }
            state = new PlayerState(initialConnectionGeneration, initialCounter);
            players.put(playerOnlineId, state);
            return OptionalInt.of(state.connectionGeneration);
        }

        state.attacks.clear();
        if (state.connectionGeneration == Integer.MAX_VALUE) {
            state.active = false;
            return OptionalInt.empty();
        }
        state.connectionGeneration++;
        state.active = true;
        state.lastObservedTimeMs = -1;
        return OptionalInt.of(state.connectionGeneration);
    }

    /** Clears retained server attack authorization without resetting attack identity state. */
    public synchronized void clearForServerDeauthorization() {
        for (PlayerState state : players.values()) {
            state.attacks.clear();
        }
    }

    public synchronized Optional<AttackToken> openFromNativeAttackCollisionCheck(
            short playerOnlineId,
            int connectionGeneration,
            long openedAtMs,
            WeaponObservation weapon,
            AttackBudget budget) {
        if (playerOnlineId < 0 || openedAtMs < 0 || weapon == null || budget == null) {
            return Optional.empty();
        }
        PlayerState state = players.get(playerOnlineId);
        if (state == null
                || !state.active
                || state.connectionGeneration != connectionGeneration
                || openedAtMs < state.lastObservedTimeMs
                || state.lastIssuedCounter == Long.MAX_VALUE) {
            return Optional.empty();
        }
        prune(state, openedAtMs);
        if (state.attacks.size() >= MAX_ATTACK_RECORDS_PER_PLAYER
                || pendingAttackCount(state, openedAtMs) >= MAX_PENDING_ATTACKS_PER_PLAYER) {
            return Optional.empty();
        }
        int nativeMaxHits = budget.nativeMaxHitsFor(weapon);
        if (nativeMaxHits <= 0) {
            return Optional.empty();
        }

        long counter = state.lastIssuedCounter + 1;
        AttackKey key =
                new AttackKey(sessionNonce, connectionGeneration, playerOnlineId, counter);
        AttackToken token = new AttackToken(key, openedAtMs, weapon, nativeMaxHits);
        state.lastIssuedCounter = counter;
        state.lastObservedTimeMs = openedAtMs;
        state.attacks.put(
                key,
                new AttackEntry(
                        token,
                        saturatingAdd(openedAtMs, attackTtlMs),
                        saturatingAdd(
                                saturatingAdd(openedAtMs, attackTtlMs),
                                tombstoneTtlMs)));
        return Optional.of(token);
    }

    public synchronized ConsumeResult consume(
            AttackKey key,
            TargetGeneration target,
            long observedAtMs,
            WeaponObservation weapon) {
        if (key == null || target == null) {
            return ConsumeResult.UNKNOWN;
        }
        PlayerState state = players.get(key.playerOnlineId());
        if (key.sessionNonce() != sessionNonce || state == null) {
            return ConsumeResult.UNKNOWN;
        }
        if (!state.active || key.connectionGeneration() != state.connectionGeneration) {
            return ConsumeResult.REPLAY;
        }
        boolean invalidClock = observedAtMs < 0 || observedAtMs < state.lastObservedTimeMs;
        if (!invalidClock) {
            prune(state, observedAtMs);
        }
        AttackEntry entry = state.attacks.get(key);
        if (entry == null) {
            return key.counter() <= state.lastIssuedCounter
                    ? ConsumeResult.REPLAY
                    : ConsumeResult.UNKNOWN;
        }
        if (entry.consumedTargets.contains(target)) {
            return ConsumeResult.REPLAY;
        }
        if (entry.consumedTargets.size() >= entry.token.nativeMaxHits()) {
            entry.closed = true;
            return ConsumeResult.TARGET_LIMIT;
        }

        entry.consumedTargets.add(target);
        if (entry.consumedTargets.size() >= entry.token.nativeMaxHits()) {
            entry.closed = true;
        }
        if (invalidClock) {
            return ConsumeResult.UNKNOWN;
        }
        entry.retainUntilMs =
                Math.max(entry.retainUntilMs, saturatingAdd(observedAtMs, tombstoneTtlMs));
        state.lastObservedTimeMs = observedAtMs;
        if (observedAtMs > entry.expiresAtMs) {
            entry.closed = true;
            return ConsumeResult.EXPIRED;
        }
        if (!entry.token.weapon().equals(weapon)) {
            return ConsumeResult.WEAPON_MISMATCH;
        }
        return ConsumeResult.ACCEPT;
    }

    private static int pendingAttackCount(PlayerState state, long nowMs) {
        int pending = 0;
        for (AttackEntry entry : state.attacks.values()) {
            if (!entry.closed && nowMs <= entry.expiresAtMs) {
                pending++;
            }
        }
        return pending;
    }

    private static void prune(PlayerState state, long nowMs) {
        Iterator<AttackEntry> iterator = state.attacks.values().iterator();
        while (iterator.hasNext()) {
            AttackEntry entry = iterator.next();
            if (nowMs > entry.retainUntilMs) {
                iterator.remove();
            } else if (nowMs > entry.expiresAtMs) {
                entry.closed = true;
            }
        }
    }

    private static long saturatingAdd(long value, long increment) {
        if (Long.MAX_VALUE - value < increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private static final class PlayerState {
        private int connectionGeneration;
        private long lastIssuedCounter;
        private long lastObservedTimeMs = -1;
        private boolean active = true;
        private final Map<AttackKey, AttackEntry> attacks = new LinkedHashMap<>();

        private PlayerState(int connectionGeneration, long lastIssuedCounter) {
            this.connectionGeneration = connectionGeneration;
            this.lastIssuedCounter = lastIssuedCounter;
        }
    }

    private static final class AttackEntry {
        private final AttackToken token;
        private final long expiresAtMs;
        private long retainUntilMs;
        private boolean closed;
        private final Set<TargetGeneration> consumedTargets =
                new LinkedHashSet<>(AttackBudget.MAX_TARGETS_PER_ATTACK);

        private AttackEntry(AttackToken token, long expiresAtMs, long retainUntilMs) {
            this.token = token;
            this.expiresAtMs = expiresAtMs;
            this.retainUntilMs = retainUntilMs;
        }
    }
}
