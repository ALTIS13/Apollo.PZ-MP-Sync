package ru.apollot.pzsync.runtime;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Bounded exact-reference player identity and connection-generation registry. */
public final class PlayerIdentityRegistry {
    public static final int DEFAULT_CAPACITY = 512;
    public static final int MAX_CAPACITY = 1_024;
    public static final long DEFAULT_DISCONNECTED_TTL_MS = 60_000;

    private final int capacity;
    private final long disconnectedTtlMs;
    private final Map<Short, Slot> slots;
    private final int[] issuedGenerations = new int[Short.MAX_VALUE + 1];
    private final boolean[] exhaustedGenerations = new boolean[Short.MAX_VALUE + 1];
    private long totalIssuedGenerations;
    private int exhaustedOnlineIds;
    private long lastTrustedTimeMs = -1;

    public PlayerIdentityRegistry() {
        this(DEFAULT_CAPACITY, DEFAULT_DISCONNECTED_TTL_MS);
    }

    public PlayerIdentityRegistry(int capacity, long disconnectedTtlMs) {
        this(capacity, disconnectedTtlMs, 0);
    }

    PlayerIdentityRegistry(int capacity, long disconnectedTtlMs, int initialGeneration) {
        if (capacity <= 0
                || capacity > MAX_CAPACITY
                || disconnectedTtlMs <= 0
                || initialGeneration < 0) {
            throw new IllegalArgumentException("player registry bounds are invalid");
        }
        this.capacity = capacity;
        this.disconnectedTtlMs = disconnectedTtlMs;
        if (initialGeneration != 0) {
            Arrays.fill(issuedGenerations, initialGeneration);
        }
        slots = new LinkedHashMap<>(capacity);
    }

    public synchronized Optional<Identity> observe(
            short onlineId, Object playerIdentity, long trustedCurrentTimeMs) {
        if (!validId(onlineId)
                || playerIdentity == null
                || !validTime(trustedCurrentTimeMs)) {
            return Optional.empty();
        }
        prune(trustedCurrentTimeMs);
        Slot existing = slots.get(onlineId);
        if (existing != null && existing.active && existing.playerIdentity == playerIdentity) {
            existing.lastObservedTimeMs = trustedCurrentTimeMs;
            lastTrustedTimeMs = trustedCurrentTimeMs;
            return Optional.of(new Identity(onlineId, existing.generation));
        }
        for (Map.Entry<Short, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (entry.getKey() != onlineId
                    && slot.active
                    && slot.playerIdentity == playerIdentity) {
                return Optional.empty();
            }
        }
        if (existing == null && slots.size() >= capacity) {
            return Optional.empty();
        }
        int generation = issueGeneration(onlineId);
        if (generation < 0) {
            return Optional.empty();
        }
        Slot replacement = new Slot(playerIdentity, generation, trustedCurrentTimeMs);
        slots.put(onlineId, replacement);
        lastTrustedTimeMs = trustedCurrentTimeMs;
        return Optional.of(new Identity(onlineId, generation));
    }

    public synchronized boolean disconnect(
            short onlineId, Object playerIdentity, long trustedCurrentTimeMs) {
        if (!validId(onlineId)
                || playerIdentity == null
                || !validTime(trustedCurrentTimeMs)) {
            return false;
        }
        Slot slot = slots.get(onlineId);
        if (slot == null || !slot.active || slot.playerIdentity != playerIdentity) {
            return false;
        }
        slot.active = false;
        slot.playerIdentity = null;
        slot.lastObservedTimeMs = trustedCurrentTimeMs;
        slot.expiresAtMs = saturatingAdd(trustedCurrentTimeMs, disconnectedTtlMs);
        lastTrustedTimeMs = trustedCurrentTimeMs;
        return true;
    }

    public synchronized Optional<Identity> current(short onlineId, Object playerIdentity) {
        if (!validId(onlineId) || playerIdentity == null) {
            return Optional.empty();
        }
        Slot slot = slots.get(onlineId);
        if (slot == null || !slot.active || slot.playerIdentity != playerIdentity) {
            return Optional.empty();
        }
        return Optional.of(new Identity(onlineId, slot.generation));
    }

    /** Resolves only an active exact-reference identity; equality is never consulted. */
    public synchronized Optional<Identity> currentByObject(Object playerIdentity) {
        if (playerIdentity == null) {
            return Optional.empty();
        }
        for (Map.Entry<Short, Slot> entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            if (slot.active && slot.playerIdentity == playerIdentity) {
                return Optional.of(new Identity(entry.getKey(), slot.generation));
            }
        }
        return Optional.empty();
    }

    public synchronized Snapshot snapshot() {
        int active = 0;
        for (Slot slot : slots.values()) {
            if (slot.active) active++;
        }
        return new Snapshot(
                slots.size(),
                active,
                slots.size() - active,
                totalIssuedGenerations,
                exhaustedOnlineIds);
    }

    private int issueGeneration(short onlineId) {
        int index = onlineId;
        if (issuedGenerations[index] == Integer.MAX_VALUE) {
            if (!exhaustedGenerations[index]) {
                exhaustedGenerations[index] = true;
                exhaustedOnlineIds++;
            }
            return -1;
        }
        issuedGenerations[index]++;
        totalIssuedGenerations = saturatingIncrement(totalIssuedGenerations);
        return issuedGenerations[index];
    }

    private void prune(long trustedCurrentTimeMs) {
        Iterator<Slot> iterator = slots.values().iterator();
        while (iterator.hasNext()) {
            Slot slot = iterator.next();
            if (!slot.active && trustedCurrentTimeMs > slot.expiresAtMs) {
                iterator.remove();
            }
        }
    }

    private boolean validTime(long value) {
        return value >= 0 && value >= lastTrustedTimeMs;
    }

    private static boolean validId(short onlineId) {
        return onlineId >= 0;
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    public record Identity(short onlineId, int connectionGeneration) {
        public Identity {
            if (onlineId < 0 || connectionGeneration <= 0) {
                throw new IllegalArgumentException("connection identity is invalid");
            }
        }
    }

    public record Snapshot(
            int retainedEntries,
            int activeEntries,
            int disconnectedEntries,
            long totalIssuedGenerations,
            int exhaustedOnlineIds) {}

    private static final class Slot {
        private Object playerIdentity;
        private final int generation;
        private long lastObservedTimeMs;
        private long expiresAtMs = Long.MAX_VALUE;
        private boolean active = true;

        private Slot(Object playerIdentity, int generation, long lastObservedTimeMs) {
            this.playerIdentity = playerIdentity;
            this.generation = generation;
            this.lastObservedTimeMs = lastObservedTimeMs;
        }
    }
}
