package ru.apollot.pzsync.attack;

import java.util.Objects;
import ru.apollot.pzsync.history.EntityKind;

public record TargetGeneration(EntityKind kind, long targetId, long generation) {
    public TargetGeneration {
        Objects.requireNonNull(kind, "kind");
        if (targetId < 0 || generation < 0) {
            throw new IllegalArgumentException("target identity values must be non-negative");
        }
    }
}
