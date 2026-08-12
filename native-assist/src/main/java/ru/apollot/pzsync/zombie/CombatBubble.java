package ru.apollot.pzsync.zombie;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ru.apollot.pzsync.history.EntityKind;

public final class CombatBubble {
    public static final double INNER_RADIUS_TILES = 8.0;
    public static final double OUTER_RADIUS_TILES = 15.0;
    public static final int MAX_PLAYERS = 12;
    public static final int MAX_ZOMBIES = 48;
    public static final int MAX_ANCHORS = MAX_PLAYERS + MAX_ZOMBIES;
    public static final long EXPIRY_MS = 5_000;

    private final long id;
    private final Entity[] anchors = new Entity[MAX_ANCHORS];
    private final SelectedEntity[] selectedPlayers = new SelectedEntity[MAX_PLAYERS];
    private final SelectedEntity[] selectedZombies = new SelectedEntity[MAX_ZOMBIES];
    private int anchorCount;
    private int selectedPlayerCount;
    private int selectedZombieCount;
    private long lastActivityMs;
    private long lastObservedTimeMs;

    public CombatBubble(long id, long activityAtMs, List<Entity> initialAnchors) {
        if (id <= 0 || activityAtMs < 0) {
            throw new IllegalArgumentException("bubble identity and activity time must be valid");
        }
        Objects.requireNonNull(initialAnchors, "initialAnchors");
        if (initialAnchors.isEmpty() || initialAnchors.size() > MAX_ANCHORS) {
            throw new IllegalArgumentException("anchors are outside the fixed participant bound");
        }
        this.id = id;
        lastActivityMs = activityAtMs;
        lastObservedTimeMs = activityAtMs;
        if (!addAnchors(initialAnchors)) {
            throw new IllegalArgumentException("anchors must be concrete and distinct");
        }
    }

    public synchronized long id() {
        return id;
    }

    public synchronized long lastActivityMs() {
        return lastActivityMs;
    }

    public synchronized boolean activeAt(long trustedCurrentTimeMs) {
        return trustedCurrentTimeMs >= lastObservedTimeMs
                && trustedCurrentTimeMs >= lastActivityMs
                && trustedCurrentTimeMs - lastActivityMs < EXPIRY_MS;
    }

    public synchronized Selection select(
            Iterable<Entity> candidates, long trustedCurrentTimeMs) {
        if (candidates == null || !activeAt(trustedCurrentTimeMs)) {
            return Selection.EMPTY;
        }
        lastObservedTimeMs = trustedCurrentTimeMs;

        RankedEntity[] players = new RankedEntity[MAX_PLAYERS];
        RankedEntity[] zombies = new RankedEntity[MAX_ZOMBIES];
        int playerCount = 0;
        int zombieCount = 0;
        for (Entity candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            double distance = distanceToNearestAnchor(candidate);
            if (!Double.isFinite(distance)) {
                continue;
            }
            boolean retained = wasSelected(candidate.kind(), candidate.id());
            double radius = retained ? OUTER_RADIUS_TILES : INNER_RADIUS_TILES;
            if (distance > radius) {
                continue;
            }
            RankedEntity ranked = new RankedEntity(candidate, distance);
            if (candidate.kind() == EntityKind.PLAYER) {
                playerCount = insertBounded(players, playerCount, ranked);
            } else {
                zombieCount = insertBounded(zombies, zombieCount, ranked);
            }
        }

        selectedPlayerCount = copySelected(players, playerCount, selectedPlayers);
        selectedZombieCount = copySelected(zombies, zombieCount, selectedZombies);
        return selection();
    }

    synchronized boolean overlaps(Entity first, Entity second) {
        return withinMergeDistance(first) || withinMergeDistance(second);
    }

    synchronized boolean replaceAnchors(long activityAtMs, List<Entity> newAnchors) {
        if (activityAtMs < lastObservedTimeMs
                || activityAtMs < lastActivityMs
                || newAnchors == null
                || newAnchors.isEmpty()
                || !validAnchorSet(newAnchors)) {
            return false;
        }
        for (int index = 0; index < anchorCount; index++) {
            anchors[index] = null;
        }
        anchorCount = 0;
        addAnchors(newAnchors);
        lastActivityMs = activityAtMs;
        lastObservedTimeMs = activityAtMs;
        return true;
    }

    synchronized List<Entity> anchorSnapshot() {
        ArrayList<Entity> copy = new ArrayList<>(anchorCount);
        for (int index = 0; index < anchorCount; index++) {
            copy.add(anchors[index]);
        }
        return List.copyOf(copy);
    }

    synchronized List<SelectedEntity> selectedSnapshot() {
        ArrayList<SelectedEntity> copy =
                new ArrayList<>(selectedPlayerCount + selectedZombieCount);
        for (int index = 0; index < selectedPlayerCount; index++) {
            copy.add(selectedPlayers[index]);
        }
        for (int index = 0; index < selectedZombieCount; index++) {
            copy.add(selectedZombies[index]);
        }
        return List.copyOf(copy);
    }

    synchronized void replaceSelection(List<SelectedEntity> selected) {
        int players = 0;
        int zombies = 0;
        for (SelectedEntity entity : selected) {
            if (entity.kind() == EntityKind.PLAYER) {
                selectedPlayers[players++] = entity;
            } else {
                selectedZombies[zombies++] = entity;
            }
        }
        for (int index = players; index < selectedPlayerCount; index++) {
            selectedPlayers[index] = null;
        }
        for (int index = zombies; index < selectedZombieCount; index++) {
            selectedZombies[index] = null;
        }
        selectedPlayerCount = players;
        selectedZombieCount = zombies;
    }

    private Selection selection() {
        ArrayList<Long> players = new ArrayList<>(selectedPlayerCount);
        for (int index = 0; index < selectedPlayerCount; index++) {
            players.add(selectedPlayers[index].id());
        }
        ArrayList<Long> zombies = new ArrayList<>(selectedZombieCount);
        for (int index = 0; index < selectedZombieCount; index++) {
            zombies.add(selectedZombies[index].id());
        }
        return new Selection(players, zombies);
    }

    private boolean withinMergeDistance(Entity candidate) {
        return candidate != null && distanceToNearestAnchor(candidate) <= OUTER_RADIUS_TILES * 2.0;
    }

    private double distanceToNearestAnchor(Entity candidate) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int index = 0; index < anchorCount; index++) {
            Entity anchor = anchors[index];
            nearest =
                    Math.min(
                            nearest,
                            Math.hypot(
                                    candidate.x() - anchor.x(), candidate.y() - anchor.y()));
        }
        return nearest;
    }

    private boolean wasSelected(EntityKind kind, long entityId) {
        SelectedEntity[] selected =
                kind == EntityKind.PLAYER ? selectedPlayers : selectedZombies;
        int size = kind == EntityKind.PLAYER ? selectedPlayerCount : selectedZombieCount;
        for (int index = 0; index < size; index++) {
            if (selected[index].id() == entityId) {
                return true;
            }
        }
        return false;
    }

    private static boolean validAnchorSet(List<Entity> additions) {
        int players = 0;
        int zombies = 0;
        Entity[] unique = new Entity[MAX_ANCHORS];
        int uniqueCount = 0;
        for (Entity addition : additions) {
            if (addition == null) {
                return false;
            }
            boolean duplicate = false;
            for (int index = 0; index < uniqueCount; index++) {
                Entity existing = unique[index];
                if (existing.kind() == addition.kind() && existing.id() == addition.id()) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                unique[uniqueCount++] = addition;
                if (addition.kind() == EntityKind.PLAYER) {
                    players++;
                } else {
                    zombies++;
                }
                if (players > MAX_PLAYERS || zombies > MAX_ZOMBIES) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean addAnchors(List<Entity> additions) {
        ArrayList<Entity> combined = new ArrayList<>(anchorCount + additions.size());
        for (int index = 0; index < anchorCount; index++) {
            combined.add(anchors[index]);
        }
        combined.addAll(additions);
        if (!validAnchorSet(combined)) {
            return false;
        }
        for (Entity addition : additions) {
            int existing = anchorIndex(addition.kind(), addition.id());
            if (existing >= 0) {
                anchors[existing] = addition.asPrincipal();
            } else {
                anchors[anchorCount++] = addition.asPrincipal();
            }
        }
        return true;
    }

    private int anchorIndex(EntityKind kind, long entityId) {
        for (int index = 0; index < anchorCount; index++) {
            Entity anchor = anchors[index];
            if (anchor.kind() == kind && anchor.id() == entityId) {
                return index;
            }
        }
        return -1;
    }

    private static int insertBounded(
            RankedEntity[] selected, int size, RankedEntity candidate) {
        for (int index = 0; index < size; index++) {
            if (selected[index].entity().id() == candidate.entity().id()) {
                if (candidate.compareTo(selected[index]) >= 0) {
                    return size;
                }
                System.arraycopy(selected, index + 1, selected, index, size - index - 1);
                size--;
                break;
            }
        }

        int insertion = 0;
        while (insertion < size && selected[insertion].compareTo(candidate) <= 0) {
            insertion++;
        }
        if (insertion >= selected.length) {
            return size;
        }
        int newSize = Math.min(size + 1, selected.length);
        int moved = newSize - insertion - 1;
        if (moved > 0) {
            System.arraycopy(selected, insertion, selected, insertion + 1, moved);
        }
        selected[insertion] = candidate;
        return newSize;
    }

    private static int copySelected(
            RankedEntity[] ranked, int size, SelectedEntity[] target) {
        for (int index = 0; index < size; index++) {
            Entity entity = ranked[index].entity();
            target[index] =
                    new SelectedEntity(entity.kind(), entity.id(), entity.x(), entity.y());
        }
        return size;
    }

    public record Entity(EntityKind kind, long id, double x, double y, boolean principal) {
        public Entity {
            Objects.requireNonNull(kind, "kind");
            if (id < 0 || !Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("entity identity and coordinates must be finite");
            }
        }

        Entity asPrincipal() {
            return principal ? this : new Entity(kind, id, x, y, true);
        }
    }

    public record Selection(List<Long> playerIds, List<Long> zombieIds) {
        private static final Selection EMPTY = new Selection(List.of(), List.of());

        public Selection {
            playerIds = List.copyOf(playerIds);
            zombieIds = List.copyOf(zombieIds);
            if (playerIds.size() > MAX_PLAYERS || zombieIds.size() > MAX_ZOMBIES) {
                throw new IllegalArgumentException("selection exceeds combat bubble bounds");
            }
        }
    }

    record SelectedEntity(EntityKind kind, long id, double x, double y) {}

    private record RankedEntity(Entity entity, double distance)
            implements Comparable<RankedEntity> {
        @Override
        public int compareTo(RankedEntity other) {
            int priorityOrder =
                    Boolean.compare(other.entity().principal(), entity.principal());
            if (priorityOrder != 0) {
                return priorityOrder;
            }
            int distanceOrder = Double.compare(distance, other.distance);
            return distanceOrder != 0
                    ? distanceOrder
                    : Long.compare(entity.id(), other.entity().id());
        }
    }
}
