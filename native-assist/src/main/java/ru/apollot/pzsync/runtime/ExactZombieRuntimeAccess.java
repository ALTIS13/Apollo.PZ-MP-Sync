package ru.apollot.pzsync.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import ru.apollot.pzsync.history.HistoryStore;
import ru.apollot.pzsync.history.StateSample;
import ru.apollot.pzsync.hooks.NativeDecisionAdapter;

/** Exact enter-copy/exit-commit lifecycle for accepted 42.20.2 zombie state. */
final class ExactZombieRuntimeAccess implements NativeDecisionAdapter.PacketAccess {
    private static final RuntimeAdapterSpec.AccessorRole[] PACKET_ROLES = {
        RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET,
        RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_REAL_X,
        RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_REAL_Y,
        RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_REAL_Z,
        RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_DIRECTION,
        RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_HEALTH,
        RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_TARGET,
        RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_STATE
    };

    private final NativeDecisionAdapter.PacketAccess delegate;
    private final RuntimeAccessorChains access;
    private final HistoryStore history;
    private final ZombieIdentityRegistry zombies;
    private final ServerMonotonicClock clock;
    private final ClassLoader targetLoader;
    private volatile Class<?> packerOwner;
    private volatile Class<?> managerOwner;

    ExactZombieRuntimeAccess(
            NativeDecisionAdapter.PacketAccess delegate,
            RuntimeAccessorChains access,
            HistoryStore history,
            ZombieIdentityRegistry zombies,
            ServerMonotonicClock clock,
            ClassLoader targetLoader) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.access = Objects.requireNonNull(access, "access");
        this.history = Objects.requireNonNull(history, "history");
        this.zombies = Objects.requireNonNull(zombies, "zombies");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.targetLoader = Objects.requireNonNull(targetLoader, "targetLoader");
    }

    synchronized void verifyDefinitions() {
        try {
            packerOwner = Class.forName(
                    "zombie.popman.NetworkZombiePacker", false, targetLoader);
            managerOwner = Class.forName(
                    "zombie.popman.NetworkZombieManager", false, targetLoader);
        } catch (ClassNotFoundException | LinkageError error) {
            throw new RuntimeAdapterException("runtime-adapter-a6-hook-owner-mismatch");
        }
    }

    static boolean isDeclared(RuntimeAdapterSpec spec) {
        return spec.chains().containsKey(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_REAL_X);
    }

    static void requireComplete(RuntimeAdapterSpec spec) {
        if (!isDeclared(spec)) return;
        requireHook(
                spec,
                RuntimeAdapterSpec.HookRole.ZOMBIE_APPLY,
                0x0002,
                "zombie.popman.NetworkZombiePacker",
                "applyZombie",
                "(Lzombie/characters/IsoZombie;)V");
        requireHook(
                spec,
                RuntimeAdapterSpec.HookRole.ZOMBIE_OWNER,
                0x0001,
                "zombie.popman.NetworkZombieManager",
                "moveZombie",
                "(Lzombie/characters/IsoZombie;Lzombie/core/raknet/UdpConnection;"
                        + "Lzombie/characters/IsoPlayer;)V");
        requireHook(
                spec,
                RuntimeAdapterSpec.HookRole.LUA_INIT,
                0x0009,
                "zombie.Lua.LuaManager",
                "init",
                "()V");
        String[] fields = {"id", "realX", "realY", "realZ", "dirAngleRads", "health",
                "target", "realState"};
        String[] descriptors = {"S", "F", "F", "B", "F", "S", "S",
                "Lzombie/network/NetworkVariables$ZombieState;"};
        for (int index = 0; index < PACKET_ROLES.length; index++) {
            requirePacketChain(spec, PACKET_ROLES[index], fields[index], descriptors[index]);
        }
        requireSingleChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.ZOMBIE_ENTITY,
                RuntimeAdapterSpec.Source.ARG0,
                "zombie.characters.IsoZombie",
                "getOnlineID",
                "()S");
        requireSingleChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.ZOMBIE_CURRENT_OWNER,
                RuntimeAdapterSpec.Source.ARG0,
                "zombie.characters.IsoZombie",
                "getOwner",
                "()Lzombie/core/raknet/UdpConnection;");
        requireSingleChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.OWNER_ZOMBIE,
                RuntimeAdapterSpec.Source.ARG0,
                "zombie.characters.IsoZombie",
                "getOnlineID",
                "()S");
        requireSingleChain(
                spec,
                RuntimeAdapterSpec.AccessorRole.OWNER_CONNECTION,
                RuntimeAdapterSpec.Source.ARG0,
                "zombie.characters.IsoZombie",
                "getOwner",
                "()Lzombie/core/raknet/UdpConnection;");
    }

    private static void requireHook(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.HookRole role,
            int access,
            String owner,
            String member,
            String descriptor) {
        RuntimeAdapterSpec.Hook hook = spec.hooks().get(role);
        if (hook == null || hook.exactAccess() != access
                || !owner.equals(hook.ownerClass()) || !member.equals(hook.memberName())
                || !descriptor.equals(hook.descriptor())) {
            throw new RuntimeAdapterException("runtime-adapter-a6-hook-mismatch");
        }
    }

    private static void requirePacketChain(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.AccessorRole role,
            String field,
            String descriptor) {
        RuntimeAdapterSpec.AccessorChain chain = spec.chains().get(role);
        if (chain == null || chain.source() != RuntimeAdapterSpec.Source.THIS
                || chain.steps().size() != 2
                || !step(chain.steps().get(0), 0, RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD,
                        0x0012, "zombie.popman.NetworkZombiePacker", "packet",
                        "Lzombie/network/packets/character/ZombiePacket;")
                || !step(chain.steps().get(1), 1, RuntimeAdapterSpec.AccessorKind.INSTANCE_FIELD,
                        0x0001, "zombie.network.packets.character.ZombiePacket", field,
                        descriptor)) {
            throw new RuntimeAdapterException("runtime-adapter-a6-packet-chain-mismatch");
        }
    }

    private static void requireSingleChain(
            RuntimeAdapterSpec spec,
            RuntimeAdapterSpec.AccessorRole role,
            RuntimeAdapterSpec.Source source,
            String owner,
            String member,
            String descriptor) {
        RuntimeAdapterSpec.AccessorChain chain = spec.chains().get(role);
        if (chain == null || chain.source() != source || chain.steps().size() != 1
                || !step(chain.steps().getFirst(), 0, RuntimeAdapterSpec.AccessorKind.VIRTUAL0,
                        0x0001, owner, member, descriptor)) {
            throw new RuntimeAdapterException("runtime-adapter-a6-identity-chain-mismatch");
        }
    }

    private static boolean step(
            RuntimeAdapterSpec.AccessorStep step,
            int index,
            RuntimeAdapterSpec.AccessorKind kind,
            int access,
            String owner,
            String member,
            String descriptor) {
        return step.index() == index && step.kind() == kind && step.exactAccess() == access
                && owner.equals(step.ownerClass()) && member.equals(step.memberName())
                && descriptor.equals(step.descriptor());
    }

    @Override
    public ZombieStateCapture captureZombieState(Object receiver, Object[] arguments) {
        if (receiver == null || receiver.getClass() != packerOwner
                || arguments == null || arguments.length != 1
                || arguments[0] == null) return null;
        Object zombie = arguments[0];
        Number entityId = numberTerminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_ENTITY,
                receiver, arguments);
        Number packetId = numberTerminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET,
                receiver, arguments);
        Number x = numberTerminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_REAL_X,
                receiver, arguments);
        Number y = numberTerminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_REAL_Y,
                receiver, arguments);
        Number z = numberTerminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_REAL_Z,
                receiver, arguments);
        Number direction = numberTerminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_DIRECTION,
                receiver, arguments);
        Number health = numberTerminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_HEALTH,
                receiver, arguments);
        Number target = numberTerminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_TARGET,
                receiver, arguments);
        Object state = terminal(RuntimeAdapterSpec.AccessorRole.ZOMBIE_PACKET_STATE,
                receiver, arguments);
        RuntimeAccessorChains.NullableValue owner = access.traceNullableTerminal(
                RuntimeAdapterSpec.AccessorRole.ZOMBIE_CURRENT_OWNER, receiver, arguments);
        if (entityId == null || packetId == null || x == null || y == null || z == null
                || direction == null || health == null || target == null || state == null
                || !owner.available()) return null;
        RawZombieSnapshot raw = new RawZombieSnapshot(
                packetId.shortValue(), x.floatValue(), y.floatValue(), z.byteValue(),
                direction.floatValue(), health.shortValue(), target.shortValue(), state);
        if (entityId.shortValue() != raw.id() || !valid(raw)) return null;
        return new ZombieStateCapture(zombie, owner.value(), raw);
    }

    @Override
    public Optional<NativeDecisionAdapter.ZombieStateFacts> completeZombieState(Object capture) {
        if (!(capture instanceof ZombieStateCapture state)) return Optional.empty();
        OptionalLong now = clock.nowMs();
        if (now.isEmpty()) return Optional.empty();
        RawZombieSnapshot raw = state.raw();
        Optional<ZombieIdentityRegistry.Identity> selected = zombies.observe(
                raw.id(), state.zombieIdentity(), state.ownerIdentity(), now.orElseThrow());
        if (selected.isEmpty()) return Optional.empty();
        ZombieIdentityRegistry.Identity identity = selected.orElseThrow();
        var sample = new StateSample(now.orElseThrow(), raw.realX(), raw.realY(), raw.realZ(),
                raw.directionRadians(), identity.ownerEpoch());
        if (!zombies.commitHistorySample(identity, sample)) return Optional.empty();
        return Optional.of(new NativeDecisionAdapter.ZombieStateFacts(
                identity.zombieId(), identity.generation(), now.orElseThrow(), raw.realX(),
                raw.realY(), raw.realZ(), raw.directionRadians(), identity.ownerEpoch(), true));
    }

    @Override
    public ZombieOwnerCapture captureZombieOwnership(Object receiver, Object[] arguments) {
        if (receiver == null || receiver.getClass() != managerOwner
                || arguments == null || arguments.length != 3
                || arguments[0] == null) return null;
        Number id = numberTerminal(RuntimeAdapterSpec.AccessorRole.OWNER_ZOMBIE,
                receiver, arguments);
        RuntimeAccessorChains.NullableValue owner = access.traceNullableTerminal(
                RuntimeAdapterSpec.AccessorRole.OWNER_CONNECTION, receiver, arguments);
        if (id == null || id.longValue() < 0 || !owner.available()) return null;
        return new ZombieOwnerCapture(arguments[0], id.longValue(), owner.value(),
                receiver, arguments.clone());
    }

    @Override
    public Optional<NativeDecisionAdapter.ZombieOwnershipFacts> completeZombieOwnership(
            Object capture) {
        if (!(capture instanceof ZombieOwnerCapture ownerCapture)) return Optional.empty();
        RuntimeAccessorChains.NullableValue current = access.traceNullableTerminal(
                RuntimeAdapterSpec.AccessorRole.OWNER_CONNECTION,
                ownerCapture.receiver(), ownerCapture.arguments());
        if (!current.available()) return Optional.empty();
        OptionalLong now = clock.nowMs();
        if (now.isEmpty()) return Optional.empty();
        Optional<ZombieIdentityRegistry.Identity> currentIdentity = zombies.current(
                ownerCapture.zombieId(), ownerCapture.zombieIdentity());
        if (currentIdentity.isEmpty()) {
            if (ownerCapture.ownerBefore() == current.value()) return Optional.empty();
            currentIdentity = zombies.observe(
                    ownerCapture.zombieId(), ownerCapture.zombieIdentity(),
                    ownerCapture.ownerBefore(), now.orElseThrow());
            if (currentIdentity.isEmpty()) return Optional.empty();
        }
        Optional<ZombieIdentityRegistry.OwnerOutcome> outcome = zombies.observeSuccessfulOwner(
                ownerCapture.zombieId(), ownerCapture.zombieIdentity(),
                ownerCapture.ownerBefore(), current.value(), now.orElseThrow());
        if (outcome.isEmpty()) return Optional.empty();
        ZombieIdentityRegistry.OwnerOutcome accepted = outcome.orElseThrow();
        long targetOwnerId = accepted.changed() ? 1 : 0;
        return Optional.of(new NativeDecisionAdapter.ZombieOwnershipFacts(
                accepted.identity().zombieId(), accepted.identity().generation(), 0, targetOwnerId,
                now.orElseThrow(), false, true, accepted.changed()));
    }

    private Number numberTerminal(
            RuntimeAdapterSpec.AccessorRole role, Object receiver, Object[] arguments) {
        Object value = terminal(role, receiver, arguments);
        return value instanceof Number number ? number : null;
    }

    private Object terminal(
            RuntimeAdapterSpec.AccessorRole role, Object receiver, Object[] arguments) {
        Optional<List<Object>> trace = access.trace(role, receiver, arguments);
        return trace.isEmpty() || trace.orElseThrow().isEmpty()
                ? null
                : trace.orElseThrow().getLast();
    }

    private static boolean valid(RawZombieSnapshot raw) {
        return raw.id() >= 0 && Float.isFinite(raw.realX()) && Float.isFinite(raw.realY())
                && Float.isFinite(raw.directionRadians())
                && raw.health() >= 0 && raw.health() <= 1000 && raw.target() >= -1
                && raw.state() != null;
    }

    @Override public Optional<NativeDecisionAdapter.PlayerFacts> playerState(Object receiver, Object[] arguments) { return delegate.playerState(receiver, arguments); }
    @Override public Optional<NativeDecisionAdapter.AttackFacts> nativeAttack(Object receiver, Object[] arguments) { return delegate.nativeAttack(receiver, arguments); }
    @Override public Optional<NativeDecisionAdapter.HitFacts> nativeHit(Object receiver, Object[] arguments) { return delegate.nativeHit(receiver, arguments); }
    @Override public boolean knownHitVariant(Object receiver) { return delegate.knownHitVariant(receiver); }
    @Override public Optional<NativeDecisionAdapter.ZombieStateFacts> zombieState(Object receiver, Object[] arguments) { return Optional.empty(); }
    @Override public Optional<NativeDecisionAdapter.ZombieOwnershipFacts> zombieOwnership(Object receiver, Object[] arguments) { return Optional.empty(); }
    @Override public Optional<NativeDecisionAdapter.ZombieKeyframeFacts> zombieKeyframe(Object receiver, Object[] arguments) { return delegate.zombieKeyframe(receiver, arguments); }

    record RawZombieSnapshot(
            short id,
            float realX,
            float realY,
            byte realZ,
            float directionRadians,
            short health,
            short target,
            Object state) {}

    record ZombieStateCapture(
            Object zombieIdentity, Object ownerIdentity, RawZombieSnapshot raw) {}

    record ZombieOwnerCapture(
            Object zombieIdentity,
            long zombieId,
            Object ownerBefore,
            Object receiver,
            Object[] arguments) {}
}
