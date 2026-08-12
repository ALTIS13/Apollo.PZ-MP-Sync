package ru.apollot.pzsync.zombie;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import ru.apollot.pzsync.history.HistoryTrack;

public final class ZombieAuthorityEpochs {
    public static final long FRESH_HISTORY_WARMUP_MS = 200;
    public static final int DEFAULT_MAX_ZOMBIES = 1_024;
    public static final int MAX_ZOMBIES = 4_096;

    private final int capacity;
    private final long initialEpoch;
    private final Map<Long, AuthorityState> states;
    private long lastTrustedTimeMs = -1;

    public ZombieAuthorityEpochs() {
        this(DEFAULT_MAX_ZOMBIES);
    }

    public ZombieAuthorityEpochs(int capacity) {
        this(capacity, 0);
    }

    ZombieAuthorityEpochs(int capacity, long initialEpoch) {
        if (capacity <= 0 || capacity > MAX_ZOMBIES || initialEpoch < 0) {
            throw new IllegalArgumentException("authority capacity or initial epoch is invalid");
        }
        this.capacity = capacity;
        this.initialEpoch = initialEpoch;
        states = new LinkedHashMap<>(capacity);
    }

    public synchronized OptionalLong register(long zombieId) {
        if (!validId(zombieId)) {
            return OptionalLong.empty();
        }
        AuthorityState existing = states.get(zombieId);
        if (existing != null) {
            return OptionalLong.of(existing.epoch);
        }
        if (states.size() == capacity) {
            return OptionalLong.empty();
        }
        states.put(zombieId, new AuthorityState(initialEpoch));
        return OptionalLong.of(initialEpoch);
    }

    public synchronized OptionalLong epoch(long zombieId) {
        AuthorityState state = states.get(zombieId);
        return state == null ? OptionalLong.empty() : OptionalLong.of(state.epoch);
    }

    synchronized Optional<EpochChange> handoff(long zombieId, long trustedCurrentTimeMs) {
        return handoff(zombieId, trustedCurrentTimeMs, false);
    }

    synchronized Optional<EpochChange> handoffOrRegister(
            long zombieId, long trustedCurrentTimeMs) {
        return handoff(zombieId, trustedCurrentTimeMs, true);
    }

    private Optional<EpochChange> handoff(
            long zombieId, long trustedCurrentTimeMs, boolean registerIfMissing) {
        if (!validId(zombieId) || !validTime(trustedCurrentTimeMs)) {
            return Optional.empty();
        }
        AuthorityState state = states.get(zombieId);
        if (state == null) {
            if (!registerIfMissing || states.size() == capacity || initialEpoch == Long.MAX_VALUE) {
                return Optional.empty();
            }
            state = new AuthorityState(initialEpoch);
        } else if (state.epoch == Long.MAX_VALUE) {
            return Optional.empty();
        }
        if (!states.containsKey(zombieId)) {
            states.put(zombieId, state);
        }
        lastTrustedTimeMs = trustedCurrentTimeMs;
        state.epoch++;
        state.handoffAtMs = trustedCurrentTimeMs;
        state.freshHistoryStartMs = -1;
        state.lastHistorySampleMs = -1;
        return Optional.of(
                new EpochChange(
                        state.epoch,
                        true,
                        saturatingAdd(trustedCurrentTimeMs, FRESH_HISTORY_WARMUP_MS)));
    }

    public synchronized boolean noteHistorySample(
            long zombieId, long ownerEpoch, long serverTimeMs) {
        if (!validId(zombieId) || ownerEpoch < 0 || !validTime(serverTimeMs)) {
            return false;
        }
        AuthorityState state = states.get(zombieId);
        if (state == null
                || ownerEpoch != state.epoch
                || serverTimeMs < state.handoffAtMs
                || serverTimeMs <= state.lastHistorySampleMs) {
            return false;
        }
        lastTrustedTimeMs = serverTimeMs;
        if (state.lastHistorySampleMs < 0
                || serverTimeMs - state.lastHistorySampleMs
                        > HistoryTrack.MAX_INTERPOLATION_GAP_MS) {
            state.freshHistoryStartMs = serverTimeMs;
        }
        state.lastHistorySampleMs = serverTimeMs;
        return true;
    }

    public synchronized boolean historyEligible(
            long zombieId, long ownerEpoch, long trustedCurrentTimeMs) {
        if (!validId(zombieId) || ownerEpoch < 0 || !validTime(trustedCurrentTimeMs)) {
            return false;
        }
        AuthorityState state = states.get(zombieId);
        if (state == null || state.epoch != ownerEpoch) {
            return false;
        }
        lastTrustedTimeMs = trustedCurrentTimeMs;
        return state.freshHistoryStartMs >= state.handoffAtMs
                && state.lastHistorySampleMs >= state.freshHistoryStartMs
                && trustedCurrentTimeMs >= state.lastHistorySampleMs
                && trustedCurrentTimeMs - state.lastHistorySampleMs
                        <= HistoryTrack.MAX_INTERPOLATION_GAP_MS
                && trustedCurrentTimeMs - state.freshHistoryStartMs
                        >= FRESH_HISTORY_WARMUP_MS;
    }

    public synchronized int size() {
        return states.size();
    }

    public int capacity() {
        return capacity;
    }

    private boolean validTime(long trustedCurrentTimeMs) {
        return trustedCurrentTimeMs >= 0 && trustedCurrentTimeMs >= lastTrustedTimeMs;
    }

    private static boolean validId(long zombieId) {
        return zombieId >= 0 && zombieId < Long.MAX_VALUE;
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    public record EpochChange(
            long ownerEpoch, boolean invalidateHistory, long earliestHistoryEligibleAtMs) {
        public EpochChange {
            if (ownerEpoch < 0 || !invalidateHistory || earliestHistoryEligibleAtMs < 0) {
                throw new IllegalArgumentException("epoch change must invalidate history");
            }
        }
    }

    private static final class AuthorityState {
        private long epoch;
        private long handoffAtMs = -1;
        private long freshHistoryStartMs = -1;
        private long lastHistorySampleMs = -1;

        private AuthorityState(long epoch) {
            this.epoch = epoch;
        }
    }
}
