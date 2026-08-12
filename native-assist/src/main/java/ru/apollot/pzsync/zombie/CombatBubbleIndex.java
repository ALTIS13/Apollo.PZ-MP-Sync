package ru.apollot.pzsync.zombie;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import ru.apollot.pzsync.history.EntityKind;

public final class CombatBubbleIndex {
    public static final int DEFAULT_MAX_BUBBLES = 64;
    public static final int MAX_BUBBLES = 256;

    private final CombatBubble[] bubbles;
    private int size;
    private long nextBubbleId;
    private boolean idExhausted;
    private long lastTrustedTimeMs = -1;

    public CombatBubbleIndex() {
        this(DEFAULT_MAX_BUBBLES);
    }

    public CombatBubbleIndex(int capacity) {
        this(capacity, 1);
    }

    CombatBubbleIndex(int capacity, long firstBubbleId) {
        if (capacity <= 0 || capacity > MAX_BUBBLES || firstBubbleId <= 0) {
            throw new IllegalArgumentException("bubble capacity or initial id is invalid");
        }
        bubbles = new CombatBubble[capacity];
        nextBubbleId = firstBubbleId;
    }

    public synchronized OptionalLong observeHostileInteraction(
            long trustedCurrentTimeMs,
            CombatBubble.Entity first,
            CombatBubble.Entity second) {
        if (!validTime(trustedCurrentTimeMs) || first == null || second == null) {
            return OptionalLong.empty();
        }
        int primary = overlappingPrimary(first, second, trustedCurrentTimeMs);
        List<CombatBubble.Entity> anchors = List.of(first.asPrincipal(), second.asPrincipal());
        if (primary < 0) {
            if (activeCount(trustedCurrentTimeMs) == bubbles.length || idExhausted) {
                return OptionalLong.empty();
            }
            lastTrustedTimeMs = trustedCurrentTimeMs;
            pruneExpired(trustedCurrentTimeMs);
            long issuedId = nextBubbleId;
            if (nextBubbleId == Long.MAX_VALUE) {
                idExhausted = true;
            } else {
                nextBubbleId++;
            }
            bubbles[size++] = new CombatBubble(issuedId, trustedCurrentTimeMs, anchors);
            return OptionalLong.of(issuedId);
        }

        lastTrustedTimeMs = trustedCurrentTimeMs;
        pruneExpired(trustedCurrentTimeMs);
        primary = overlappingPrimary(first, second, trustedCurrentTimeMs);
        CombatBubble merged = bubbles[primary];
        ArrayList<Integer> overlapping = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            if (index != primary && bubbles[index].overlaps(first, second)) {
                overlapping.add(index);
            }
        }
        List<CombatBubble.Entity> mergedAnchors =
                selectMergedAnchors(merged, overlapping, anchors);
        List<CombatBubble.SelectedEntity> mergedSelection =
                selectMergedMembers(merged, overlapping, anchors);
        if (!merged.replaceAnchors(trustedCurrentTimeMs, mergedAnchors)) {
            return OptionalLong.empty();
        }
        merged.replaceSelection(mergedSelection);
        for (int index = overlapping.size() - 1; index >= 0; index--) {
            removeAt(overlapping.get(index));
        }
        return OptionalLong.of(merged.id());
    }

    public synchronized Optional<CombatBubble.Selection> select(
            long bubbleId,
            Iterable<CombatBubble.Entity> candidates,
            long trustedCurrentTimeMs) {
        if (bubbleId <= 0 || candidates == null || !validTime(trustedCurrentTimeMs)) {
            return Optional.empty();
        }
        if (activeIndexOf(bubbleId, trustedCurrentTimeMs) < 0) {
            return Optional.empty();
        }
        lastTrustedTimeMs = trustedCurrentTimeMs;
        pruneExpired(trustedCurrentTimeMs);
        int index = indexOf(bubbleId);
        return index < 0
                ? Optional.empty()
                : Optional.of(bubbles[index].select(candidates, trustedCurrentTimeMs));
    }

    public synchronized List<Long> activeBubbles(long trustedCurrentTimeMs) {
        if (!validTime(trustedCurrentTimeMs)) {
            return List.of();
        }
        lastTrustedTimeMs = trustedCurrentTimeMs;
        pruneExpired(trustedCurrentTimeMs);
        ArrayList<Long> ids = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ids.add(bubbles[index].id());
        }
        ids.sort(Long::compare);
        return List.copyOf(ids);
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return bubbles.length;
    }

    private boolean validTime(long trustedCurrentTimeMs) {
        return trustedCurrentTimeMs >= 0 && trustedCurrentTimeMs >= lastTrustedTimeMs;
    }

    private void pruneExpired(long trustedCurrentTimeMs) {
        for (int index = size - 1; index >= 0; index--) {
            if (!bubbles[index].activeAt(trustedCurrentTimeMs)) {
                removeAt(index);
            }
        }
    }

    private int indexOf(long bubbleId) {
        for (int index = 0; index < size; index++) {
            if (bubbles[index].id() == bubbleId) {
                return index;
            }
        }
        return -1;
    }

    private int activeIndexOf(long bubbleId, long trustedCurrentTimeMs) {
        for (int index = 0; index < size; index++) {
            if (bubbles[index].id() == bubbleId
                    && bubbles[index].activeAt(trustedCurrentTimeMs)) {
                return index;
            }
        }
        return -1;
    }

    private int activeCount(long trustedCurrentTimeMs) {
        int count = 0;
        for (int index = 0; index < size; index++) {
            if (bubbles[index].activeAt(trustedCurrentTimeMs)) {
                count++;
            }
        }
        return count;
    }

    private int overlappingPrimary(
            CombatBubble.Entity first,
            CombatBubble.Entity second,
            long trustedCurrentTimeMs) {
        int primary = -1;
        for (int index = 0; index < size; index++) {
            if (bubbles[index].activeAt(trustedCurrentTimeMs)
                    && bubbles[index].overlaps(first, second)
                    && (primary < 0 || bubbles[index].id() < bubbles[primary].id())) {
                primary = index;
            }
        }
        return primary;
    }

    private List<CombatBubble.Entity> selectMergedAnchors(
            CombatBubble primary,
            List<Integer> overlapping,
            List<CombatBubble.Entity> newAnchors) {
        RankedAnchor[] players = new RankedAnchor[CombatBubble.MAX_PLAYERS];
        RankedAnchor[] zombies = new RankedAnchor[CombatBubble.MAX_ZOMBIES];
        int playerCount = 0;
        int zombieCount = 0;
        for (CombatBubble.Entity anchor : newAnchors) {
            RankedAnchor ranked = new RankedAnchor(anchor.asPrincipal(), true, 0.0);
            if (anchor.kind() == EntityKind.PLAYER) {
                playerCount = insertBounded(players, playerCount, ranked);
            } else {
                zombieCount = insertBounded(zombies, zombieCount, ranked);
            }
        }
        ArrayList<CombatBubble.Entity> existing = new ArrayList<>(CombatBubble.MAX_ANCHORS);
        existing.addAll(primary.anchorSnapshot());
        for (int index : overlapping) {
            existing.addAll(bubbles[index].anchorSnapshot());
        }
        for (CombatBubble.Entity anchor : existing) {
            RankedAnchor ranked =
                    new RankedAnchor(anchor, false, distanceToNearest(anchor, newAnchors));
            if (anchor.kind() == EntityKind.PLAYER) {
                playerCount = insertBounded(players, playerCount, ranked);
            } else {
                zombieCount = insertBounded(zombies, zombieCount, ranked);
            }
        }
        ArrayList<CombatBubble.Entity> selected = new ArrayList<>(playerCount + zombieCount);
        for (int index = 0; index < playerCount; index++) {
            selected.add(players[index].entity());
        }
        for (int index = 0; index < zombieCount; index++) {
            selected.add(zombies[index].entity());
        }
        return List.copyOf(selected);
    }

    private static int insertBounded(RankedAnchor[] target, int size, RankedAnchor candidate) {
        for (int index = 0; index < size; index++) {
            CombatBubble.Entity existing = target[index].entity();
            if (existing.kind() == candidate.entity().kind()
                    && existing.id() == candidate.entity().id()) {
                if (candidate.compareTo(target[index]) >= 0) {
                    return size;
                }
                System.arraycopy(target, index + 1, target, index, size - index - 1);
                size--;
                break;
            }
        }
        int insertion = 0;
        while (insertion < size && target[insertion].compareTo(candidate) <= 0) {
            insertion++;
        }
        if (insertion >= target.length) {
            return size;
        }
        int newSize = Math.min(size + 1, target.length);
        int moved = newSize - insertion - 1;
        if (moved > 0) {
            System.arraycopy(target, insertion, target, insertion + 1, moved);
        }
        target[insertion] = candidate;
        return newSize;
    }

    private static double distanceToNearest(
            CombatBubble.Entity candidate,
            List<CombatBubble.Entity> anchors) {
        double nearest = Double.POSITIVE_INFINITY;
        for (CombatBubble.Entity anchor : anchors) {
            nearest =
                    Math.min(
                            nearest,
                            Math.hypot(candidate.x() - anchor.x(), candidate.y() - anchor.y()));
        }
        return nearest;
    }

    private List<CombatBubble.SelectedEntity> selectMergedMembers(
            CombatBubble primary,
            List<Integer> overlapping,
            List<CombatBubble.Entity> currentPrincipals) {
        RankedMember[] players = new RankedMember[CombatBubble.MAX_PLAYERS];
        RankedMember[] zombies = new RankedMember[CombatBubble.MAX_ZOMBIES];
        int playerCount = 0;
        int zombieCount = 0;
        for (CombatBubble.Entity principal : currentPrincipals) {
            CombatBubble.SelectedEntity selected =
                    new CombatBubble.SelectedEntity(
                            principal.kind(), principal.id(), principal.x(), principal.y());
            RankedMember ranked = new RankedMember(selected, true, 0.0);
            if (principal.kind() == EntityKind.PLAYER) {
                playerCount = insertBounded(players, playerCount, ranked);
            } else {
                zombieCount = insertBounded(zombies, zombieCount, ranked);
            }
        }

        ArrayList<CombatBubble.SelectedEntity> previous =
                new ArrayList<>(CombatBubble.MAX_ANCHORS);
        previous.addAll(primary.selectedSnapshot());
        for (int index : overlapping) {
            previous.addAll(bubbles[index].selectedSnapshot());
        }
        for (CombatBubble.SelectedEntity selected : previous) {
            RankedMember ranked =
                    new RankedMember(
                            selected,
                            false,
                            distanceToNearest(selected.x(), selected.y(), currentPrincipals));
            if (selected.kind() == EntityKind.PLAYER) {
                playerCount = insertBounded(players, playerCount, ranked);
            } else {
                zombieCount = insertBounded(zombies, zombieCount, ranked);
            }
        }

        ArrayList<CombatBubble.SelectedEntity> result =
                new ArrayList<>(playerCount + zombieCount);
        for (int index = 0; index < playerCount; index++) {
            result.add(players[index].entity());
        }
        for (int index = 0; index < zombieCount; index++) {
            result.add(zombies[index].entity());
        }
        return List.copyOf(result);
    }

    private static int insertBounded(
            RankedMember[] target, int size, RankedMember candidate) {
        for (int index = 0; index < size; index++) {
            CombatBubble.SelectedEntity existing = target[index].entity();
            if (existing.kind() == candidate.entity().kind()
                    && existing.id() == candidate.entity().id()) {
                if (candidate.compareTo(target[index]) >= 0) {
                    return size;
                }
                System.arraycopy(target, index + 1, target, index, size - index - 1);
                size--;
                break;
            }
        }
        int insertion = 0;
        while (insertion < size && target[insertion].compareTo(candidate) <= 0) {
            insertion++;
        }
        if (insertion >= target.length) {
            return size;
        }
        int newSize = Math.min(size + 1, target.length);
        int moved = newSize - insertion - 1;
        if (moved > 0) {
            System.arraycopy(target, insertion, target, insertion + 1, moved);
        }
        target[insertion] = candidate;
        return newSize;
    }

    private static double distanceToNearest(
            double x, double y, List<CombatBubble.Entity> anchors) {
        double nearest = Double.POSITIVE_INFINITY;
        for (CombatBubble.Entity anchor : anchors) {
            nearest = Math.min(nearest, Math.hypot(x - anchor.x(), y - anchor.y()));
        }
        return nearest;
    }

    private void removeAt(int index) {
        int moved = size - index - 1;
        if (moved > 0) {
            System.arraycopy(bubbles, index + 1, bubbles, index, moved);
        }
        bubbles[--size] = null;
    }

    private record RankedAnchor(
            CombatBubble.Entity entity,
            boolean currentPrincipal,
            double distance)
            implements Comparable<RankedAnchor> {
        @Override
        public int compareTo(RankedAnchor other) {
            int currentOrder = Boolean.compare(other.currentPrincipal, currentPrincipal);
            if (currentOrder != 0) {
                return currentOrder;
            }
            int distanceOrder = Double.compare(distance, other.distance);
            return distanceOrder != 0
                    ? distanceOrder
                    : Long.compare(entity.id(), other.entity.id());
        }
    }

    private record RankedMember(
            CombatBubble.SelectedEntity entity,
            boolean currentPrincipal,
            double distance)
            implements Comparable<RankedMember> {
        @Override
        public int compareTo(RankedMember other) {
            int currentOrder = Boolean.compare(other.currentPrincipal, currentPrincipal);
            if (currentOrder != 0) {
                return currentOrder;
            }
            int distanceOrder = Double.compare(distance, other.distance);
            return distanceOrder != 0
                    ? distanceOrder
                    : Long.compare(entity.id(), other.entity.id());
        }
    }
}
