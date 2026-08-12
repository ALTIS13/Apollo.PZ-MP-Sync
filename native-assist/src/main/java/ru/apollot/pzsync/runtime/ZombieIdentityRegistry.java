package ru.apollot.pzsync.runtime;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.apollot.pzsync.history.EntityKey;
import ru.apollot.pzsync.history.EntityKind;
import ru.apollot.pzsync.history.HistoryStore;
import ru.apollot.pzsync.history.HistoryTrack;
import ru.apollot.pzsync.history.StateSample;

/** Bounded zombie reference, generation and observed native-owner registry. */
public final class ZombieIdentityRegistry {
    private static final Object OWNER_UNKNOWN = new Object();
    public static final int DEFAULT_CAPACITY = 1_024;
    public static final int MAX_CAPACITY = 4_096;
    public static final long DEFAULT_DISCONNECTED_TTL_MS = 60_000;
    public static final long FRESH_HISTORY_WARMUP_MS = 200;

    private final int capacity;
    private final long disconnectedTtlMs;
    private final HistoryStore history;
    private final Map<Long, Slot> slots;
    private final long initialOwnerEpoch;
    private long lastIssuedGeneration;
    private long lastTrustedTimeMs = -1;
    private boolean generationExhausted;

    public ZombieIdentityRegistry(HistoryStore history) {
        this(DEFAULT_CAPACITY, DEFAULT_DISCONNECTED_TTL_MS, history);
    }

    public ZombieIdentityRegistry(int capacity, long disconnectedTtlMs, HistoryStore history) {
        this(capacity, disconnectedTtlMs, history, 0);
    }

    ZombieIdentityRegistry(
            int capacity, long disconnectedTtlMs, HistoryStore history, long initialGeneration) {
        this(capacity, disconnectedTtlMs, history, initialGeneration, 0);
    }

    ZombieIdentityRegistry(
            int capacity,
            long disconnectedTtlMs,
            HistoryStore history,
            long initialGeneration,
            long initialOwnerEpoch) {
        if (capacity <= 0
                || capacity > MAX_CAPACITY
                || disconnectedTtlMs <= 0
                || initialGeneration < 0
                || initialOwnerEpoch < 0) {
            throw new IllegalArgumentException("zombie registry bounds are invalid");
        }
        this.capacity = capacity;
        this.disconnectedTtlMs = disconnectedTtlMs;
        this.history = Objects.requireNonNull(history, "history");
        this.lastIssuedGeneration = initialGeneration;
        this.initialOwnerEpoch = initialOwnerEpoch;
        slots = new LinkedHashMap<>(capacity);
    }

    public synchronized Optional<Identity> observe(
            long zombieId, Object zombieIdentity, Object nativeOwnerIdentity, long trustedCurrentTimeMs) {
        return observe(zombieId, zombieIdentity, nativeOwnerIdentity, false, trustedCurrentTimeMs);
    }

    public synchronized Optional<Identity> observeUnknownOwner(
            long zombieId, Object zombieIdentity, long trustedCurrentTimeMs) {
        return observe(zombieId, zombieIdentity, OWNER_UNKNOWN, true, trustedCurrentTimeMs);
    }

    private Optional<Identity> observe(
            long zombieId,
            Object zombieIdentity,
            Object nativeOwnerIdentity,
            boolean unknownObservation,
            long trustedCurrentTimeMs) {
        if (!validId(zombieId)
                || zombieIdentity == null
                || !validTime(trustedCurrentTimeMs)) {
            return Optional.empty();
        }
        prune(trustedCurrentTimeMs);
        Slot existing = slots.get(zombieId);
        if (existing != null && existing.active && existing.zombieIdentity == zombieIdentity) {
            if (unknownObservation) {
                existing.lastObservedTimeMs = trustedCurrentTimeMs;
                lastTrustedTimeMs = trustedCurrentTimeMs;
                return Optional.of(identity(zombieId, existing));
            }
            if (existing.nativeOwnerIdentity == OWNER_UNKNOWN) {
                existing.nativeOwnerIdentity = nativeOwnerIdentity;
            } else if (existing.nativeOwnerIdentity != nativeOwnerIdentity) {
                return Optional.empty();
            }
            existing.lastObservedTimeMs = trustedCurrentTimeMs;
            lastTrustedTimeMs = trustedCurrentTimeMs;
            return Optional.of(identity(zombieId, existing));
        }
        if (existing == null && slots.size() >= capacity) {
            return Optional.empty();
        }
        long generation = issueGeneration();
        if (generation < 0) {
            return Optional.empty();
        }
        if (existing != null) {
            history.invalidate(key(zombieId, existing.generation));
        }
        Slot replacement =
                new Slot(
                        zombieIdentity,
                        nativeOwnerIdentity,
                        generation,
                        initialOwnerEpoch,
                        trustedCurrentTimeMs);
        slots.put(zombieId, replacement);
        lastTrustedTimeMs = trustedCurrentTimeMs;
        return Optional.of(identity(zombieId, replacement));
    }

    public synchronized Optional<OwnerOutcome> observeSuccessfulOwner(
            long zombieId,
            Object zombieIdentity,
            Object ownerBefore,
            Object ownerAfter,
            long trustedCurrentTimeMs) {
        if (!validId(zombieId)
                || zombieIdentity == null
                || !validTime(trustedCurrentTimeMs)) {
            return Optional.empty();
        }
        Slot slot = slots.get(zombieId);
        if (slot == null
                || !slot.active
                || slot.zombieIdentity != zombieIdentity) {
            return Optional.empty();
        }
        boolean ownerUnknown = slot.nativeOwnerIdentity == OWNER_UNKNOWN;
        if (!ownerUnknown && slot.nativeOwnerIdentity != ownerBefore) return Optional.empty();
        if (ownerBefore == ownerAfter) {
            if (ownerUnknown) slot.nativeOwnerIdentity = ownerBefore;
            slot.lastObservedTimeMs = trustedCurrentTimeMs;
            lastTrustedTimeMs = trustedCurrentTimeMs;
            return Optional.of(
                    new OwnerOutcome(identity(zombieId, slot), false, false, -1));
        }
        if (slot.ownerEpoch == Long.MAX_VALUE) {
            return Optional.empty();
        }
        long priorOwnerEpoch = slot.ownerEpoch;
        long nextOwnerEpoch = priorOwnerEpoch + 1;
        boolean historyBoundaryRecorded = history.recordOwnerHandoff(
                key(zombieId, slot.generation),
                priorOwnerEpoch,
                nextOwnerEpoch,
                trustedCurrentTimeMs);
        slot.ownerEpoch = nextOwnerEpoch;
        slot.nativeOwnerIdentity = ownerAfter;
        slot.handoffAtMs = trustedCurrentTimeMs;
        slot.freshHistoryStartMs = -1;
        slot.lastHistorySampleMs = -1;
        slot.lastObservedTimeMs = trustedCurrentTimeMs;
        if (!historyBoundaryRecorded) {
            history.invalidate(key(zombieId, slot.generation));
        }
        lastTrustedTimeMs = trustedCurrentTimeMs;
        return Optional.of(
                new OwnerOutcome(
                        identity(zombieId, slot),
                        true,
                        !historyBoundaryRecorded,
                        saturatingAdd(trustedCurrentTimeMs, FRESH_HISTORY_WARMUP_MS)));
    }

    synchronized boolean commitHistorySample(Identity identity, StateSample sample) {
        if (identity == null
                || sample == null
                || sample.ownerEpoch() != identity.ownerEpoch()
                || !validTime(sample.serverTimeMs())) {
            return false;
        }
        Slot slot = slots.get(identity.zombieId());
        if (!matches(slot, identity)
                || sample.serverTimeMs() < slot.handoffAtMs
                || sample.serverTimeMs() <= slot.lastHistorySampleMs) {
            return false;
        }
        if (!history.add(key(identity.zombieId(), identity.generation()), sample)) {
            return false;
        }
        recordAcceptedHistoryTime(slot, sample.serverTimeMs());
        return true;
    }

    public synchronized boolean historyEligible(Identity identity, long trustedCurrentTimeMs) {
        if (identity == null || !validTime(trustedCurrentTimeMs)) {
            return false;
        }
        Slot slot = slots.get(identity.zombieId());
        if (!matches(slot, identity)) {
            return false;
        }
        lastTrustedTimeMs = trustedCurrentTimeMs;
        if (slot.handoffAtMs < 0) {
            return true;
        }
        return slot.freshHistoryStartMs >= slot.handoffAtMs
                && slot.lastHistorySampleMs >= slot.freshHistoryStartMs
                && trustedCurrentTimeMs >= slot.lastHistorySampleMs
                && trustedCurrentTimeMs - slot.lastHistorySampleMs
                        <= HistoryTrack.MAX_INTERPOLATION_GAP_MS
                && trustedCurrentTimeMs - slot.freshHistoryStartMs
                        >= FRESH_HISTORY_WARMUP_MS;
    }

    public synchronized boolean disconnect(
            long zombieId, Object zombieIdentity, long trustedCurrentTimeMs) {
        if (!validId(zombieId)
                || zombieIdentity == null
                || !validTime(trustedCurrentTimeMs)) {
            return false;
        }
        Slot slot = slots.get(zombieId);
        if (slot == null || !slot.active || slot.zombieIdentity != zombieIdentity) {
            return false;
        }
        history.invalidate(key(zombieId, slot.generation));
        slot.active = false;
        slot.zombieIdentity = null;
        slot.nativeOwnerIdentity = null;
        slot.lastObservedTimeMs = trustedCurrentTimeMs;
        slot.expiresAtMs = saturatingAdd(trustedCurrentTimeMs, disconnectedTtlMs);
        lastTrustedTimeMs = trustedCurrentTimeMs;
        return true;
    }

    public synchronized Optional<Identity> current(long zombieId, Object zombieIdentity) {
        if (!validId(zombieId) || zombieIdentity == null) {
            return Optional.empty();
        }
        Slot slot = slots.get(zombieId);
        if (slot == null || !slot.active || slot.zombieIdentity != zombieIdentity) {
            return Optional.empty();
        }
        return Optional.of(identity(zombieId, slot));
    }

    public synchronized Snapshot snapshot() {
        int active = 0;
        long ownerChanges = 0;
        for (Slot slot : slots.values()) {
            if (slot.active) active++;
            ownerChanges = saturatingAdd(ownerChanges, slot.ownerEpoch);
        }
        return new Snapshot(
                slots.size(), active, ownerChanges, lastIssuedGeneration, generationExhausted);
    }

    private long issueGeneration() {
        if (lastIssuedGeneration == Long.MAX_VALUE) {
            generationExhausted = true;
            return -1;
        }
        lastIssuedGeneration++;
        return lastIssuedGeneration;
    }

    private void recordAcceptedHistoryTime(Slot slot, long serverTimeMs) {
        if (slot.lastHistorySampleMs < 0
                || serverTimeMs - slot.lastHistorySampleMs
                        > HistoryTrack.MAX_INTERPOLATION_GAP_MS) {
            slot.freshHistoryStartMs = serverTimeMs;
        }
        slot.lastHistorySampleMs = serverTimeMs;
        slot.lastObservedTimeMs = serverTimeMs;
        lastTrustedTimeMs = serverTimeMs;
    }

    private void prune(long trustedCurrentTimeMs) {
        Iterator<Map.Entry<Long, Slot>> iterator = slots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Slot> entry = iterator.next();
            Slot slot = entry.getValue();
            if (!slot.active && trustedCurrentTimeMs > slot.expiresAtMs) {
                history.invalidate(key(entry.getKey(), slot.generation));
                iterator.remove();
            }
        }
    }

    private boolean validTime(long value) {
        return value >= 0 && value >= lastTrustedTimeMs;
    }

    private static boolean validId(long zombieId) {
        return zombieId >= 0 && zombieId < Long.MAX_VALUE;
    }

    private static boolean matches(Slot slot, Identity identity) {
        return slot != null
                && slot.active
                && slot.generation == identity.generation()
                && slot.ownerEpoch == identity.ownerEpoch();
    }

    private static Identity identity(long zombieId, Slot slot) {
        return new Identity(
                zombieId, slot.generation, slot.ownerEpoch, slot.handoffAtMs);
    }

    private static EntityKey key(long zombieId, long generation) {
        return new EntityKey(EntityKind.ZOMBIE, zombieId, generation);
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    public record Identity(
            long zombieId, long generation, long ownerEpoch, long ownerEpochStartedAtMs) {
        public Identity {
            if (zombieId < 0
                    || zombieId == Long.MAX_VALUE
                    || generation <= 0
                    || ownerEpoch < 0
                    || ownerEpochStartedAtMs < -1) {
                throw new IllegalArgumentException("zombie identity is invalid");
            }
        }
    }

    public record OwnerOutcome(
            Identity identity,
            boolean changed,
            boolean invalidateHistory,
            long earliestHistoryEligibleAtMs) {
        public OwnerOutcome {
            Objects.requireNonNull(identity, "identity");
            if ((!changed && invalidateHistory)
                    || (changed && earliestHistoryEligibleAtMs < 0)
                    || (!changed && earliestHistoryEligibleAtMs != -1)) {
                throw new IllegalArgumentException("owner outcome is inconsistent");
            }
        }
    }

    public record Snapshot(
            int retainedEntries,
            int activeEntries,
            long observedOwnerChanges,
            long lastIssuedGeneration,
            boolean generationExhausted) {}

    private static final class Slot {
        private Object zombieIdentity;
        private Object nativeOwnerIdentity;
        private final long generation;
        private long ownerEpoch;
        private long handoffAtMs = -1;
        private long freshHistoryStartMs = -1;
        private long lastHistorySampleMs = -1;
        private long lastObservedTimeMs;
        private long expiresAtMs = Long.MAX_VALUE;
        private boolean active = true;

        private Slot(
                Object zombieIdentity,
                Object nativeOwnerIdentity,
                long generation,
                long ownerEpoch,
                long lastObservedTimeMs) {
            this.zombieIdentity = zombieIdentity;
            this.nativeOwnerIdentity = nativeOwnerIdentity;
            this.generation = generation;
            this.ownerEpoch = ownerEpoch;
            this.lastObservedTimeMs = lastObservedTimeMs;
        }
    }
}
