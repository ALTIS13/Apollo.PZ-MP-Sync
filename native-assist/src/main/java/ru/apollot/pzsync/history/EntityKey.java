package ru.apollot.pzsync.history;

import java.util.Objects;

public record EntityKey(EntityKind kind, long id, long generation) {
    public EntityKey {
        Objects.requireNonNull(kind, "kind");
        if (id < 0) {
            throw new IllegalArgumentException("id must not be negative");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
    }
}
