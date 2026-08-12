package ru.apollot.pzsync.runtime;

import java.util.Objects;
import java.util.OptionalLong;

/** Server-owned elapsed time derived only from a monotonic nanosecond source. */
public final class ServerMonotonicClock {
    private static final long NANOS_PER_MILLISECOND = 1_000_000;

    private final NanoTimeSource source;
    private final long originNanos;
    private long lastAcceptedNanos;
    private long lastTimeMs = -1;
    private long acceptedReads;
    private long rejectedReads;

    public ServerMonotonicClock() {
        this(System::nanoTime);
    }

    public ServerMonotonicClock(NanoTimeSource source) {
        this.source = Objects.requireNonNull(source, "source");
        originNanos = source.nanoTime();
        lastAcceptedNanos = originNanos;
    }

    public synchronized OptionalLong nowMs() {
        long observed = source.nanoTime();
        if (observed < lastAcceptedNanos) {
            rejectedReads = saturatingIncrement(rejectedReads);
            return OptionalLong.empty();
        }
        final long elapsed;
        try {
            elapsed = Math.subtractExact(observed, originNanos);
        } catch (ArithmeticException overflow) {
            rejectedReads = saturatingIncrement(rejectedReads);
            return OptionalLong.empty();
        }
        if (elapsed < 0) {
            rejectedReads = saturatingIncrement(rejectedReads);
            return OptionalLong.empty();
        }
        long converted = elapsed / NANOS_PER_MILLISECOND;
        if (converted < lastTimeMs) {
            rejectedReads = saturatingIncrement(rejectedReads);
            return OptionalLong.empty();
        }
        lastAcceptedNanos = observed;
        lastTimeMs = converted;
        acceptedReads = saturatingIncrement(acceptedReads);
        return OptionalLong.of(converted);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(acceptedReads, rejectedReads, lastTimeMs);
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    @FunctionalInterface
    public interface NanoTimeSource {
        long nanoTime();
    }

    public record Snapshot(long acceptedReads, long rejectedReads, long lastTimeMs) {}
}
