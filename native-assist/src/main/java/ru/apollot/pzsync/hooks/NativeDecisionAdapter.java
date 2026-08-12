package ru.apollot.pzsync.hooks;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.apollot.pzsync.attack.AttackBudget;
import ru.apollot.pzsync.attack.AttackLedger;
import ru.apollot.pzsync.attack.AttackToken;
import ru.apollot.pzsync.attack.TargetGeneration;
import ru.apollot.pzsync.attack.WeaponObservation;
import ru.apollot.pzsync.bridge.ApolloNativeBridge;
import ru.apollot.pzsync.geometry.CollisionProbe;
import ru.apollot.pzsync.geometry.CurrentHitRequest;
import ru.apollot.pzsync.geometry.HistoricalPose;
import ru.apollot.pzsync.geometry.RewindRequest;
import ru.apollot.pzsync.geometry.RewindValidator;
import ru.apollot.pzsync.geometry.Vec3;
import ru.apollot.pzsync.geometry.WeaponProfile;
import ru.apollot.pzsync.geometry.ZombieCurrentHitValidator;
import ru.apollot.pzsync.history.EntityKey;
import ru.apollot.pzsync.history.EntityKind;
import ru.apollot.pzsync.history.HistoryStore;
import ru.apollot.pzsync.history.NetworkQualityEstimator;
import ru.apollot.pzsync.history.RewindClock;
import ru.apollot.pzsync.history.RewindWindow;
import ru.apollot.pzsync.history.StateSample;
import ru.apollot.pzsync.zombie.ZombieHandoffPolicy;
import ru.apollot.pzsync.zombie.ZombieKeyframePolicy;

public final class NativeDecisionAdapter {
    private static final int MAX_CURRENT_ATTACKERS = AttackLedger.MAX_PLAYER_CAPACITY;
    private static final long NETWORK_SAMPLE_FRESHNESS_MS = 1_000;

    private final AttackLedger attackLedger;
    private final RewindValidator validator;
    private final ZombieCurrentHitValidator zombieCurrentValidator =
            new ZombieCurrentHitValidator();
    private final CollisionProbe collisionProbe;
    private final PacketAccess packetAccess;
    private final HistoryStore history;
    private final PlayerStateObserver playerObserver;
    private final ZombieStateObserver zombieObserver;
    private final LinkedHashMap<Attacker, AttackToken> currentAttacks = new LinkedHashMap<>();
    private final LinkedHashMap<Short, Integer> connectionGenerations = new LinkedHashMap<>();
    private final LinkedHashMap<Attacker, NetworkQualityEstimator> networkEstimators =
            new LinkedHashMap<>();
    private long hitGateRequests;
    private long hitGateAccepts;
    private long hitGateRejections;
    private long zombieStateCommits;
    private long zombieOwnerChanges;
    private ApolloNativeBridge.Handshake configuration;

    public NativeDecisionAdapter(
            AttackLedger attackLedger,
            RewindValidator validator,
            CollisionProbe collisionProbe,
            PacketAccess packetAccess,
            HistoryStore history,
            PlayerStateObserver playerObserver,
            ZombieStateObserver zombieObserver) {
        this.attackLedger = Objects.requireNonNull(attackLedger, "attackLedger");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.collisionProbe = Objects.requireNonNull(collisionProbe, "collisionProbe");
        this.packetAccess = Objects.requireNonNull(packetAccess, "packetAccess");
        this.history = Objects.requireNonNull(history, "history");
        this.playerObserver = Objects.requireNonNull(playerObserver, "playerObserver");
        this.zombieObserver = Objects.requireNonNull(zombieObserver, "zombieObserver");
    }

    synchronized void observePlayer(Object receiver, Object[] arguments) {
        boolean attackTracking = attackFeaturesEnabled();
        if (!attackTracking
                && (configuration == null || !configuration.playerNativeAssistEnabled())) {
            return;
        }
        Optional<PlayerFacts> selected = packetAccess.playerState(receiver, arguments);
        if (selected.isEmpty()) {
            return;
        }
        PlayerFacts facts = selected.orElseThrow();
        try {
            validatePlayerFacts(facts);
        } catch (ExpectedFactAbsence malformed) {
            return;
        }
        PlayerStateObserver.Observation observation = playerObservation(facts);
        if (attackTracking && !observeConnectionGeneration(observation)) {
            return;
        }
        if (configuration.playerNativeAssistEnabled()) {
            playerObserver.observe(observation);
        }
    }

    synchronized void observeAttackOpen(Object receiver, Object[] arguments) {
        if (!attackFeaturesEnabled()) {
            return;
        }
        Optional<AttackFacts> selected = packetAccess.nativeAttack(receiver, arguments);
        if (selected.isEmpty()) {
            return;
        }
        AttackFacts facts = selected.orElseThrow();
        final WeaponObservation weapon;
        final AttackBudget budget;
        final short playerOnlineId;
        final int connectionGeneration;
        try {
            validateAttackFacts(facts);
            playerOnlineId = nativePlayerId(facts.playerOnlineId());
            connectionGeneration = nativeGeneration(facts.connectionGeneration());
        } catch (ExpectedFactAbsence malformed) {
            return;
        }
        weapon = new WeaponObservation(
                facts.weaponInstanceId(), facts.attackMode(), facts.projectile());
        budget = new AttackBudget(
                facts.nativeMaxHitCount(), facts.nativeProjectileCount());
        Optional<AttackToken> opened =
                attackLedger.openFromNativeAttackCollisionCheck(
                        playerOnlineId,
                        connectionGeneration,
                        facts.openedAtMs(),
                        weapon,
                        budget);
        Attacker attacker = new Attacker(playerOnlineId, connectionGeneration);
        if (opened.isEmpty()) {
            return;
        }
        if (!currentAttacks.containsKey(attacker)
                && currentAttacks.size() == MAX_CURRENT_ATTACKERS) {
            return;
        }
        currentAttacks.put(attacker, opened.orElseThrow());
    }

    synchronized boolean allowHit(Object receiver, Object[] arguments) {
        hitGateRequests++;
        if (!attackFeaturesEnabled()) {
            hitGateAccepts++;
            return true;
        }

        final Optional<HitFacts> selected = packetAccess.nativeHit(receiver, arguments);
        if (selected.isEmpty()) {
            if (!packetAccess.knownHitVariant(receiver)) {
                hitGateAccepts++;
                return true;
            }
            return rejectHit();
        }
        HitFacts facts = selected.orElseThrow();
        boolean pvp = facts.route() == HitRoute.PLAYER_TO_PLAYER;
        if ((pvp && !configuration.pvpRewindEnabled())
                || (!pvp && !configuration.pveRewindEnabled())) {
            hitGateAccepts++;
            return true;
        }
        if (facts.validationStage() != NativeValidationStage.VALIDATED_PRE_APPLY) {
            return rejectHit();
        }

        if (facts.route() == HitRoute.ZOMBIE_TO_PLAYER) {
            try {
                validateHitFacts(facts);
                return acceptOrReject(validateZombieCurrent(facts));
            } catch (ExpectedFactAbsence malformed) {
                return rejectHit();
            }
        }

        final short playerOnlineId;
        final int connectionGeneration;
        final RewindRequest request;
        try {
            validateHitFacts(facts);
            playerOnlineId = nativePlayerId(facts.playerOnlineId());
            connectionGeneration = nativeGeneration(facts.connectionGeneration());
            Attacker attackerKey = new Attacker(playerOnlineId, connectionGeneration);
            AttackToken token = currentAttacks.get(attackerKey);
            if (token == null) {
                return rejectHit();
            }
            request = request(facts, token, attackerKey);
        } catch (ExpectedFactAbsence malformed) {
            return rejectHit();
        }

        boolean accepted = validator.validateAndConsume(attackLedger, request, collisionProbe)
                .accepted();
        if (accepted) {
            hitGateAccepts++;
            return true;
        }
        return rejectHit();
    }

    private boolean validateZombieCurrent(HitFacts facts) {
        HistoricalPose attacker = pose(EntityKind.ZOMBIE, facts.currentAttacker());
        HistoricalPose target = pose(EntityKind.PLAYER, facts.currentTarget());
        WeaponProfile weapon = new WeaponProfile(
                new WeaponObservation(
                        facts.weaponInstanceId(), facts.attackMode(), facts.projectile()),
                facts.serverRangeTiles(),
                facts.meleeHalfAngleDegrees(),
                facts.equipped(),
                facts.ammoAuthorized(),
                facts.nativeProjectileBudget());
        return zombieCurrentValidator.validate(
                new CurrentHitRequest(
                        facts.trustedCurrentTimeMs(),
                        EntityKind.ZOMBIE,
                        EntityKind.PLAYER,
                        weapon,
                        attacker,
                        target,
                        true),
                collisionProbe).accepted();
    }

    private boolean acceptOrReject(boolean accepted) {
        if (accepted) {
            hitGateAccepts++;
            return true;
        }
        return rejectHit();
    }

    Object captureZombieState(Object receiver, Object[] arguments) {
        if (configuration == null || !configuration.zombieCombatBubbleEnabled()) {
            return null;
        }
        return packetAccess.captureZombieState(receiver, arguments);
    }

    void completeZombieState(Object capture) {
        Optional<ZombieStateFacts> selected = packetAccess.completeZombieState(capture);
        if (selected.isEmpty()) {
            return;
        }
        ZombieStateFacts facts = selected.orElseThrow();
        try {
            validateZombieStateFacts(facts);
        } catch (ExpectedFactAbsence malformed) {
            return;
        }
        if (facts.registryApplied()) {
            zombieStateCommits++;
            return;
        }
        var observation = new ZombieStateObserver.StateObservation(
                new EntityKey(EntityKind.ZOMBIE, facts.zombieId(), facts.generation()),
                new StateSample(
                        facts.serverTimeMs(),
                        facts.x(),
                        facts.y(),
                        facts.floor(),
                        facts.yawRadians(),
                        facts.ownerEpoch()));
        zombieObserver.observeState(observation);
    }

    void observeZombieState(Object receiver, Object[] arguments) {
        Object capture = captureZombieState(receiver, arguments);
        if (capture != null) completeZombieState(capture);
    }

    Object captureZombieOwnership(Object receiver, Object[] arguments) {
        if (configuration == null || !configuration.zombieCombatBubbleEnabled()) {
            return null;
        }
        return packetAccess.captureZombieOwnership(receiver, arguments);
    }

    void completeZombieOwnership(Object capture) {
        Optional<ZombieOwnershipFacts> selected = packetAccess.completeZombieOwnership(capture);
        if (selected.isEmpty()) {
            return;
        }
        ZombieOwnershipFacts facts = selected.orElseThrow();
        try {
            requireExpected(facts.zombieId() >= 0
                    && facts.generation() >= 0
                    && facts.trustedCurrentTimeMs() >= 0);
        } catch (ExpectedFactAbsence malformed) {
            return;
        }
        if (facts.registryApplied()) {
            if (facts.changed()) zombieOwnerChanges++;
            return;
        }
        var observation = new ZombieStateObserver.OwnershipObservation(
                new EntityKey(EntityKind.ZOMBIE, facts.zombieId(), facts.generation()),
                new ZombieHandoffPolicy.Observation(
                        facts.zombieId(),
                        facts.currentNativeOwnerId(),
                        facts.targetOwnerId(),
                        facts.trustedCurrentTimeMs(),
                        facts.grapple()));
        zombieObserver.observeOwnership(observation);
    }

    void observeZombieOwnership(Object receiver, Object[] arguments) {
        Object capture = captureZombieOwnership(receiver, arguments);
        if (capture != null) completeZombieOwnership(capture);
    }

    void observeZombieKeyframe(Object receiver, Object[] arguments) {
        if (configuration == null || !configuration.zombieCombatBubbleEnabled()) {
            return;
        }
        Optional<ZombieKeyframeFacts> selected = packetAccess.zombieKeyframe(receiver, arguments);
        if (selected.isEmpty()) {
            return;
        }
        int ordinal = selected.orElseThrow().eventOrdinal();
        ZombieKeyframePolicy.ZombieKeyframeEvent[] events =
                ZombieKeyframePolicy.ZombieKeyframeEvent.values();
        if (ordinal < 0 || ordinal >= events.length) {
            return;
        }
        zombieObserver.observeKeyframe(
                new ZombieStateObserver.KeyframeObservation(events[ordinal]));
    }

    synchronized Map<String, Long> metrics() {
        var snapshot = new LinkedHashMap<String, Long>();
        snapshot.put("hit_gate_requests", hitGateRequests);
        snapshot.put("hit_gate_accepts", hitGateAccepts);
        snapshot.put("hit_gate_rejections", hitGateRejections);
        snapshot.put("zombie_state_commits", zombieStateCommits);
        snapshot.put("zombie_owner_changes", zombieOwnerChanges);
        return Collections.unmodifiableMap(snapshot);
    }

    synchronized void configure(ApolloNativeBridge.Handshake handshake) {
        configuration = Objects.requireNonNull(handshake, "handshake");
    }

    synchronized void clearConfiguration() {
        configuration = null;
        currentAttacks.clear();
        networkEstimators.clear();
        attackLedger.clearForServerDeauthorization();
    }

    private RewindRequest request(HitFacts facts, AttackToken token, Attacker attackerKey) {
        if (facts.trustedCurrentTimeMs() < 0
                || facts.networkSampleTimeMs() < 0
                || !Double.isFinite(facts.networkRttMs())
                || facts.networkRttMs() < 0.0) {
            throw new ExpectedFactAbsence();
        }
        HistoricalPose currentAttacker = pose(EntityKind.PLAYER, facts.currentAttacker());
        HistoricalPose currentTarget = pose(facts.targetKind(), facts.currentTarget());
        WeaponObservation weaponObservation =
                new WeaponObservation(
                        facts.weaponInstanceId(), facts.attackMode(), facts.projectile());
        WeaponProfile weapon =
                new WeaponProfile(
                        weaponObservation,
                        facts.serverRangeTiles(),
                        facts.meleeHalfAngleDegrees(),
                        facts.equipped(),
                        facts.ammoAuthorized(),
                        facts.nativeProjectileBudget());
        RewindWindow window = rewindWindow(attackerKey, facts);
        EntityKey attackerIdentity = currentAttacker.identity();
        EntityKey targetIdentity = currentTarget.identity();
        Optional<HistoricalPose> historicalAttacker =
                historicalPose(attackerIdentity, currentAttacker, facts.trustedCurrentTimeMs(), window);
        Optional<HistoricalPose> historicalTarget =
                historicalPose(targetIdentity, currentTarget, facts.trustedCurrentTimeMs(), window);
        return new RewindRequest(
                facts.trustedCurrentTimeMs(),
                token,
                new TargetGeneration(
                        facts.targetKind(),
                        facts.currentTarget().entityId(),
                        facts.currentTarget().generation()),
                weapon,
                currentAttacker,
                currentTarget,
                facts.currentTarget().ownerEpochStartedAtMs(),
                facts.currentTarget().historyEligible(),
                historicalAttacker,
                historicalTarget,
                window,
                true);
    }

    private RewindWindow rewindWindow(Attacker attacker, HitFacts facts) {
        if (facts.networkRttMs() <= 0.0
                || facts.networkSampleTimeMs() > facts.trustedCurrentTimeMs()
                || facts.trustedCurrentTimeMs() - facts.networkSampleTimeMs()
                        > NETWORK_SAMPLE_FRESHNESS_MS) {
            return new RewindClock(configuration.maxRewindMs()).compute(null);
        }
        NetworkQualityEstimator estimator = networkEstimators.get(attacker);
        if (estimator == null) {
            if (networkEstimators.size() == MAX_CURRENT_ATTACKERS) {
                return new RewindClock(configuration.maxRewindMs()).compute(null);
            }
            estimator = new NetworkQualityEstimator();
            networkEstimators.put(attacker, estimator);
        }
        estimator.addSample(facts.networkSampleTimeMs(), facts.networkRttMs());
        return new RewindClock(configuration.maxRewindMs())
                .compute(estimator.estimateAt(facts.trustedCurrentTimeMs()).orElse(null));
    }

    private Optional<HistoricalPose> historicalPose(
            EntityKey identity,
            HistoricalPose current,
            long trustedCurrentTimeMs,
            RewindWindow window) {
        return history.sampleForRewind(
                        identity, trustedCurrentTimeMs, window, current.ownerEpoch())
                .map(sample -> new HistoricalPose(
                        identity,
                        sample.serverTimeMs(),
                        new Vec3(sample.x(), sample.y(), sample.floor()),
                        sample.yaw(),
                        sample.ownerEpoch(),
                        current.aliveAndEligible()));
    }

    private static HistoricalPose pose(EntityKind kind, PoseFacts facts) {
        return new HistoricalPose(
                new EntityKey(kind, facts.entityId(), facts.generation()),
                facts.serverTimeMs(),
                new Vec3(facts.x(), facts.y(), facts.floor()),
                facts.yawRadians(),
                facts.ownerEpoch(),
                facts.aliveAndEligible());
    }

    private static PlayerStateObserver.Observation playerObservation(PlayerFacts facts) {
        return new PlayerStateObserver.Observation(
                new EntityKey(EntityKind.PLAYER, facts.playerId(), facts.connectionGeneration()),
                new StateSample(
                        facts.serverTimeMs(),
                        facts.x(),
                        facts.y(),
                        facts.floor(),
                        facts.yawRadians(),
                        facts.ownerEpoch()));
    }

    private boolean attackFeaturesEnabled() {
        return configuration != null
                && configuration.nativeAssistEnabled()
                && configuration.serverRewindEnabled()
                && (configuration.pvpRewindEnabled() || configuration.pveRewindEnabled());
    }

    private boolean rejectHit() {
        hitGateRejections++;
        return false;
    }

    private boolean observeConnectionGeneration(PlayerStateObserver.Observation observation) {
        long playerId = observation.identity().id();
        long generation = observation.identity().generation();
        if (playerId > Short.MAX_VALUE || generation <= 0 || generation > Integer.MAX_VALUE) {
            return false;
        }
        short nativePlayerId = (short) playerId;
        int observedGeneration = (int) generation;
        Integer current = connectionGenerations.get(nativePlayerId);
        if (current != null && current == observedGeneration) {
            return true;
        }
        if ((current == null && observedGeneration != 1)
                || (current != null
                        && (current == Integer.MAX_VALUE
                                || observedGeneration != current + 1))) {
            return false;
        }
        if (current == null
                && connectionGenerations.size() == AttackLedger.MAX_PLAYER_CAPACITY) {
            return false;
        }
        int issued = attackLedger.beginConnection(nativePlayerId).orElse(-1);
        if (issued != observedGeneration) {
            return false;
        }
        removeOlderGeneration(nativePlayerId, observedGeneration);
        connectionGenerations.put(nativePlayerId, issued);
        return true;
    }

    private void removeOlderGeneration(short playerId, int generation) {
        Iterator<Attacker> attacks = currentAttacks.keySet().iterator();
        while (attacks.hasNext()) {
            Attacker attacker = attacks.next();
            if (attacker.playerOnlineId() == playerId
                    && attacker.connectionGeneration() != generation) {
                attacks.remove();
            }
        }
        Iterator<Attacker> estimators = networkEstimators.keySet().iterator();
        while (estimators.hasNext()) {
            Attacker attacker = estimators.next();
            if (attacker.playerOnlineId() == playerId
                    && attacker.connectionGeneration() != generation) {
                estimators.remove();
            }
        }
    }

    private static short nativePlayerId(long value) {
        if (value < 0 || value > Short.MAX_VALUE) {
            throw new ExpectedFactAbsence();
        }
        return (short) value;
    }

    private static int nativeGeneration(long value) {
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new ExpectedFactAbsence();
        }
        return (int) value;
    }

    private static void validatePlayerFacts(PlayerFacts facts) {
        requireExpected(facts != null
                && facts.playerId() >= 0
                && facts.connectionGeneration() >= 0
                && validState(
                        facts.serverTimeMs(),
                        facts.x(),
                        facts.y(),
                        facts.yawRadians(),
                        facts.ownerEpoch()));
    }

    private static void validateAttackFacts(AttackFacts facts) {
        requireExpected(facts != null
                && facts.openedAtMs() >= 0
                && facts.weaponInstanceId() >= 0
                && validAttackMode(facts.attackMode())
                && facts.nativeMaxHitCount() > 0
                && facts.nativeMaxHitCount() <= AttackBudget.MAX_TARGETS_PER_ATTACK
                && facts.nativeProjectileCount() >= 0
                && facts.nativeProjectileCount() <= AttackBudget.MAX_TARGETS_PER_ATTACK);
    }

    private static void validateHitFacts(HitFacts facts) {
        requireExpected(facts != null
                && facts.targetKind() != null
                && facts.route() != null
                && ((facts.route() == HitRoute.PLAYER_TO_PLAYER
                                && facts.targetKind() == EntityKind.PLAYER)
                        || (facts.route() == HitRoute.PLAYER_TO_ZOMBIE
                                && facts.targetKind() == EntityKind.ZOMBIE)
                        || (facts.route() == HitRoute.ZOMBIE_TO_PLAYER
                                && facts.targetKind() == EntityKind.PLAYER))
                && facts.validationStage() != null
                && facts.weaponInstanceId() >= 0
                && validAttackMode(facts.attackMode())
                && Double.isFinite(facts.serverRangeTiles())
                && facts.serverRangeTiles() > 0.0
                && Double.isFinite(facts.meleeHalfAngleDegrees())
                && facts.meleeHalfAngleDegrees() >= 0.0
                && facts.meleeHalfAngleDegrees() <= 180.0
                && facts.nativeProjectileBudget() >= 0
                && facts.nativeProjectileBudget() <= AttackBudget.MAX_TARGETS_PER_ATTACK
                && facts.trustedCurrentTimeMs() >= 0
                && facts.networkSampleTimeMs() >= 0
                && Double.isFinite(facts.networkRttMs())
                && facts.networkRttMs() >= 0.0
                && validPose(facts.currentAttacker())
                && validPose(facts.currentTarget()));
    }

    private static void validateZombieStateFacts(ZombieStateFacts facts) {
        requireExpected(facts != null
                && facts.zombieId() >= 0
                && facts.generation() >= 0
                && validState(
                        facts.serverTimeMs(),
                        facts.x(),
                        facts.y(),
                        facts.yawRadians(),
                        facts.ownerEpoch()));
    }

    private static boolean validPose(PoseFacts facts) {
        return facts != null
                && facts.entityId() >= 0
                && facts.generation() >= 0
                && facts.aliveAndEligible()
                && validState(
                        facts.serverTimeMs(),
                        facts.x(),
                        facts.y(),
                        facts.yawRadians(),
                        facts.ownerEpoch());
    }

    private static boolean validState(
            long serverTimeMs, double x, double y, double yaw, long ownerEpoch) {
        return serverTimeMs >= 0
                && Double.isFinite(x)
                && Double.isFinite(y)
                && Double.isFinite(yaw)
                && ownerEpoch >= 0;
    }

    private static boolean validAttackMode(String mode) {
        return mode != null
                && !mode.isBlank()
                && mode.length() <= WeaponObservation.MAX_ATTACK_MODE_LENGTH;
    }

    private static void requireExpected(boolean condition) {
        if (!condition) {
            throw new ExpectedFactAbsence();
        }
    }

    public interface PacketAccess {
        Optional<PlayerFacts> playerState(Object receiver, Object[] arguments);

        Optional<AttackFacts> nativeAttack(Object receiver, Object[] arguments);

        Optional<HitFacts> nativeHit(Object receiver, Object[] arguments);

        default boolean knownHitVariant(Object receiver) { return true; }

        Optional<ZombieStateFacts> zombieState(Object receiver, Object[] arguments);

        Optional<ZombieOwnershipFacts> zombieOwnership(Object receiver, Object[] arguments);

        Optional<ZombieKeyframeFacts> zombieKeyframe(Object receiver, Object[] arguments);

        default Object captureZombieState(Object receiver, Object[] arguments) {
            return new DeferredZombieCall(
                    receiver, arguments == null ? new Object[0] : arguments.clone());
        }

        default Optional<ZombieStateFacts> completeZombieState(Object capture) {
            if (!(capture instanceof DeferredZombieCall call)) return Optional.empty();
            return zombieState(call.receiver(), call.arguments());
        }

        default Object captureZombieOwnership(Object receiver, Object[] arguments) {
            return new DeferredZombieCall(
                    receiver, arguments == null ? new Object[0] : arguments.clone());
        }

        default Optional<ZombieOwnershipFacts> completeZombieOwnership(Object capture) {
            if (!(capture instanceof DeferredZombieCall call)) return Optional.empty();
            return zombieOwnership(call.receiver(), call.arguments());
        }
    }

    private record DeferredZombieCall(Object receiver, Object[] arguments) {}

    public record PlayerFacts(
            long playerId,
            long connectionGeneration,
            long serverTimeMs,
            double x,
            double y,
            int floor,
            double yawRadians,
            long ownerEpoch) {}

    public record AttackFacts(
            long playerOnlineId,
            long connectionGeneration,
            long openedAtMs,
            long weaponInstanceId,
            String attackMode,
            boolean projectile,
            int nativeMaxHitCount,
            int nativeProjectileCount) {}

    public record PoseFacts(
            long entityId,
            long generation,
            long serverTimeMs,
            double x,
            double y,
            int floor,
            double yawRadians,
            long ownerEpoch,
            boolean aliveAndEligible,
            long ownerEpochStartedAtMs,
            boolean historyEligible) {
        public PoseFacts(
                long entityId,
                long generation,
                long serverTimeMs,
                double x,
                double y,
                int floor,
                double yawRadians,
                long ownerEpoch,
                boolean aliveAndEligible) {
            this(
                    entityId,
                    generation,
                    serverTimeMs,
                    x,
                    y,
                    floor,
                    yawRadians,
                    ownerEpoch,
                    aliveAndEligible,
                    -1,
                    true);
        }
    }

    public record HitFacts(
            long playerOnlineId,
            long connectionGeneration,
            long trustedCurrentTimeMs,
            EntityKind targetKind,
            PoseFacts currentAttacker,
            PoseFacts currentTarget,
            long weaponInstanceId,
            String attackMode,
            boolean projectile,
            double serverRangeTiles,
            double meleeHalfAngleDegrees,
            boolean equipped,
            boolean ammoAuthorized,
            int nativeProjectileBudget,
            long networkSampleTimeMs,
            double networkRttMs,
            NativeValidationStage validationStage,
            HitRoute route) {
        public HitFacts(
                long playerOnlineId,
                long connectionGeneration,
                long trustedCurrentTimeMs,
                EntityKind targetKind,
                PoseFacts currentAttacker,
                PoseFacts currentTarget,
                long weaponInstanceId,
                String attackMode,
                boolean projectile,
                double serverRangeTiles,
                double meleeHalfAngleDegrees,
                boolean equipped,
                boolean ammoAuthorized,
                int nativeProjectileBudget,
                long networkSampleTimeMs,
                double networkRttMs,
                NativeValidationStage validationStage) {
            this(
                    playerOnlineId,
                    connectionGeneration,
                    trustedCurrentTimeMs,
                    targetKind,
                    currentAttacker,
                    currentTarget,
                    weaponInstanceId,
                    attackMode,
                    projectile,
                    serverRangeTiles,
                    meleeHalfAngleDegrees,
                    equipped,
                    ammoAuthorized,
                    nativeProjectileBudget,
                    networkSampleTimeMs,
                    networkRttMs,
                    validationStage,
                    targetKind == EntityKind.ZOMBIE
                            ? HitRoute.PLAYER_TO_ZOMBIE
                            : HitRoute.PLAYER_TO_PLAYER);
        }
    }

    public enum HitRoute {
        PLAYER_TO_PLAYER,
        PLAYER_TO_ZOMBIE,
        ZOMBIE_TO_PLAYER
    }

    public record ZombieStateFacts(
            long zombieId,
            long generation,
            long serverTimeMs,
            double x,
            double y,
            int floor,
            double yawRadians,
            long ownerEpoch,
            boolean registryApplied) {
        public ZombieStateFacts(
                long zombieId,
                long generation,
                long serverTimeMs,
                double x,
                double y,
                int floor,
                double yawRadians,
                long ownerEpoch) {
            this(zombieId, generation, serverTimeMs, x, y, floor, yawRadians, ownerEpoch, false);
        }
    }

    public record ZombieOwnershipFacts(
            long zombieId,
            long generation,
            long currentNativeOwnerId,
            long targetOwnerId,
            long trustedCurrentTimeMs,
            boolean grapple,
            boolean registryApplied,
            boolean changed) {
        public ZombieOwnershipFacts(
                long zombieId,
                long generation,
                long currentNativeOwnerId,
                long targetOwnerId,
                long trustedCurrentTimeMs,
                boolean grapple) {
            this(
                    zombieId,
                    generation,
                    currentNativeOwnerId,
                    targetOwnerId,
                    trustedCurrentTimeMs,
                    grapple,
                    false,
                    currentNativeOwnerId != targetOwnerId);
        }
    }

    public record ZombieKeyframeFacts(int eventOrdinal) {}

    public enum NativeValidationStage {
        VALIDATED_PRE_APPLY,
        NOT_VALIDATED
    }

    private static final class ExpectedFactAbsence extends RuntimeException {}

    private record Attacker(short playerOnlineId, int connectionGeneration) {}
}
