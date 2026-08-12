package ru.apollot.pzsync.history;

public final class RewindClock {
    public static final long NORMAL_CAP_MS = 150;
    public static final long HARD_CAP_MS = 200;
    public static final double MAX_RTT_MS = 300.0;
    public static final double MAX_JITTER_MS = 50.0;
    public static final double FLOOR_ALLOWANCE_MS = 50.0;
    public static final double PROCESSING_ALLOWANCE_MS = 25.0;

    private final long rewindCapMs;

    public RewindClock() {
        this(NORMAL_CAP_MS);
    }

    public RewindClock(long configuredMaximumMs) {
        if (configuredMaximumMs < 0 || configuredMaximumMs > HARD_CAP_MS) {
            throw new IllegalArgumentException("configured maximum is outside the hard cap");
        }
        rewindCapMs = Math.min(NORMAL_CAP_MS, configuredMaximumMs);
    }

    public RewindWindow compute(NetworkEstimate estimate) {
        if (estimate == null) {
            return strictCurrent("strict-current-network-missing");
        }
        if (estimate.rttEmaMs() > MAX_RTT_MS) {
            return strictCurrent("strict-current-rtt");
        }
        if (estimate.jitterMs() > MAX_JITTER_MS) {
            return strictCurrent("strict-current-jitter");
        }

        double effectiveRttMs =
                Math.min(estimate.rttEmaMs(), estimate.floorRttMs() + FLOOR_ALLOWANCE_MS);
        double requestedMs = effectiveRttMs / 2.0 + PROCESSING_ALLOWANCE_MS;
        long boundedMs = (long) Math.floor(Math.min(rewindCapMs, requestedMs));
        return new RewindWindow(boundedMs, true, "history");
    }

    private static RewindWindow strictCurrent(String reasonCode) {
        return new RewindWindow(0, false, reasonCode);
    }
}
