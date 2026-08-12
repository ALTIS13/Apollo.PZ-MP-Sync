package ru.apollot.pzsync.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import ru.apollot.pzsync.attack.AttackBudget;
import ru.apollot.pzsync.hooks.NativeDecisionAdapter;

/** Closed typed extraction for the two successful-exit A4 hooks. */
final class ExactAcceptedPacketAccess implements NativeDecisionAdapter.PacketAccess {
    private static final String MELEE_MODE = "native-melee";
    private static final String RANGED_MODE = "native-ranged";

    private final TraceAccess access;
    private final PlayerIdentityRegistry players;
    private final ServerMonotonicClock clock;

    ExactAcceptedPacketAccess(
            TraceAccess access, PlayerIdentityRegistry players, ServerMonotonicClock clock) {
        this.access = Objects.requireNonNull(access, "access");
        this.players = Objects.requireNonNull(players, "players");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    static boolean isDeclared(RuntimeAdapterSpec spec) {
        return spec.chains().containsKey(RuntimeAdapterSpec.AccessorRole.PLAYER_PREDICTION)
                || spec.chains().containsKey(RuntimeAdapterSpec.AccessorRole.ATTACK_WEAPON)
                || spec.chains().containsKey(RuntimeAdapterSpec.AccessorRole.ATTACK_HIT_COUNT);
    }

    static void requireComplete(RuntimeAdapterSpec spec) {
        if (!isDeclared(spec)) {
            return;
        }
        for (RuntimeAdapterSpec.AccessorRole role : new RuntimeAdapterSpec.AccessorRole[] {
            RuntimeAdapterSpec.AccessorRole.PLAYER_RELATED_PLAYER,
            RuntimeAdapterSpec.AccessorRole.PLAYER_PREDICTION,
            RuntimeAdapterSpec.AccessorRole.ATTACK_PLAYER,
            RuntimeAdapterSpec.AccessorRole.ATTACK_WEAPON,
            RuntimeAdapterSpec.AccessorRole.ATTACK_HIT_COUNT
        }) {
            if (!spec.chains().containsKey(role)) {
                throw new RuntimeAdapterException("runtime-adapter-a4-chain-missing");
            }
        }
        requireHook(
                spec,
                RuntimeAdapterSpec.HookRole.PLAYER_ACCEPTED,
                "zombie.characters.NetworkPlayerAI",
                "parse",
                "(Lzombie/network/packets/character/PlayerPacket;)V");
        requireHook(
                spec,
                RuntimeAdapterSpec.HookRole.ATTACK_ACCEPTED,
                "zombie.network.packets.hit.AttackCollisionCheckPacket",
                "processServer",
                "(Lzombie/network/PacketTypes$PacketType;"
                        + "Lzombie/core/raknet/UdpConnection;)V");
        requireChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.PLAYER_RELATED_PLAYER,
                RuntimeAdapterSpec.Source.THIS,
                step(RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD, 18,
                        "zombie.characters.NetworkPlayerAI", "player",
                        "Lzombie/characters/IsoPlayer;"),
                step(RuntimeAdapterSpec.AccessorKind.VIRTUAL0, 1,
                        "zombie.characters.IsoPlayer", "getOnlineID", "()S"));
        requireChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.PLAYER_PREDICTION,
                RuntimeAdapterSpec.Source.ARG0,
                step(RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD, 17,
                        "zombie.network.packets.character.PlayerPacket", "prediction",
                        "Lzombie/network/fields/character/Prediction;"),
                step(RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD, 1,
                        "zombie.network.fields.character.Prediction", "x", "F"));
        requireChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.ATTACK_PLAYER,
                RuntimeAdapterSpec.Source.THIS,
                step(RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD, 20,
                        "zombie.network.packets.hit.AttackCollisionCheckPacket", "wielder",
                        "Lzombie/network/fields/character/PlayerID;"),
                step(RuntimeAdapterSpec.AccessorKind.VIRTUAL0, 1,
                        "zombie.network.fields.character.PlayerID", "getPlayer",
                        "()Lzombie/characters/IsoPlayer;"));
        requireChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.ATTACK_WEAPON,
                RuntimeAdapterSpec.Source.THIS,
                step(RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD, 20,
                        "zombie.network.packets.hit.AttackCollisionCheckPacket", "weapon",
                        "Lzombie/network/fields/hit/Weapon;"),
                step(RuntimeAdapterSpec.AccessorKind.VIRTUAL0, 1,
                        "zombie.network.fields.hit.Weapon", "getWeapon",
                        "()Lzombie/inventory/types/HandWeapon;"),
                step(RuntimeAdapterSpec.AccessorKind.VIRTUAL0, 1,
                        "zombie.inventory.types.HandWeapon", "getMaxHitCount", "()I"));
        requireChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.ATTACK_HIT_COUNT,
                RuntimeAdapterSpec.Source.THIS,
                step(RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD, 4,
                        "zombie.network.packets.hit.AttackCollisionCheckPacket", "hitCount", "I"));
    }

    private static void requireHook(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.HookRole role,
            String owner,
            String member,
            String descriptor) {
        RuntimeAdapterSpec.Hook hook = spec.hooks().get(role);
        if (hook == null
                || hook.exactAccess() != 1
                || !owner.equals(hook.ownerClass())
                || !member.equals(hook.memberName())
                || !descriptor.equals(hook.descriptor())) {
            throw new RuntimeAdapterException("runtime-adapter-a4-hook-mismatch");
        }
    }

    private static void requireChain(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.AccessorRole role,
            RuntimeAdapterSpec.Source source,
            ExpectedStep... expected) {
        RuntimeAdapterSpec.AccessorChain chain = spec.chains().get(role);
        if (chain == null || chain.source() != source || chain.steps().size() != expected.length) {
            throw new RuntimeAdapterException("runtime-adapter-a4-chain-mismatch");
        }
        for (int index = 0; index < expected.length; index++) {
            RuntimeAdapterSpec.AccessorStep actual = chain.steps().get(index);
            ExpectedStep wanted = expected[index];
            if (actual.index() != index
                    || actual.kind() != wanted.kind()
                    || actual.exactAccess() != wanted.exactAccess()
                    || !actual.ownerClass().equals(wanted.owner())
                    || !actual.memberName().equals(wanted.member())
                    || !actual.descriptor().equals(wanted.descriptor())) {
                throw new RuntimeAdapterException("runtime-adapter-a4-chain-mismatch");
            }
        }
    }

    private static ExpectedStep step(
            RuntimeAdapterSpec.AccessorKind kind,
            int access,
            String owner,
            String member,
            String descriptor) {
        return new ExpectedStep(kind, access, owner, member, descriptor);
    }

    private record ExpectedStep(
            RuntimeAdapterSpec.AccessorKind kind,
            int exactAccess,
            String owner,
            String member,
            String descriptor) {}

    @Override
    public Optional<NativeDecisionAdapter.PlayerFacts> playerState(
            Object receiver, Object[] arguments) {
        if (receiver == null || arguments == null || arguments.length != 1 || arguments[0] == null) {
            return Optional.empty();
        }
        Optional<List<Object>> related = access.trace(
                RuntimeAdapterSpec.AccessorRole.PLAYER_RELATED_PLAYER, receiver, arguments);
        Optional<List<Object>> prediction = access.trace(
                RuntimeAdapterSpec.AccessorRole.PLAYER_PREDICTION, receiver, arguments);
        if (related.isEmpty() || prediction.isEmpty()) {
            return Optional.empty();
        }
        List<Object> playerTrace = related.orElseThrow();
        List<Object> predictionTrace = prediction.orElseThrow();
        if (playerTrace.size() != 2
                || predictionTrace.size() != 2
                || !(playerTrace.get(1) instanceof Number onlineNumber)
                || !(predictionTrace.get(1) instanceof Number xNumber)) {
            return Optional.empty();
        }
        Object player = playerTrace.get(0);
        Object predictionObject = predictionTrace.get(0);
        if (player == null || predictionObject == null) {
            return Optional.empty();
        }
        int onlineId = onlineNumber.intValue();
        double x = xNumber.doubleValue();
        Object yValue = access.field(predictionObject, "y", Float.class);
        Object zValue = access.field(predictionObject, "z", Byte.class);
        Object directionValue = access.field(predictionObject, "direction", Float.class);
        if (!(yValue instanceof Number yNumber)
                || !(zValue instanceof Number zNumber)
                || !(directionValue instanceof Number directionNumber)) {
            return Optional.empty();
        }
        double y = yNumber.doubleValue();
        int floor = zNumber.intValue();
        double direction = directionNumber.doubleValue();
        if (onlineId < 0
                || onlineId > Short.MAX_VALUE
                || !Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(direction)
                || floor < 0
                || floor > Byte.MAX_VALUE
                || x < Integer.MIN_VALUE
                || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE
                || y > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        OptionalLong now = clock.nowMs();
        if (now.isEmpty()) {
            return Optional.empty();
        }
        Optional<PlayerIdentityRegistry.Identity> identity =
                players.observe((short) onlineId, player, now.orElseThrow());
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        PlayerIdentityRegistry.Identity accepted = identity.orElseThrow();
        return Optional.of(new NativeDecisionAdapter.PlayerFacts(
                accepted.onlineId(),
                accepted.connectionGeneration(),
                now.orElseThrow(),
                x,
                y,
                floor,
                direction,
                0));
    }

    @Override
    public Optional<NativeDecisionAdapter.AttackFacts> nativeAttack(
            Object receiver, Object[] arguments) {
        if (receiver == null || arguments == null || arguments.length != 2) {
            return Optional.empty();
        }
        Optional<List<Object>> playerPath = access.trace(
                RuntimeAdapterSpec.AccessorRole.ATTACK_PLAYER, receiver, arguments);
        Optional<List<Object>> weaponPath = access.trace(
                RuntimeAdapterSpec.AccessorRole.ATTACK_WEAPON, receiver, arguments);
        Optional<List<Object>> hitCountPath = access.trace(
                RuntimeAdapterSpec.AccessorRole.ATTACK_HIT_COUNT, receiver, arguments);
        if (playerPath.isEmpty() || weaponPath.isEmpty() || hitCountPath.isEmpty()) {
            return Optional.empty();
        }
        List<Object> playerTrace = playerPath.orElseThrow();
        List<Object> weaponTrace = weaponPath.orElseThrow();
        List<Object> hitTrace = hitCountPath.orElseThrow();
        if (playerTrace.size() != 2
                || weaponTrace.size() != 3
                || hitTrace.size() != 1
                || !(weaponTrace.get(2) instanceof Number weaponMaxNumber)
                || !(hitTrace.get(0) instanceof Number hitCountNumber)) {
            return Optional.empty();
        }
        Object player = playerTrace.get(1);
        Object weapon = weaponTrace.get(1);
        if (player == null || weapon == null) {
            return Optional.empty();
        }
        int packetHits = hitCountNumber.intValue();
        int weaponMax = weaponMaxNumber.intValue();
        if (packetHits <= 0 || weaponMax <= 0) {
            return Optional.empty();
        }
        Optional<PlayerIdentityRegistry.Identity> identity = players.currentByObject(player);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        Object idValue = access.virtual0(weapon, "getID", Integer.class);
        Object rangedValue = access.virtual0(weapon, "isRanged", Boolean.class);
        Object projectileValue = access.virtual0(weapon, "getProjectileCount", Integer.class);
        if (!(idValue instanceof Number idNumber)
                || !(rangedValue instanceof Boolean ranged)
                || !(projectileValue instanceof Number projectileNumber)) {
            return Optional.empty();
        }
        long weaponId = idNumber.longValue();
        int projectileCount = projectileNumber.intValue();
        if (weaponId < 0 || projectileCount < 0) {
            return Optional.empty();
        }
        int nativeMaxHits = Math.min(
                AttackBudget.MAX_TARGETS_PER_ATTACK, Math.min(packetHits, weaponMax));
        int nativeProjectileCount = ranged
                ? Math.min(AttackBudget.MAX_TARGETS_PER_ATTACK, projectileCount)
                : 0;
        if (nativeMaxHits <= 0 || (ranged && nativeProjectileCount <= 0)) {
            return Optional.empty();
        }
        OptionalLong now = clock.nowMs();
        if (now.isEmpty()) {
            return Optional.empty();
        }
        PlayerIdentityRegistry.Identity accepted = identity.orElseThrow();
        return Optional.of(new NativeDecisionAdapter.AttackFacts(
                accepted.onlineId(),
                accepted.connectionGeneration(),
                now.orElseThrow(),
                weaponId,
                ranged ? RANGED_MODE : MELEE_MODE,
                ranged,
                nativeMaxHits,
                nativeProjectileCount));
    }

    @Override
    public Optional<NativeDecisionAdapter.HitFacts> nativeHit(Object receiver, Object[] arguments) {
        return Optional.empty();
    }

    @Override
    public Optional<NativeDecisionAdapter.ZombieStateFacts> zombieState(
            Object receiver, Object[] arguments) {
        return Optional.empty();
    }

    @Override
    public Optional<NativeDecisionAdapter.ZombieOwnershipFacts> zombieOwnership(
            Object receiver, Object[] arguments) {
        return Optional.empty();
    }

    @Override
    public Optional<NativeDecisionAdapter.ZombieKeyframeFacts> zombieKeyframe(
            Object receiver, Object[] arguments) {
        return Optional.empty();
    }

    interface TraceAccess {
        Optional<List<Object>> trace(
                RuntimeAdapterSpec.AccessorRole role, Object receiver, Object[] arguments);

        Object field(Object receiver, String name, Class<?> expectedType);

        Object virtual0(Object receiver, String name, Class<?> expectedType);
    }
}
