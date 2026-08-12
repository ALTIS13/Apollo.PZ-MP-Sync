package ru.apollot.pzsync.zombie;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ZombieHandoffPolicy {
    public static final long TARGET_STABILITY_MS = 250;
    public static final long HANDOFF_COOLDOWN_MS = 500;
    public static final long NO_OWNER = -1;
    public static final int DEFAULT_MAX_ZOMBIES = 1_024;
    public static final int MAX_ZOMBIES = 4_096;

    private final ZombieAuthorityEpochs epochs;
    private final int capacity;
    private final Map<Long, HandoffState> states;
    private long lastTrustedTimeMs = -1;
    private long lastGlobalHandoffMs = -1;

    public ZombieHandoffPolicy(ZombieAuthorityEpochs epochs) {
        this(epochs, DEFAULT_MAX_ZOMBIES);
    }

    public ZombieHandoffPolicy(ZombieAuthorityEpochs epochs, int capacity) {
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        if (capacity <= 0 || capacity > MAX_ZOMBIES) {
            throw new IllegalArgumentException("handoff capacity is outside the fixed bound");
        }
        this.capacity = capacity;
        states = new LinkedHashMap<>(capacity);
    }

    public synchronized Decision observe(Observation observation) {
        if (!valid(observation) || observation.trustedCurrentTimeMs() < lastTrustedTimeMs) {
            return Decision.rejected();
        }
        HandoffState state = states.get(observation.zombieId());
        if (state == null && states.size() == capacity) {
            return Decision.rejected();
        }
        if (observation.targetOwnerId() == NO_OWNER
                || observation.targetOwnerId() == observation.currentNativeOwnerId()) {
            state = track(observation.zombieId(), state);
            if (state == null) {
                return Decision.rejected();
            }
            lastTrustedTimeMs = observation.trustedCurrentTimeMs();
            state.pendingTargetOwnerId = NO_OWNER;
            state.pendingSinceMs = -1;
            return Decision.withoutHandoff(
                    HandoffCode.KEEP_NATIVE_OWNER,
                    epochs.epoch(observation.zombieId()).orElse(NO_OWNER));
        }

        long pendingTargetOwnerId =
                state == null ? NO_OWNER : state.pendingTargetOwnerId;
        long pendingSinceMs = state == null ? -1 : state.pendingSinceMs;
        if (pendingTargetOwnerId != observation.targetOwnerId()) {
            pendingTargetOwnerId = observation.targetOwnerId();
            pendingSinceMs = observation.trustedCurrentTimeMs();
        }

        if (!observation.grapple()
                && observation.trustedCurrentTimeMs() - pendingSinceMs < TARGET_STABILITY_MS) {
            state = track(observation.zombieId(), state);
            if (state == null) {
                return Decision.rejected();
            }
            lastTrustedTimeMs = observation.trustedCurrentTimeMs();
            state.pendingTargetOwnerId = pendingTargetOwnerId;
            state.pendingSinceMs = pendingSinceMs;
            return Decision.withoutHandoff(
                    HandoffCode.WAITING_FOR_STABILITY,
                    epochs.epoch(observation.zombieId()).orElse(NO_OWNER));
        }
        if (lastGlobalHandoffMs >= 0
                && observation.trustedCurrentTimeMs() - lastGlobalHandoffMs
                        < HANDOFF_COOLDOWN_MS) {
            state = track(observation.zombieId(), state);
            if (state == null) {
                return Decision.rejected();
            }
            lastTrustedTimeMs = observation.trustedCurrentTimeMs();
            state.pendingTargetOwnerId = pendingTargetOwnerId;
            state.pendingSinceMs = pendingSinceMs;
            return Decision.withoutHandoff(
                    HandoffCode.COOLDOWN,
                    epochs.epoch(observation.zombieId()).orElse(NO_OWNER));
        }

        var change =
                epochs.handoffOrRegister(
                        observation.zombieId(), observation.trustedCurrentTimeMs());
        if (change.isEmpty()) {
            return Decision.rejected();
        }
        if (state == null) {
            state = new HandoffState();
            states.put(observation.zombieId(), state);
        }
        ZombieAuthorityEpochs.EpochChange epochChange = change.orElseThrow();
        lastTrustedTimeMs = observation.trustedCurrentTimeMs();
        lastGlobalHandoffMs = observation.trustedCurrentTimeMs();
        state.pendingTargetOwnerId = NO_OWNER;
        state.pendingSinceMs = -1;
        return new Decision(
                HandoffCode.HANDOFF,
                observation.targetOwnerId(),
                epochChange.ownerEpoch(),
                epochChange.invalidateHistory(),
                epochChange.earliestHistoryEligibleAtMs());
    }

    public synchronized int size() {
        return states.size();
    }

    public int capacity() {
        return capacity;
    }

    private HandoffState track(long zombieId, HandoffState state) {
        if (state != null) {
            return state;
        }
        if (epochs.register(zombieId).isEmpty()) {
            return null;
        }
        HandoffState created = new HandoffState();
        states.put(zombieId, created);
        return created;
    }

    private static boolean valid(Observation observation) {
        return observation != null
                && observation.zombieId() >= 0
                && observation.zombieId() < Long.MAX_VALUE
                && observation.currentNativeOwnerId() >= 0
                && observation.currentNativeOwnerId() < Long.MAX_VALUE
                && observation.targetOwnerId() >= NO_OWNER
                && observation.targetOwnerId() < Long.MAX_VALUE
                && observation.trustedCurrentTimeMs() >= 0;
    }

    public enum HandoffCode {
        KEEP_NATIVE_OWNER,
        WAITING_FOR_STABILITY,
        COOLDOWN,
        HANDOFF,
        REJECTED
    }

    public record Observation(
            long zombieId,
            long currentNativeOwnerId,
            long targetOwnerId,
            long trustedCurrentTimeMs,
            boolean grapple) {}

    public record Decision(
            HandoffCode code,
            long proposedOwnerId,
            long ownerEpoch,
            boolean invalidateHistory,
            long earliestHistoryEligibleAtMs) {
        public Decision {
            Objects.requireNonNull(code, "code");
        }

        private static Decision withoutHandoff(HandoffCode code, long ownerEpoch) {
            return new Decision(code, NO_OWNER, ownerEpoch, false, -1);
        }

        private static Decision rejected() {
            return withoutHandoff(HandoffCode.REJECTED, NO_OWNER);
        }
    }

    private static final class HandoffState {
        private long pendingTargetOwnerId = NO_OWNER;
        private long pendingSinceMs = -1;
    }
}
