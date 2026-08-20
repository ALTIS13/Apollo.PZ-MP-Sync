package ru.apollot.pzsync.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import ru.apollot.pzsync.attack.AttackBudget;
import ru.apollot.pzsync.history.EntityKind;
import ru.apollot.pzsync.hooks.NativeDecisionAdapter;

/** Exact, read-only extraction for the three allowlisted 42.20.3 hit leaves. */
final class ExactHitPacketAccess implements NativeDecisionAdapter.PacketAccess {
    private static final String MELEE_MODE = "native-melee";
    private static final String RANGED_MODE = "native-ranged";
    private static final String ZOMBIE_MODE = "native-zombie";
    private static final double ZOMBIE_RANGE_TILES = 2.0;
    private static final double ZOMBIE_HALF_ANGLE_DEGREES = 100.0;

    private final NativeDecisionAdapter.PacketAccess accepted;
    private final RuntimeAccessorChains access;
    private final PlayerIdentityRegistry players;
    private final ZombieIdentityRegistry zombies;
    private final ServerMonotonicClock clock;

    static boolean isDeclared(RuntimeAdapterSpec spec) {
        return !spec.supports().isEmpty()
                || spec.chains().containsKey(RuntimeAdapterSpec.AccessorRole.HIT_PVP_ATTACKER);
    }

    static void requireComplete(RuntimeAdapterSpec spec) {
        if (!isDeclared(spec)) return;
        RuntimeAdapterSpec.Hook hook = spec.hooks().get(RuntimeAdapterSpec.HookRole.HIT_PRE_APPLY);
        if (hook == null || hook.exactAccess() != 1
                || !"zombie.network.packets.hit.HitCharacter".equals(hook.ownerClass())
                || !"processServer".equals(hook.memberName())
                || !("(Lzombie/network/PacketTypes$PacketType;"
                        + "Lzombie/core/raknet/UdpConnection;)V").equals(hook.descriptor())) {
            throw new RuntimeAdapterException("runtime-adapter-a5-hook-mismatch");
        }
        requireVariant(spec, RuntimeAdapterSpec.HitVariant.PLAYER_HIT_PLAYER,
                "zombie.network.packets.hit.PlayerHitPlayerPacket",
                "zombie.network.packets.hit.PlayerHit");
        requireVariant(spec, RuntimeAdapterSpec.HitVariant.PLAYER_HIT_ZOMBIE,
                "zombie.network.packets.hit.PlayerHitZombiePacket",
                "zombie.network.packets.hit.PlayerHit");
        requireVariant(spec, RuntimeAdapterSpec.HitVariant.ZOMBIE_HIT_PLAYER,
                "zombie.network.packets.hit.ZombieHitPlayerPacket",
                "zombie.network.packets.hit.ZombieHit");
        for (RuntimeAdapterSpec.SupportRole role : RuntimeAdapterSpec.SupportRole.values()) {
            if (!spec.supports().containsKey(role)) {
                throw new RuntimeAdapterException("runtime-adapter-a5-support-missing");
            }
        }
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.HIT_PVP_ATTACKER,
                field("zombie.network.packets.hit.PlayerHit", "wielder",
                        "Lzombie/network/fields/hit/Player;", 0x14), player(), playerId());
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.HIT_PVP_TARGET,
                field("zombie.network.packets.hit.PlayerHitPlayerPacket", "target",
                        "Lzombie/network/fields/hit/Player;", 0x11), player(), playerId());
        requireWeaponChain(spec, RuntimeAdapterSpec.AccessorRole.HIT_PVP_WEAPON);
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.HIT_PVZ_ATTACKER,
                field("zombie.network.packets.hit.PlayerHit", "wielder",
                        "Lzombie/network/fields/hit/Player;", 0x14), player(), playerId());
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.HIT_PVZ_TARGET,
                field("zombie.network.packets.hit.PlayerHitZombiePacket", "target",
                        "Lzombie/network/fields/hit/Zombie;", 0x14), zombie(), zombieId());
        requireWeaponChain(spec, RuntimeAdapterSpec.AccessorRole.HIT_PVZ_WEAPON);
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.HIT_ZVP_ATTACKER,
                field("zombie.network.packets.hit.ZombieHit", "wielder",
                        "Lzombie/network/fields/hit/Zombie;", 0x14), zombie(), zombieId());
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.HIT_ZVP_TARGET,
                field("zombie.network.packets.hit.ZombieHitPlayerPacket", "target",
                        "Lzombie/network/fields/hit/Player;", 0x11), player(), playerId());
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.WORLD_SERVER_MAP,
                staticField("zombie.network.ServerMap", "instance",
                        "Lzombie/network/ServerMap;", 0x9));
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.WORLD_LOS_CLEAR,
                staticField("zombie.iso.LosUtil$TestResults", "Clear",
                        "Lzombie/iso/LosUtil$TestResults;", 0x19));
        requireChain(spec, RuntimeAdapterSpec.AccessorRole.WORLD_LOS_OPEN_DOOR,
                staticField("zombie.iso.LosUtil$TestResults", "ClearThroughOpenDoor",
                        "Lzombie/iso/LosUtil$TestResults;", 0x19));
    }

    private static void requireVariant(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.HitVariant role,
            String leaf,
            String parent) {
        RuntimeAdapterSpec.Variant variant = spec.variants().get(role);
        if (variant == null || !leaf.equals(variant.ownerClass())
                || !variant.hierarchyProof().equals(List.of(
                        parent, "zombie.network.packets.hit.HitCharacter"))) {
            throw new RuntimeAdapterException("runtime-adapter-a5-variant-mismatch");
        }
    }

    private static void requireWeaponChain(
            RuntimeAdapterSpec spec, RuntimeAdapterSpec.AccessorRole role) {
        requireChain(spec, role,
                field("zombie.network.packets.hit.PlayerHit", "weapon",
                        "Lzombie/network/fields/hit/Weapon;", 0x14),
                virtual("zombie.network.fields.hit.Weapon", "getWeapon",
                        "()Lzombie/inventory/types/HandWeapon;"),
                virtual("zombie.inventory.types.HandWeapon", "getMaxHitCount", "()I"));
    }

    private static void requireChain(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.AccessorRole role,
            ExpectedStep... expected) {
        RuntimeAdapterSpec.AccessorChain chain = spec.chains().get(role);
        RuntimeAdapterSpec.Source source = role.name().startsWith("WORLD_")
                ? RuntimeAdapterSpec.Source.STATIC : RuntimeAdapterSpec.Source.THIS;
        if (chain == null || chain.source() != source || chain.steps().size() != expected.length) {
            throw new RuntimeAdapterException("runtime-adapter-a5-chain-mismatch");
        }
        for (int index = 0; index < expected.length; index++) {
            RuntimeAdapterSpec.AccessorStep actual = chain.steps().get(index);
            ExpectedStep wanted = expected[index];
            if (actual.index() != index || actual.kind() != wanted.kind()
                    || actual.exactAccess() != wanted.access()
                    || !actual.ownerClass().equals(wanted.owner())
                    || !actual.memberName().equals(wanted.member())
                    || !actual.descriptor().equals(wanted.descriptor())) {
                throw new RuntimeAdapterException("runtime-adapter-a5-chain-mismatch");
            }
        }
    }

    private static ExpectedStep field(String owner, String member, String descriptor, int access) {
        return new ExpectedStep(RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD,
                access, owner, member, descriptor);
    }
    private static ExpectedStep staticField(
            String owner, String member, String descriptor, int access) {
        return new ExpectedStep(RuntimeAdapterSpec.AccessorKind.STATIC_FIELD,
                access, owner, member, descriptor);
    }
    private static ExpectedStep virtual(String owner, String member, String descriptor) {
        return new ExpectedStep(RuntimeAdapterSpec.AccessorKind.VIRTUAL0,
                1, owner, member, descriptor);
    }
    private static ExpectedStep player() { return virtual(
            "zombie.network.fields.hit.Player", "getPlayer",
            "()Lzombie/characters/IsoPlayer;"); }
    private static ExpectedStep zombie() { return virtual(
            "zombie.network.fields.hit.Zombie", "getZombie",
            "()Lzombie/characters/IsoZombie;"); }
    private static ExpectedStep playerId() { return virtual(
            "zombie.characters.IsoPlayer", "getOnlineID", "()S"); }
    private static ExpectedStep zombieId() { return virtual(
            "zombie.characters.IsoZombie", "getOnlineID", "()S"); }

    private record ExpectedStep(
            RuntimeAdapterSpec.AccessorKind kind,
            int access,
            String owner,
            String member,
            String descriptor) {}

    ExactHitPacketAccess(
            RuntimeAccessorChains access,
            PlayerIdentityRegistry players,
            ZombieIdentityRegistry zombies,
            ServerMonotonicClock clock) {
        this(null, access, players, zombies, clock);
    }

    ExactHitPacketAccess(
            NativeDecisionAdapter.PacketAccess accepted,
            RuntimeAccessorChains access,
            PlayerIdentityRegistry players,
            ZombieIdentityRegistry zombies,
            ServerMonotonicClock clock) {
        this.accepted = accepted;
        this.access = Objects.requireNonNull(access, "access");
        this.players = Objects.requireNonNull(players, "players");
        this.zombies = Objects.requireNonNull(zombies, "zombies");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean knownHitVariant(Object receiver) {
        return variant(receiver) != null;
    }

    @Override
    public Optional<NativeDecisionAdapter.HitFacts> nativeHit(
            Object receiver, Object[] arguments) {
        RuntimeAdapterSpec.HitVariant variant = variant(receiver);
        if (variant == null) return Optional.empty();
        if (arguments == null || arguments.length != 2 || arguments[0] == null
                || arguments[1] == null) {
            return Optional.empty();
        }
        OptionalLong now = clock.nowMs();
        if (now.isEmpty()) return Optional.empty();
        Network network = network(receiver, arguments, now.orElseThrow());
        if (network == null) return Optional.empty();
        return switch (variant) {
            case PLAYER_HIT_PLAYER -> playerHit(
                    NativeDecisionAdapter.HitRoute.PLAYER_TO_PLAYER,
                    EntityKind.PLAYER,
                    RuntimeAdapterSpec.AccessorRole.HIT_PVP_ATTACKER,
                    RuntimeAdapterSpec.AccessorRole.HIT_PVP_TARGET,
                    RuntimeAdapterSpec.AccessorRole.HIT_PVP_WEAPON,
                    receiver,
                    arguments,
                    now.orElseThrow(),
                    network);
            case PLAYER_HIT_ZOMBIE -> playerHit(
                    NativeDecisionAdapter.HitRoute.PLAYER_TO_ZOMBIE,
                    EntityKind.ZOMBIE,
                    RuntimeAdapterSpec.AccessorRole.HIT_PVZ_ATTACKER,
                    RuntimeAdapterSpec.AccessorRole.HIT_PVZ_TARGET,
                    RuntimeAdapterSpec.AccessorRole.HIT_PVZ_WEAPON,
                    receiver,
                    arguments,
                    now.orElseThrow(),
                    network);
            case ZOMBIE_HIT_PLAYER -> zombieHitPlayer(
                    receiver, arguments, now.orElseThrow(), network);
        };
    }

    private Optional<NativeDecisionAdapter.HitFacts> playerHit(
            NativeDecisionAdapter.HitRoute route,
            EntityKind targetKind,
            RuntimeAdapterSpec.AccessorRole attackerRole,
            RuntimeAdapterSpec.AccessorRole targetRole,
            RuntimeAdapterSpec.AccessorRole weaponRole,
            Object receiver,
            Object[] arguments,
            long nowMs,
            Network network) {
        Optional<List<Object>> attackerTrace = access.traceVariant(attackerRole, receiver, arguments);
        Optional<List<Object>> targetTrace = access.traceVariant(targetRole, receiver, arguments);
        Optional<List<Object>> weaponTrace = access.traceVariant(weaponRole, receiver, arguments);
        if (attackerTrace.isEmpty() || targetTrace.isEmpty() || weaponTrace.isEmpty()) {
            return Optional.empty();
        }
        List<Object> attackerPath = attackerTrace.orElseThrow();
        List<Object> targetPath = targetTrace.orElseThrow();
        List<Object> weaponPath = weaponTrace.orElseThrow();
        if (attackerPath.size() != 3 || targetPath.size() != 3 || weaponPath.size() != 3
                || !(attackerPath.get(2) instanceof Number attackerId)
                || !(targetPath.get(2) instanceof Number targetId)
                || !(weaponPath.get(2) instanceof Number maxHitCount)) {
            return Optional.empty();
        }
        Object attacker = attackerPath.get(1);
        Object target = targetPath.get(1);
        Object weapon = weaponPath.get(1);
        Optional<PlayerIdentityRegistry.Identity> attackerIdentity = players.currentByObject(attacker);
        if (attackerIdentity.isEmpty()
                || attackerIdentity.orElseThrow().onlineId() != attackerId.shortValue()) {
            return Optional.empty();
        }
        Identity targetIdentity = targetKind == EntityKind.PLAYER
                ? playerIdentity(target, targetId)
                : zombieIdentity(target, targetId, nowMs);
        Pose attackerPose = pose(attacker, attackerIdentity.orElseThrow().onlineId(),
                attackerIdentity.orElseThrow().connectionGeneration(), 0, -1, true, nowMs);
        Pose targetPose = targetIdentity == null ? null
                : pose(target, targetIdentity.id(), targetIdentity.generation(),
                        targetIdentity.ownerEpoch(), targetIdentity.ownerEpochStartedAtMs(),
                        targetIdentity.historyEligible(), nowMs);
        Weapon weaponFacts = weapon(weapon, maxHitCount.intValue(), attacker);
        if (attackerPose == null || targetPose == null || weaponFacts == null) {
            return Optional.empty();
        }
        return Optional.of(new NativeDecisionAdapter.HitFacts(
                attackerIdentity.orElseThrow().onlineId(),
                attackerIdentity.orElseThrow().connectionGeneration(),
                nowMs,
                targetKind,
                attackerPose.facts(),
                targetPose.facts(),
                weaponFacts.id(),
                weaponFacts.ranged() ? RANGED_MODE : MELEE_MODE,
                weaponFacts.ranged(),
                weaponFacts.range(),
                weaponFacts.halfAngleDegrees(),
                weaponFacts.equipped(),
                !weaponFacts.ranged() || weaponFacts.projectileBudget() > 0,
                weaponFacts.projectileBudget(),
                network.sampleTimeMs(),
                network.rttMs(),
                NativeDecisionAdapter.NativeValidationStage.VALIDATED_PRE_APPLY,
                route));
    }

    private Optional<NativeDecisionAdapter.HitFacts> zombieHitPlayer(
            Object receiver, Object[] arguments, long nowMs, Network network) {
        Optional<List<Object>> attackerTrace = access.traceVariant(
                RuntimeAdapterSpec.AccessorRole.HIT_ZVP_ATTACKER, receiver, arguments);
        Optional<List<Object>> targetTrace = access.traceVariant(
                RuntimeAdapterSpec.AccessorRole.HIT_ZVP_TARGET, receiver, arguments);
        if (attackerTrace.isEmpty() || targetTrace.isEmpty()) return Optional.empty();
        List<Object> attackerPath = attackerTrace.orElseThrow();
        List<Object> targetPath = targetTrace.orElseThrow();
        if (attackerPath.size() != 3 || targetPath.size() != 3
                || !(attackerPath.get(2) instanceof Number attackerId)
                || !(targetPath.get(2) instanceof Number targetId)) {
            return Optional.empty();
        }
        Object attacker = attackerPath.get(1);
        Object target = targetPath.get(1);
        Identity zombieIdentity = zombieIdentity(attacker, attackerId, nowMs);
        Identity playerIdentity = playerIdentity(target, targetId);
        if (zombieIdentity == null || playerIdentity == null) return Optional.empty();
        Pose attackerPose = pose(attacker, zombieIdentity.id(), zombieIdentity.generation(),
                zombieIdentity.ownerEpoch(), zombieIdentity.ownerEpochStartedAtMs(),
                zombieIdentity.historyEligible(), nowMs);
        Pose targetPose = pose(
                target, playerIdentity.id(), playerIdentity.generation(), 0, -1, true, nowMs);
        if (attackerPose == null || targetPose == null) return Optional.empty();
        return Optional.of(new NativeDecisionAdapter.HitFacts(
                0,
                0,
                nowMs,
                EntityKind.PLAYER,
                attackerPose.facts(),
                targetPose.facts(),
                zombieIdentity.id(),
                ZOMBIE_MODE,
                false,
                ZOMBIE_RANGE_TILES,
                ZOMBIE_HALF_ANGLE_DEGREES,
                true,
                true,
                0,
                network.sampleTimeMs(),
                network.rttMs(),
                NativeDecisionAdapter.NativeValidationStage.VALIDATED_PRE_APPLY,
                NativeDecisionAdapter.HitRoute.ZOMBIE_TO_PLAYER));
    }

    private RuntimeAdapterSpec.HitVariant variant(Object receiver) {
        for (RuntimeAdapterSpec.HitVariant variant : RuntimeAdapterSpec.HitVariant.values()) {
            if (access.exactVariant(variant, receiver)) return variant;
        }
        return null;
    }

    private Network network(Object receiver, Object[] arguments, long nowMs) {
        Optional<List<Object>> path = access.trace(
                RuntimeAdapterSpec.AccessorRole.CONNECTION_AVERAGE_PING, receiver, arguments);
        if (path.isEmpty() || path.orElseThrow().size() != 1
                || !(path.orElseThrow().getFirst() instanceof Number number)) {
            return null;
        }
        double rtt = number.doubleValue();
        if (!Double.isFinite(rtt)) return null;
        return new Network(nowMs, rtt > 0.0 ? rtt : 0.0);
    }

    private Identity playerIdentity(Object player, Number id) {
        Optional<PlayerIdentityRegistry.Identity> identity = players.currentByObject(player);
        if (identity.isEmpty() || identity.orElseThrow().onlineId() != id.shortValue()) return null;
        return new Identity(
                identity.orElseThrow().onlineId(),
                identity.orElseThrow().connectionGeneration(),
                0,
                -1,
                true);
    }

    private Identity zombieIdentity(Object zombie, Number id, long nowMs) {
        long numericId = id.longValue();
        if (numericId < 0 || numericId > Short.MAX_VALUE) return null;
        Optional<ZombieIdentityRegistry.Identity> identity = zombies.current(numericId, zombie);
        if (identity.isEmpty()) {
            identity = zombies.observeUnknownOwner(numericId, zombie, nowMs);
        }
        if (identity.isEmpty()) return null;
        ZombieIdentityRegistry.Identity value = identity.orElseThrow();
        return new Identity(
                value.zombieId(),
                value.generation(),
                value.ownerEpoch(),
                value.ownerEpochStartedAtMs(),
                zombies.historyEligible(value, nowMs));
    }

    private Pose pose(
            Object entity,
            long id,
            long generation,
            long ownerEpoch,
            long ownerEpochStartedAtMs,
            boolean historyEligible,
            long nowMs) {
        Object x = access.support(RuntimeAdapterSpec.SupportRole.MOVING_X, entity);
        Object y = access.support(RuntimeAdapterSpec.SupportRole.MOVING_Y, entity);
        Object z = access.support(RuntimeAdapterSpec.SupportRole.MOVING_Z, entity);
        Object forward = access.support(RuntimeAdapterSpec.SupportRole.CHARACTER_FORWARD, entity);
        Object alive = access.support(RuntimeAdapterSpec.SupportRole.CHARACTER_ALIVE, entity);
        if (!(x instanceof Number xNumber) || !(y instanceof Number yNumber)
                || !(z instanceof Number zNumber) || forward == null
                || !(alive instanceof Boolean aliveValue) || !aliveValue) {
            return null;
        }
        Object directionX = access.support(RuntimeAdapterSpec.SupportRole.VECTOR_X, forward);
        Object directionY = access.support(RuntimeAdapterSpec.SupportRole.VECTOR_Y, forward);
        if (!(directionX instanceof Number dx) || !(directionY instanceof Number dy)) return null;
        double px = xNumber.doubleValue();
        double py = yNumber.doubleValue();
        double pz = zNumber.doubleValue();
        double vx = dx.doubleValue();
        double vy = dy.doubleValue();
        if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)
                || !Double.isFinite(vx) || !Double.isFinite(vy)
                || Math.hypot(vx, vy) < Double.MIN_NORMAL
                || pz < Integer.MIN_VALUE || pz > Integer.MAX_VALUE) {
            return null;
        }
        return new Pose(new NativeDecisionAdapter.PoseFacts(
                id, generation, nowMs, px, py, (int) Math.floor(pz), Math.atan2(vy, vx),
                ownerEpoch, true, ownerEpochStartedAtMs, historyEligible));
    }

    private Weapon weapon(Object weapon, int maxHitCount, Object wielder) {
        Object id = access.support(RuntimeAdapterSpec.SupportRole.WEAPON_ID, weapon);
        Object ranged = access.support(RuntimeAdapterSpec.SupportRole.WEAPON_RANGED, weapon);
        Object projectiles = access.support(RuntimeAdapterSpec.SupportRole.WEAPON_PROJECTILES, weapon);
        Object range = access.support(RuntimeAdapterSpec.SupportRole.WEAPON_MAX_RANGE, weapon);
        Object minAngle = access.support(RuntimeAdapterSpec.SupportRole.WEAPON_MIN_ANGLE, weapon);
        Object primary = access.support(RuntimeAdapterSpec.SupportRole.CHARACTER_PRIMARY_HAND, wielder);
        Object secondary = access.support(RuntimeAdapterSpec.SupportRole.CHARACTER_SECONDARY_HAND, wielder);
        if (!(id instanceof Number idNumber) || !(ranged instanceof Boolean rangedValue)
                || !(projectiles instanceof Number projectileNumber)
                || !(range instanceof Number rangeNumber)
                || !(minAngle instanceof Number minAngleNumber)) {
            return null;
        }
        int budget = rangedValue
                ? Math.min(AttackBudget.MAX_TARGETS_PER_ATTACK,
                        Math.min(maxHitCount, projectileNumber.intValue()))
                : 0;
        double cosine = minAngleNumber.doubleValue();
        double halfAngle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosine))));
        double serverRange = rangeNumber.doubleValue();
        long weaponId = idNumber.longValue();
        if (weaponId < 0 || maxHitCount <= 0 || !Double.isFinite(serverRange)
                || serverRange <= 0.0 || !Double.isFinite(cosine)
                || (rangedValue && budget <= 0)) {
            return null;
        }
        return new Weapon(
                weaponId, rangedValue, serverRange, halfAngle,
                primary == weapon || secondary == weapon, budget);
    }

    @Override
    public Optional<NativeDecisionAdapter.PlayerFacts> playerState(
            Object receiver, Object[] arguments) {
        return accepted == null ? Optional.empty() : accepted.playerState(receiver, arguments);
    }

    @Override
    public Optional<NativeDecisionAdapter.AttackFacts> nativeAttack(
            Object receiver, Object[] arguments) {
        return accepted == null ? Optional.empty() : accepted.nativeAttack(receiver, arguments);
    }

    @Override public Optional<NativeDecisionAdapter.ZombieStateFacts> zombieState(Object receiver, Object[] arguments) { return Optional.empty(); }
    @Override public Optional<NativeDecisionAdapter.ZombieOwnershipFacts> zombieOwnership(Object receiver, Object[] arguments) { return Optional.empty(); }
    @Override public Optional<NativeDecisionAdapter.ZombieKeyframeFacts> zombieKeyframe(Object receiver, Object[] arguments) { return Optional.empty(); }

    private record Identity(
            long id,
            long generation,
            long ownerEpoch,
            long ownerEpochStartedAtMs,
            boolean historyEligible) {}
    private record Pose(NativeDecisionAdapter.PoseFacts facts) {}
    private record Weapon(long id, boolean ranged, double range, double halfAngleDegrees,
            boolean equipped, int projectileBudget) {}
    private record Network(long sampleTimeMs, double rttMs) {}
}
