package ru.apollot.pzsync.history;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class HistoryStore {
    public static final int DEFAULT_MAX_ENTITIES = 1_024;
    public static final int MAX_ENTITY_CAPACITY = 1_024;

    private final int maxEntities;
    private final LinkedHashMap<EntityKey, HistoryTrack> tracks;
    private long lastTrustedLookupTimeMs = -1;

    public HistoryStore() {
        this(DEFAULT_MAX_ENTITIES);
    }

    public HistoryStore(int maxEntities) {
        if (maxEntities <= 0 || maxEntities > MAX_ENTITY_CAPACITY) {
            throw new IllegalArgumentException("maxEntities is outside the fixed capacity bound");
        }
        this.maxEntities = maxEntities;
        tracks = new LinkedHashMap<>(maxEntities);
    }

    public synchronized boolean add(EntityKey key, StateSample sample) {
        if (key == null
                || sample == null
                || sample.serverTimeMs() < lastTrustedLookupTimeMs) {
            return false;
        }

        HistoryTrack existing = tracks.get(key);
        if (existing != null) {
            return existing.add(sample);
        }

        EntityKey staleGeneration = findOtherGeneration(key);
        if (staleGeneration == null && tracks.size() >= maxEntities) {
            EntityKey expired = findExpiredTrack(sample.serverTimeMs());
            if (expired == null) {
                return false;
            }
            tracks.remove(expired);
        }
        if (staleGeneration != null) {
            if (key.generation() < staleGeneration.generation()) {
                return false;
            }
            List<StateSample> staleSamples = tracks.get(staleGeneration).snapshot();
            if (sample.serverTimeMs() <= staleSamples.getLast().serverTimeMs()) {
                return false;
            }
        }

        HistoryTrack replacement = new HistoryTrack();
        if (!replacement.add(sample)) {
            return false;
        }
        if (staleGeneration != null) {
            tracks.remove(staleGeneration);
        }
        tracks.put(key, replacement);
        return true;
    }

    public synchronized Optional<StateSample> sampleForRewind(
            EntityKey key,
            long trustedCurrentTimeMs,
            RewindWindow window,
            long ownerEpoch) {
        if (key == null
                || window == null
                || trustedCurrentTimeMs < 0
                || trustedCurrentTimeMs < lastTrustedLookupTimeMs) {
            return Optional.empty();
        }
        lastTrustedLookupTimeMs = trustedCurrentTimeMs;
        if (!window.historyAllowed() || window.requestedMs() > trustedCurrentTimeMs) {
            return Optional.empty();
        }
        HistoryTrack track = tracks.get(key);
        if (track == null || trustedCurrentTimeMs < track.latestServerTimeMs()) {
            return Optional.empty();
        }
        return track.sampleAtOrBeforeEpoch(
                trustedCurrentTimeMs - window.requestedMs(), ownerEpoch);
    }

    public synchronized boolean recordOwnerHandoff(
            EntityKey key, long fromEpoch, long toEpoch, long effectiveAtMs) {
        if (key == null
                || key.kind() != EntityKind.ZOMBIE
                || fromEpoch < 0
                || fromEpoch == Long.MAX_VALUE
                || toEpoch != fromEpoch + 1
                || effectiveAtMs < 0
                || effectiveAtMs < lastTrustedLookupTimeMs) {
            return false;
        }
        HistoryTrack track = tracks.get(key);
        if (track == null) {
            lastTrustedLookupTimeMs = effectiveAtMs;
            return true;
        }
        if (!track.recordOwnerHandoff(fromEpoch, toEpoch, effectiveAtMs)) {
            return false;
        }
        lastTrustedLookupTimeMs = effectiveAtMs;
        return true;
    }

    public synchronized boolean invalidate(EntityKey key) {
        return key != null && tracks.remove(key) != null;
    }

    public synchronized int size() {
        return tracks.size();
    }

    public int capacity() {
        return maxEntities;
    }

    synchronized Map<EntityKey, List<StateSample>> snapshot() {
        LinkedHashMap<EntityKey, List<StateSample>> copy = new LinkedHashMap<>(tracks.size());
        tracks.forEach((key, track) -> copy.put(key, track.snapshot()));
        return Collections.unmodifiableMap(copy);
    }

    private EntityKey findOtherGeneration(EntityKey key) {
        Iterator<EntityKey> iterator = tracks.keySet().iterator();
        while (iterator.hasNext()) {
            EntityKey candidate = iterator.next();
            if (candidate.kind() == key.kind()
                    && candidate.id() == key.id()
                    && candidate.generation() != key.generation()) {
                return candidate;
            }
        }
        return null;
    }

    private EntityKey findExpiredTrack(long trustedCurrentTimeMs) {
        if (trustedCurrentTimeMs < HistoryTrack.RETENTION_MS) {
            return null;
        }
        long cutoffMs = trustedCurrentTimeMs - HistoryTrack.RETENTION_MS;
        EntityKey oldestKey = null;
        long oldestTimeMs = Long.MAX_VALUE;
        for (Map.Entry<EntityKey, HistoryTrack> entry : tracks.entrySet()) {
            long latestTimeMs = entry.getValue().latestServerTimeMs();
            if (latestTimeMs < cutoffMs && latestTimeMs < oldestTimeMs) {
                oldestKey = entry.getKey();
                oldestTimeMs = latestTimeMs;
            }
        }
        return oldestKey;
    }
}
