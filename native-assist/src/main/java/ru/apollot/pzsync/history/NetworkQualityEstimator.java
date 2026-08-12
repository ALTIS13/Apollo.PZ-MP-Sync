package ru.apollot.pzsync.history;

import java.util.Optional;

public final class NetworkQualityEstimator {
    public static final long FLOOR_WINDOW_MS = 30_000;
    public static final long SAMPLE_INTERVAL_MS = 100;
    public static final int SAMPLE_CAPACITY =
            (int) (FLOOR_WINDOW_MS / SAMPLE_INTERVAL_MS) + 1;

    private static final double RTT_EMA_WEIGHT = 0.125;
    private static final double JITTER_WEIGHT = 0.25;

    private final long[] sampleTimes = new long[SAMPLE_CAPACITY];
    private final double[] sampleRtts = new double[SAMPLE_CAPACITY];
    private int sampleCount;
    private long lastSampleTime = -1;
    private long lastTrustedQueryTime = -1;
    private double rttEmaMs;
    private double jitterMs;

    public synchronized boolean addSample(long serverTimeMs, double rttMs) {
        if (serverTimeMs < 0
                || serverTimeMs <= lastSampleTime
                || (lastSampleTime >= 0
                        && serverTimeMs - lastSampleTime < SAMPLE_INTERVAL_MS)
                || serverTimeMs < lastTrustedQueryTime
                || !Double.isFinite(rttMs)
                || rttMs < 0.0) {
            return false;
        }

        if (sampleCount == 0) {
            rttEmaMs = rttMs;
            jitterMs = 0.0;
        } else {
            double error = Math.abs(rttMs - rttEmaMs);
            jitterMs += JITTER_WEIGHT * (error - jitterMs);
            rttEmaMs += RTT_EMA_WEIGHT * (rttMs - rttEmaMs);
        }

        pruneBefore(serverTimeMs - FLOOR_WINDOW_MS);
        if (sampleCount == SAMPLE_CAPACITY) {
            removeFirst();
        }
        sampleTimes[sampleCount] = serverTimeMs;
        sampleRtts[sampleCount] = rttMs;
        sampleCount++;
        lastSampleTime = serverTimeMs;
        return true;
    }

    public synchronized Optional<NetworkEstimate> estimateAt(long trustedCurrentTimeMs) {
        if (trustedCurrentTimeMs < 0
                || trustedCurrentTimeMs < lastTrustedQueryTime
                || trustedCurrentTimeMs < lastSampleTime) {
            return Optional.empty();
        }
        lastTrustedQueryTime = trustedCurrentTimeMs;
        pruneBefore(trustedCurrentTimeMs - FLOOR_WINDOW_MS);
        if (sampleCount == 0) {
            return Optional.empty();
        }
        double floor = sampleRtts[0];
        for (int index = 1; index < sampleCount; index++) {
            floor = Math.min(floor, sampleRtts[index]);
        }
        return Optional.of(new NetworkEstimate(rttEmaMs, floor, jitterMs));
    }

    public synchronized int sampleCount() {
        return sampleCount;
    }

    private void pruneBefore(long cutoffMs) {
        while (sampleCount > 0 && sampleTimes[0] < cutoffMs) {
            removeFirst();
        }
    }

    private void removeFirst() {
        if (sampleCount > 1) {
            System.arraycopy(sampleTimes, 1, sampleTimes, 0, sampleCount - 1);
            System.arraycopy(sampleRtts, 1, sampleRtts, 0, sampleCount - 1);
        }
        sampleCount--;
    }
}
