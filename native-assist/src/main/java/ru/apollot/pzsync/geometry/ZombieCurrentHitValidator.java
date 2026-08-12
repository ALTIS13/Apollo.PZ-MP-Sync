package ru.apollot.pzsync.geometry;

import ru.apollot.pzsync.history.EntityKind;

/** Current-only validator whose public surface is restricted to zombie-to-player hits. */
public final class ZombieCurrentHitValidator {
    private final RewindValidator geometry = new RewindValidator();

    public RewindDecision validate(
            CurrentHitRequest request, CollisionProbe collisionProbe) {
        if (request == null
                || request.attackerKind() != EntityKind.ZOMBIE
                || request.targetKind() != EntityKind.PLAYER) {
            return new RewindDecision(DecisionCode.REJECT_IDENTITY);
        }
        return geometry.validateCurrent(request, collisionProbe);
    }
}
