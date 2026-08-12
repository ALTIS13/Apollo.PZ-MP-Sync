package ru.apollot.pzsync.geometry;

public interface CollisionProbe {
    boolean squaresLoaded(HistoricalPose attacker, HistoricalPose target);

    boolean currentLineOfSight(HistoricalPose attacker, HistoricalPose target);
}
