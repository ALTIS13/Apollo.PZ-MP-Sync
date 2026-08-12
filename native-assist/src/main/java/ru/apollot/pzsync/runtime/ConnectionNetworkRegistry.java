package ru.apollot.pzsync.runtime;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.apollot.pzsync.history.NetworkQualityEstimator;
import ru.apollot.pzsync.history.RewindClock;
import ru.apollot.pzsync.history.RewindWindow;

/** Bounded per-connection RTT telemetry with explicit freshness policy. */
public final class ConnectionNetworkRegistry {
    public static final int DEFAULT_CAPACITY = 512;
    public static final int MAX_CAPACITY = 1_024;
    public static final long DEFAULT_SAMPLE_FRESHNESS_MS = 1_000;
    public static final long DEFAULT_RETAINED_TTL_MS = 60_000;

    private final int capacity;
    private final long sampleFreshnessMs;
    private final long retainedTtlMs;
    private final Map<PlayerIdentityRegistry.Identity, Entry> entries;
    private long lastTrustedTimeMs = -1;
    private long acceptedSamples;
    private long invalidSamples;

    public ConnectionNetworkRegistry() {
        this(DEFAULT_CAPACITY, DEFAULT_SAMPLE_FRESHNESS_MS, DEFAULT_RETAINED_TTL_MS);
    }

    public ConnectionNetworkRegistry(int capacity, long sampleFreshnessMs, long retainedTtlMs) {
        if (capacity <= 0
                || capacity > MAX_CAPACITY
                || sampleFreshnessMs <= 0
                || retainedTtlMs <= 0) {
            throw new IllegalArgumentException("network registry bounds are invalid");
        }
        this.capacity = capacity;
        this.sampleFreshnessMs = sampleFreshnessMs;
        this.retainedTtlMs = retainedTtlMs;
        entries = new LinkedHashMap<>(capacity);
    }

    public synchronized boolean addSample(
            PlayerIdentityRegistry.Identity connection, long sampleTimeMs, double rttMs) {
        if (connection == null
                || sampleTimeMs < 0
                || sampleTimeMs < lastTrustedTimeMs
                || !Double.isFinite(rttMs)
                || rttMs <= 0.0) {
            invalidSamples = saturatingIncrement(invalidSamples);
            return false;
        }
        prune(sampleTimeMs);
        Entry entry = entries.get(connection);
        if (entry == null) {
            if (entries.size() >= capacity) {
                return false;
            }
            entry = new Entry();
            entries.put(connection, entry);
        }
        if (!entry.estimator.addSample(sampleTimeMs, rttMs)) {
            invalidSamples = saturatingIncrement(invalidSamples);
            if (entry.lastSampleTimeMs < 0) entries.remove(connection);
            return false;
        }
        entry.lastSampleTimeMs = sampleTimeMs;
        acceptedSamples = saturatingIncrement(acceptedSamples);
        lastTrustedTimeMs = sampleTimeMs;
        return true;
    }

    public synchronized RewindWindow rewindWindow(
            PlayerIdentityRegistry.Identity connection,
            long trustedCurrentTimeMs,
            long configuredMaximumMs) {
        RewindClock clock = new RewindClock(configuredMaximumMs);
        if (connection == null
                || trustedCurrentTimeMs < 0
                || trustedCurrentTimeMs < lastTrustedTimeMs) {
            return clock.compute(null);
        }
        prune(trustedCurrentTimeMs);
        lastTrustedTimeMs = trustedCurrentTimeMs;
        Entry entry = entries.get(connection);
        if (entry == null
                || entry.lastSampleTimeMs < 0
                || trustedCurrentTimeMs < entry.lastSampleTimeMs
                || trustedCurrentTimeMs - entry.lastSampleTimeMs > sampleFreshnessMs) {
            return clock.compute(null);
        }
        return clock.compute(entry.estimator.estimateAt(trustedCurrentTimeMs).orElse(null));
    }

    public synchronized boolean remove(PlayerIdentityRegistry.Identity connection) {
        return connection != null && entries.remove(connection) != null;
    }

    /** Drops only transient RTT samples; player generations live in a separate registry. */
    public synchronized void clearForServerDeauthorization() {
        entries.clear();
    }

    public synchronized Snapshot snapshot() {
        int samples = 0;
        for (Entry entry : entries.values()) {
            samples += entry.estimator.sampleCount();
        }
        return new Snapshot(entries.size(), samples, acceptedSamples, invalidSamples);
    }

    private void prune(long trustedCurrentTimeMs) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.lastSampleTimeMs >= 0
                    && trustedCurrentTimeMs > saturatingAdd(entry.lastSampleTimeMs, retainedTtlMs)) {
                iterator.remove();
            }
        }
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private static long saturatingAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    public record Snapshot(
            int retainedConnections,
            int retainedSamples,
            long acceptedSamples,
            long invalidSamples) {}

    private static final class Entry {
        private final NetworkQualityEstimator estimator = new NetworkQualityEstimator();
        private long lastSampleTimeMs = -1;
    }
}
