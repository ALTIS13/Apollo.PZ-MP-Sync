package ru.apollot.pzsync.geometry;

import ru.apollot.pzsync.attack.AttackKey;
import ru.apollot.pzsync.attack.AttackLedger;
import ru.apollot.pzsync.attack.ConsumeResult;
import ru.apollot.pzsync.attack.TargetGeneration;
import ru.apollot.pzsync.history.EntityKey;
import ru.apollot.pzsync.history.EntityKind;

public final class RewindValidator {
    public static final double RANGE_EPSILON_TILES = 0.25;
    public static final double DEFAULT_MELEE_CAP_TILES = 3.0;
    public static final double DIVERGENCE_REJECT_TILES = 2.5;

    public RewindDecision validateAndConsume(
            AttackLedger attackLedger,
            RewindRequest request,
            CollisionProbe collisionProbe) {
        if (attackLedger == null || request == null) {
            return decision(DecisionCode.REJECT_IDENTITY);
        }
        ConsumeResult consumeResult =
                attackLedger.consume(
                        request.attackToken().key(),
                        request.consumedTarget(),
                        request.trustedCurrentTimeMs(),
                        request.weapon().observation());
        return validateConsumed(request, consumeResult, collisionProbe);
    }

    RewindDecision validateCurrent(
            CurrentHitRequest request, CollisionProbe collisionProbe) {
        if (request == null || collisionProbe == null) {
            return decision(DecisionCode.REJECT_IDENTITY);
        }
        if (!request.nativeAccepted()) return decision(DecisionCode.REJECT_NATIVE);
        HistoricalPose attacker = request.currentAttacker();
        HistoricalPose target = request.currentTarget();
        if (attacker.identity().kind() != request.attackerKind()
                || target.identity().kind() != request.targetKind()) {
            return decision(DecisionCode.REJECT_IDENTITY);
        }
        if (attacker.serverTimeMs() != request.trustedCurrentTimeMs()
                || target.serverTimeMs() != request.trustedCurrentTimeMs()
                || !attacker.aliveAndEligible()
                || !target.aliveAndEligible()) {
            return decision(DecisionCode.REJECT_STALE);
        }
        if (!sameIntegerFloor(attacker, target)) return decision(DecisionCode.REJECT_FLOOR);
        if (!collisionProbe.squaresLoaded(attacker, target)) {
            return decision(DecisionCode.REJECT_UNLOADED);
        }
        if (!collisionProbe.currentLineOfSight(attacker, target)) {
            return decision(DecisionCode.REJECT_LOS);
        }
        DecisionCode geometry = request.weapon().observation().projectile()
                ? rangedGeometry(attacker, target, request.weapon())
                : meleeGeometry(attacker, target, request.weapon());
        return decision(geometry == null ? DecisionCode.ACCEPT_CURRENT : geometry);
    }

    private RewindDecision validateConsumed(
            RewindRequest request, ConsumeResult consumeResult, CollisionProbe collisionProbe) {
        if (consumeResult != ConsumeResult.ACCEPT) {
            return decision(DecisionCode.REJECT_IDENTITY);
        }
        if (!request.nativeAccepted()) {
            return decision(DecisionCode.REJECT_NATIVE);
        }
        if (collisionProbe == null) {
            return decision(DecisionCode.REJECT_IDENTITY);
        }
        if (!identitiesAndWeaponMatch(request)) {
            return decision(DecisionCode.REJECT_IDENTITY);
        }
        if (!currentStateIsFresh(request)) {
            return decision(DecisionCode.REJECT_STALE);
        }

        HistoricalPose attacker = request.currentAttacker();
        HistoricalPose target = request.currentTarget();
        var historicalAttacker = request.historicalAttacker();
        var historicalTarget = request.historicalTarget();
        if (historicalTarget.isPresent()
                && request.currentTarget().identity().kind() == EntityKind.ZOMBIE
                && historicalTarget.orElseThrow().ownerEpoch()
                        == request.currentTarget().ownerEpoch()
                && !request.targetCurrentEpochHistoryEligible()) {
            historicalTarget = java.util.Optional.empty();
        }
        boolean historicalAttackerPresent = historicalAttacker.isPresent();
        boolean historicalTargetPresent = historicalTarget.isPresent();
        if (request.rewindWindow().historyAllowed()
                && historicalAttackerPresent != historicalTargetPresent) {
            HistoricalPose partial = historicalAttackerPresent
                    ? historicalAttacker.orElseThrow()
                    : historicalTarget.orElseThrow();
            HistoricalPose current = historicalAttackerPresent
                    ? request.currentAttacker()
                    : request.currentTarget();
            if (!historicalEndpointIsFresh(request, partial, current)) {
                return decision(DecisionCode.REJECT_STALE);
            }
        }
        boolean rewind =
                request.rewindWindow().historyAllowed()
                        && historicalAttackerPresent
                        && historicalTargetPresent;
        boolean attackCrossedTargetHandoff =
                request.currentTarget().identity().kind() == EntityKind.ZOMBIE
                        && request.targetOwnerEpochStartedAtMs() >= 0
                        && request.attackToken().openedAtMs()
                                < request.targetOwnerEpochStartedAtMs();
        boolean priorTargetEpochRewind =
                rewind
                        && historicalTarget.orElseThrow().ownerEpoch()
                                < request.currentTarget().ownerEpoch();
        if (attackCrossedTargetHandoff != priorTargetEpochRewind) {
            return decision(DecisionCode.REJECT_STALE);
        }
        if (rewind) {
            attacker = historicalAttacker.orElseThrow();
            target = historicalTarget.orElseThrow();
            if (!attacker.identity().equals(request.currentAttacker().identity())
                    || !target.identity().equals(request.currentTarget().identity())) {
                return decision(DecisionCode.REJECT_IDENTITY);
            }
            if (!historicalStateIsFresh(request, attacker, target)) {
                return decision(DecisionCode.REJECT_STALE);
            }
            double divergenceLimitSquared =
                    DIVERGENCE_REJECT_TILES * DIVERGENCE_REJECT_TILES;
            if (attacker.position().horizontalDistanceSquared(
                                    request.currentAttacker().position())
                            >= divergenceLimitSquared
                    || target.position().horizontalDistanceSquared(
                                    request.currentTarget().position())
                            >= divergenceLimitSquared) {
                return decision(DecisionCode.REJECT_DIVERGENCE);
            }
        }

        if (!sameIntegerFloor(request.currentAttacker(), request.currentTarget())
                || !sameIntegerFloor(attacker, target)) {
            return decision(DecisionCode.REJECT_FLOOR);
        }
        if (!collisionProbe.squaresLoaded(request.currentAttacker(), request.currentTarget())) {
            return decision(DecisionCode.REJECT_UNLOADED);
        }
        if (!collisionProbe.currentLineOfSight(
                request.currentAttacker(), request.currentTarget())) {
            return decision(DecisionCode.REJECT_LOS);
        }

        DecisionCode geometry =
                request.weapon().observation().projectile()
                        ? rangedGeometry(attacker, target, request.weapon())
                        : meleeGeometry(attacker, target, request.weapon());
        if (geometry != null) {
            return decision(geometry);
        }
        return decision(rewind ? DecisionCode.ACCEPT_REWIND : DecisionCode.ACCEPT_CURRENT);
    }

    private static boolean identitiesAndWeaponMatch(RewindRequest request) {
        AttackKey attack = request.attackToken().key();
        EntityKey attacker = request.currentAttacker().identity();
        EntityKey target = request.currentTarget().identity();
        TargetGeneration consumedTarget = request.consumedTarget();
        boolean projectileFactsMatch =
                !request.weapon().observation().projectile()
                        || (request.weapon().ammoAuthorized()
                                && request.weapon().nativeProjectileBudget()
                                        == request.attackToken().nativeMaxHits());
        return attacker.kind() == EntityKind.PLAYER
                && attacker.id() == Short.toUnsignedLong(attack.playerOnlineId())
                && attacker.generation() == attack.connectionGeneration()
                && target.kind() == consumedTarget.kind()
                && target.id() == consumedTarget.targetId()
                && target.generation() == consumedTarget.generation()
                && request.attackToken().weapon().equals(request.weapon().observation())
                && request.weapon().equipped()
                && projectileFactsMatch
                && request.attackToken().openedAtMs() <= request.trustedCurrentTimeMs();
    }

    private static boolean currentStateIsFresh(RewindRequest request) {
        return request.currentAttacker().serverTimeMs() == request.trustedCurrentTimeMs()
                && request.currentTarget().serverTimeMs() == request.trustedCurrentTimeMs()
                && request.currentAttacker().aliveAndEligible()
                && request.currentTarget().aliveAndEligible();
    }

    private static boolean historicalStateIsFresh(
            RewindRequest request, HistoricalPose attacker, HistoricalPose target) {
        return historicalEndpointIsFresh(request, attacker, request.currentAttacker())
                && historicalEndpointIsFresh(request, target, request.currentTarget());
    }

    private static boolean historicalEndpointIsFresh(
            RewindRequest request, HistoricalPose historical, HistoricalPose current) {
        if (request.rewindWindow().requestedMs() > request.trustedCurrentTimeMs()) {
            return false;
        }
        long expectedTime =
                request.trustedCurrentTimeMs() - request.rewindWindow().requestedMs();
        return historical.identity().equals(current.identity())
                && historical.serverTimeMs() == expectedTime
                && ownerEpochIsAuthorized(historical, current)
                && historical.aliveAndEligible();
    }

    private static boolean ownerEpochIsAuthorized(
            HistoricalPose historical, HistoricalPose current) {
        if (current.identity().kind() == EntityKind.ZOMBIE) {
            return historical.ownerEpoch() <= current.ownerEpoch();
        }
        return historical.ownerEpoch() == current.ownerEpoch();
    }

    private static boolean sameIntegerFloor(HistoricalPose attacker, HistoricalPose target) {
        return attacker.position().integerFloor() == target.position().integerFloor();
    }

    private static DecisionCode meleeGeometry(
            HistoricalPose attacker, HistoricalPose target, WeaponProfile weapon) {
        double allowedRange =
                Math.min(weapon.serverRangeTiles(), DEFAULT_MELEE_CAP_TILES)
                        + RANGE_EPSILON_TILES;
        double dx = target.position().x() - attacker.position().x();
        double dy = target.position().y() - attacker.position().y();
        double distance = Math.hypot(dx, dy);
        if (distance < Double.MIN_NORMAL) {
            return DecisionCode.REJECT_CONE;
        }
        if (distance > allowedRange) {
            return DecisionCode.REJECT_RANGE;
        }

        double inverseDistance = 1.0 / distance;
        double facingX = Math.cos(attacker.yawRadians());
        double facingY = Math.sin(attacker.yawRadians());
        double dot = (dx * facingX + dy * facingY) * inverseDistance;
        double minimumDot = Math.cos(Math.toRadians(weapon.meleeHalfAngleDegrees()));
        return dot + 1.0e-12 < minimumDot ? DecisionCode.REJECT_CONE : null;
    }

    private static DecisionCode rangedGeometry(
            HistoricalPose attacker, HistoricalPose target, WeaponProfile weapon) {
        double dx = target.position().x() - attacker.position().x();
        double dy = target.position().y() - attacker.position().y();
        double distance = Math.hypot(dx, dy);
        if (distance < Double.MIN_NORMAL) {
            return DecisionCode.REJECT_CONE;
        }
        if (!Double.isFinite(distance)
                || (distance > weapon.serverRangeTiles()
                        && distance - weapon.serverRangeTiles() > RANGE_EPSILON_TILES)) {
            return DecisionCode.REJECT_RANGE;
        }

        double facingX = Math.cos(attacker.yawRadians());
        double facingY = Math.sin(attacker.yawRadians());
        double forward = dx * facingX + dy * facingY;
        double crossTrack = Math.abs(dx * facingY - dy * facingX);
        if (forward < 0.0 || crossTrack > RANGE_EPSILON_TILES) {
            return DecisionCode.REJECT_CONE;
        }
        return null;
    }

    private static RewindDecision decision(DecisionCode code) {
        return new RewindDecision(code);
    }
}
