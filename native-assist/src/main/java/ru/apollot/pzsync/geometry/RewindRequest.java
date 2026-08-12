package ru.apollot.pzsync.geometry;

import java.util.Objects;
import java.util.Optional;
import ru.apollot.pzsync.attack.AttackToken;
import ru.apollot.pzsync.attack.TargetGeneration;
import ru.apollot.pzsync.history.RewindWindow;

public record RewindRequest(
        long trustedCurrentTimeMs,
        AttackToken attackToken,
        TargetGeneration consumedTarget,
        WeaponProfile weapon,
        HistoricalPose currentAttacker,
        HistoricalPose currentTarget,
        long targetOwnerEpochStartedAtMs,
        boolean targetCurrentEpochHistoryEligible,
        Optional<HistoricalPose> historicalAttacker,
        Optional<HistoricalPose> historicalTarget,
        RewindWindow rewindWindow,
        boolean nativeAccepted) {
    public RewindRequest {
        if (trustedCurrentTimeMs < 0) {
            throw new IllegalArgumentException("trustedCurrentTimeMs must be non-negative");
        }
        if (targetOwnerEpochStartedAtMs < -1
                || targetOwnerEpochStartedAtMs > trustedCurrentTimeMs) {
            throw new IllegalArgumentException("target owner epoch boundary is invalid");
        }
        Objects.requireNonNull(attackToken, "attackToken");
        Objects.requireNonNull(consumedTarget, "consumedTarget");
        Objects.requireNonNull(weapon, "weapon");
        Objects.requireNonNull(currentAttacker, "currentAttacker");
        Objects.requireNonNull(currentTarget, "currentTarget");
        Objects.requireNonNull(historicalAttacker, "historicalAttacker");
        Objects.requireNonNull(historicalTarget, "historicalTarget");
        Objects.requireNonNull(rewindWindow, "rewindWindow");
    }

    public RewindRequest(
            long trustedCurrentTimeMs,
            AttackToken attackToken,
            TargetGeneration consumedTarget,
            WeaponProfile weapon,
            HistoricalPose currentAttacker,
            HistoricalPose currentTarget,
            Optional<HistoricalPose> historicalAttacker,
            Optional<HistoricalPose> historicalTarget,
            RewindWindow rewindWindow,
            boolean nativeAccepted) {
        this(
                trustedCurrentTimeMs,
                attackToken,
                consumedTarget,
                weapon,
                currentAttacker,
                currentTarget,
                -1,
                true,
                historicalAttacker,
                historicalTarget,
                rewindWindow,
                nativeAccepted);
    }
}
