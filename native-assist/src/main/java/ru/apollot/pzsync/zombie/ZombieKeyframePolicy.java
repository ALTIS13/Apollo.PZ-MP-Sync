package ru.apollot.pzsync.zombie;

import java.util.Objects;
import java.util.Optional;
import ru.apollot.pzsync.attack.TargetGeneration;
import ru.apollot.pzsync.geometry.DecisionCode;
import ru.apollot.pzsync.geometry.HistoricalPose;
import ru.apollot.pzsync.geometry.RewindDecision;
import ru.apollot.pzsync.history.EntityKind;

public final class ZombieKeyframePolicy {
    private final ZombieAuthorityEpochs epochs;

    public ZombieKeyframePolicy(ZombieAuthorityEpochs epochs) {
        this.epochs = Objects.requireNonNull(epochs, "epochs");
    }

    public PveDecision playerToZombie(PlayerToZombieRequest request) {
        if (!validPlayerToZombieIdentity(request)) {
            return PveDecision.rejected(PveEligibility.REJECT_IDENTITY);
        }
        long currentEpoch = epochs.epoch(request.currentZombie().identity().id()).orElse(-1);
        if (currentEpoch != request.currentZombie().ownerEpoch()) {
            return PveDecision.rejected(PveEligibility.REJECT_EPOCH);
        }
        if (!request.nativeAccepted()) {
            return PveDecision.rejected(PveEligibility.REJECT_NATIVE);
        }
        if (request.geometry() == null || !request.geometry().accepted()) {
            return PveDecision.rejected(PveEligibility.REJECT_GEOMETRY);
        }

        boolean rewind = request.geometry().code() == DecisionCode.ACCEPT_REWIND;
        if (rewind
                && (!epochs.historyEligible(
                                request.currentZombie().identity().id(),
                                currentEpoch,
                                request.currentZombie().serverTimeMs())
                        || request.historicalZombie() == null
                        || request.historicalZombie().isEmpty()
                        || !sameZombieHistory(
                                request.currentZombie(),
                                request.historicalZombie().orElseThrow(),
                                currentEpoch))) {
            return PveDecision.rejected(PveEligibility.REJECT_HISTORY);
        }
        return PveDecision.eligible(rewind, request.confirmedHit());
    }

    public PveDecision zombieToPlayer(ZombieToPlayerRequest request) {
        if (!validZombieToPlayerIdentity(request)) {
            return PveDecision.rejected(PveEligibility.REJECT_IDENTITY);
        }
        if (request.nativeOwnerId() < 0
                || request.nativeOwnerId() == Long.MAX_VALUE
                || request.observedOwnerId() != request.nativeOwnerId()) {
            return PveDecision.rejected(PveEligibility.REJECT_OWNER);
        }
        long currentEpoch = epochs.epoch(request.currentZombie().identity().id()).orElse(-1);
        if (currentEpoch != request.currentZombie().ownerEpoch()) {
            return PveDecision.rejected(PveEligibility.REJECT_EPOCH);
        }
        if (!request.nativeAccepted()) {
            return PveDecision.rejected(PveEligibility.REJECT_NATIVE);
        }
        if (!epochs.historyEligible(
                        request.currentZombie().identity().id(),
                        currentEpoch,
                        request.currentZombie().serverTimeMs())
                || request.historicalZombie() == null
                || request.historicalZombie().isEmpty()
                || !sameZombieHistory(
                        request.currentZombie(),
                        request.historicalZombie().orElseThrow(),
                        currentEpoch)) {
            return PveDecision.rejected(PveEligibility.REJECT_HISTORY);
        }
        if (request.currentGeometry() == null
                || request.currentGeometry().code() != DecisionCode.ACCEPT_CURRENT) {
            return PveDecision.rejected(PveEligibility.REJECT_GEOMETRY);
        }
        return PveDecision.eligible(false, request.confirmedHit());
    }

    public boolean keyframeEligible(ZombieKeyframeEvent event) {
        return event != null && event != ZombieKeyframeEvent.NONE;
    }

    private static boolean validPlayerToZombieIdentity(PlayerToZombieRequest request) {
        if (request == null
                || request.mode() == null
                || request.target() == null
                || request.currentZombie() == null
                || request.historicalZombie() == null) {
            return false;
        }
        return request.target().kind() == EntityKind.ZOMBIE
                && request.currentZombie().identity().kind() == EntityKind.ZOMBIE
                && request.target().targetId() == request.currentZombie().identity().id()
                && request.target().generation() == request.currentZombie().identity().generation()
                && request.currentZombie().aliveAndEligible();
    }

    private static boolean validZombieToPlayerIdentity(ZombieToPlayerRequest request) {
        if (request == null
                || request.playerTarget() == null
                || request.currentZombie() == null
                || request.currentPlayer() == null
                || request.historicalZombie() == null) {
            return false;
        }
        return request.playerTarget().kind() == EntityKind.PLAYER
                && request.currentPlayer().identity().kind() == EntityKind.PLAYER
                && request.currentZombie().identity().kind() == EntityKind.ZOMBIE
                && request.playerTarget().targetId() == request.currentPlayer().identity().id()
                && request.playerTarget().generation()
                        == request.currentPlayer().identity().generation()
                && request.currentZombie().aliveAndEligible()
                && request.currentPlayer().aliveAndEligible();
    }

    private static boolean sameZombieHistory(
            HistoricalPose current, HistoricalPose historical, long authorityEpoch) {
        return historical.identity().equals(current.identity())
                && historical.ownerEpoch() == authorityEpoch
                && historical.serverTimeMs() <= current.serverTimeMs()
                && historical.aliveAndEligible();
    }

    public enum PlayerAttackMode {
        MELEE,
        RANGED
    }

    public enum ZombieKeyframeEvent {
        OWNERSHIP_CHANGE,
        KNOCKDOWN,
        GET_UP,
        DEATH,
        TARGET_CHANGE,
        CONFIRMED_HIT,
        NONE
    }

    public enum PveEligibility {
        ELIGIBLE,
        REJECT_IDENTITY,
        REJECT_OWNER,
        REJECT_EPOCH,
        REJECT_HISTORY,
        REJECT_GEOMETRY,
        REJECT_NATIVE
    }

    public record PlayerToZombieRequest(
            PlayerAttackMode mode,
            TargetGeneration target,
            HistoricalPose currentZombie,
            Optional<HistoricalPose> historicalZombie,
            RewindDecision geometry,
            boolean nativeAccepted,
            boolean confirmedHit) {}

    public record ZombieToPlayerRequest(
            TargetGeneration playerTarget,
            HistoricalPose currentZombie,
            HistoricalPose currentPlayer,
            Optional<HistoricalPose> historicalZombie,
            long nativeOwnerId,
            long observedOwnerId,
            RewindDecision currentGeometry,
            boolean nativeAccepted,
            boolean confirmedHit) {}

    public record PveDecision(
            PveEligibility code,
            boolean proceedNative,
            boolean rewindEligible,
            boolean keyframeEligible) {
        public PveDecision {
            Objects.requireNonNull(code, "code");
        }

        private static PveDecision eligible(boolean rewind, boolean keyframe) {
            return new PveDecision(PveEligibility.ELIGIBLE, true, rewind, keyframe);
        }

        private static PveDecision rejected(PveEligibility code) {
            return new PveDecision(code, false, false, false);
        }
    }
}
