package ru.apollot.pzsync.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class HistoryTrack {
    public static final long RETENTION_MS = 750;
    public static final long SAMPLE_INTERVAL_MS = 100;
    public static final long MAX_INTERPOLATION_GAP_MS = 125;
    public static final int CAPACITY =
            (int) ((RETENTION_MS + SAMPLE_INTERVAL_MS - 1) / SAMPLE_INTERVAL_MS) + 1;

    private final StateSample[] samples = new StateSample[CAPACITY];
    private final OwnerBoundary[] ownerBoundaries = new OwnerBoundary[CAPACITY];
    private int size;
    private int ownerBoundaryCount;

    public synchronized boolean add(StateSample sample) {
        if (sample == null) {
            return false;
        }
        if (size > 0) {
            StateSample latest = samples[size - 1];
            if (sample.serverTimeMs() <= latest.serverTimeMs()) {
                return false;
            }
            if (sample.ownerEpoch() < latest.ownerEpoch()) {
                return false;
            }
        }

        pruneBefore(sample.serverTimeMs() - RETENTION_MS);
        if (size == CAPACITY) {
            removeFirst();
        }
        samples[size++] = sample;
        return true;
    }

    synchronized Optional<StateSample> sampleAt(long serverTimeMs, long ownerEpoch) {
        return sampleAtExactEpoch(serverTimeMs, ownerEpoch);
    }

    synchronized Optional<StateSample> sampleAtOrBeforeEpoch(
            long serverTimeMs, long maximumOwnerEpoch) {
        if (serverTimeMs < 0 || maximumOwnerEpoch < 0 || size == 0) {
            return Optional.empty();
        }
        long authorizedEpoch = authorizedEpochAt(serverTimeMs, maximumOwnerEpoch);
        return authorizedEpoch < 0
                ? Optional.empty()
                : sampleAtExactEpoch(serverTimeMs, authorizedEpoch);
    }

    private Optional<StateSample> sampleAtExactEpoch(
            long serverTimeMs, long ownerEpoch) {
        if (serverTimeMs < 0 || ownerEpoch < 0 || size == 0) {
            return Optional.empty();
        }
        StateSample first = samples[0];
        StateSample last = samples[size - 1];
        if (serverTimeMs < first.serverTimeMs() || serverTimeMs > last.serverTimeMs()) {
            return Optional.empty();
        }

        for (int index = 0; index < size; index++) {
            StateSample current = samples[index];
            if (current.serverTimeMs() == serverTimeMs) {
                return current.ownerEpoch() == ownerEpoch
                        ? Optional.of(current)
                        : Optional.empty();
            }
            if (current.serverTimeMs() > serverTimeMs) {
                StateSample previous = samples[index - 1];
                long selectedEpoch = previous.ownerEpoch();
                if (selectedEpoch != ownerEpoch) {
                    return Optional.empty();
                }
                return interpolate(previous, current, serverTimeMs, selectedEpoch);
            }
        }
        return Optional.empty();
    }

    synchronized List<StateSample> snapshot() {
        ArrayList<StateSample> copy = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            copy.add(samples[index]);
        }
        return List.copyOf(copy);
    }

    synchronized long latestServerTimeMs() {
        return samples[size - 1].serverTimeMs();
    }

    public synchronized void clear() {
        clearInternal();
    }

    synchronized boolean recordOwnerHandoff(
            long fromEpoch, long toEpoch, long effectiveAtMs) {
        if (fromEpoch < 0
                || fromEpoch == Long.MAX_VALUE
                || toEpoch != fromEpoch + 1
                || effectiveAtMs < 0) {
            return false;
        }
        pruneOwnerBoundaries(effectiveAtMs);
        if (ownerBoundaryCount > 0) {
            OwnerBoundary latest = ownerBoundaries[ownerBoundaryCount - 1];
            if (latest.toEpoch() != fromEpoch || effectiveAtMs < latest.effectiveAtMs()) {
                return false;
            }
        } else if (size > 0 && samples[size - 1].ownerEpoch() != fromEpoch) {
            return false;
        }
        if (ownerBoundaryCount == ownerBoundaries.length) {
            return false;
        }
        ownerBoundaries[ownerBoundaryCount++] =
                new OwnerBoundary(fromEpoch, toEpoch, effectiveAtMs);
        return true;
    }

    private long authorizedEpochAt(long serverTimeMs, long maximumOwnerEpoch) {
        long authorizedEpoch = maximumOwnerEpoch;
        for (int index = ownerBoundaryCount - 1; index >= 0; index--) {
            OwnerBoundary boundary = ownerBoundaries[index];
            if (boundary.toEpoch() > authorizedEpoch) {
                return -1;
            }
            if (serverTimeMs >= boundary.effectiveAtMs()) {
                break;
            }
            authorizedEpoch = boundary.fromEpoch();
        }
        return authorizedEpoch;
    }

    private void pruneOwnerBoundaries(long trustedCurrentTimeMs) {
        if (trustedCurrentTimeMs < RETENTION_MS) {
            return;
        }
        long cutoffMs = trustedCurrentTimeMs - RETENTION_MS;
        int removeCount = 0;
        while (removeCount < ownerBoundaryCount
                && ownerBoundaries[removeCount].effectiveAtMs() < cutoffMs) {
            removeCount++;
        }
        if (removeCount == 0) {
            return;
        }
        System.arraycopy(
                ownerBoundaries,
                removeCount,
                ownerBoundaries,
                0,
                ownerBoundaryCount - removeCount);
        for (int index = ownerBoundaryCount - removeCount; index < ownerBoundaryCount; index++) {
            ownerBoundaries[index] = null;
        }
        ownerBoundaryCount -= removeCount;
    }

    private static Optional<StateSample> interpolate(
            StateSample lower, StateSample upper, long requestedTimeMs, long ownerEpoch) {
        long gap = upper.serverTimeMs() - lower.serverTimeMs();
        if (gap <= 0
                || gap > MAX_INTERPOLATION_GAP_MS
                || lower.floor() != upper.floor()
                || lower.ownerEpoch() != upper.ownerEpoch()
                || lower.ownerEpoch() != ownerEpoch) {
            return Optional.empty();
        }

        double fraction = (double) (requestedTimeMs - lower.serverTimeMs()) / gap;
        return Optional.of(
                new StateSample(
                        requestedTimeMs,
                        lerp(lower.x(), upper.x(), fraction),
                        lerp(lower.y(), upper.y(), fraction),
                        lower.floor(),
                        lerp(lower.yaw(), upper.yaw(), fraction),
                        ownerEpoch));
    }

    private static double lerp(double lower, double upper, double fraction) {
        return lower * (1.0 - fraction) + upper * fraction;
    }

    private void pruneBefore(long cutoffMs) {
        while (size > 0 && samples[0].serverTimeMs() < cutoffMs) {
            removeFirst();
        }
    }

    private void removeFirst() {
        if (size > 1) {
            System.arraycopy(samples, 1, samples, 0, size - 1);
        }
        samples[--size] = null;
    }

    private void clearInternal() {
        for (int index = 0; index < size; index++) {
            samples[index] = null;
        }
        size = 0;
        for (int index = 0; index < ownerBoundaryCount; index++) {
            ownerBoundaries[index] = null;
        }
        ownerBoundaryCount = 0;
    }

    private record OwnerBoundary(long fromEpoch, long toEpoch, long effectiveAtMs) {}
}
