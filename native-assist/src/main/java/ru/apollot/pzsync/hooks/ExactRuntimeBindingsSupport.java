package ru.apollot.pzsync.hooks;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import ru.apollot.pzsync.bridge.BridgePublisher;
import ru.apollot.pzsync.gate.RuntimeFingerprint;
import ru.apollot.pzsync.geometry.CollisionProbe;
import ru.apollot.pzsync.geometry.HistoricalPose;
import ru.apollot.pzsync.history.EntityKind;

/** Builds narrow runtime access solely from the exact role schema in a validated fingerprint. */
public final class ExactRuntimeBindingsSupport {
    private ExactRuntimeBindingsSupport() {}

    public static HookInstaller.ProductionBindings create(
            RuntimeFingerprint fingerprint, ClassLoader targetLoader) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(targetLoader, "targetLoader");
        List<ExactMemberBinding> bindings = ExactMemberBinding.requiredFrom(fingerprint);
        var access = new ExactAccess(bindings, targetLoader);
        String identity = bindings.stream()
                .map(binding -> binding.role()
                        + "|"
                        + binding.source()
                        + "|"
                        + binding.kind()
                        + "|"
                        + binding.ownerClass()
                        + "#"
                        + binding.memberName()
                        + "|"
                        + binding.descriptor()
                        + "|"
                        + binding.ownerSha256())
                .collect(Collectors.joining("\n"));
        return HookInstaller.ProductionBindings.exact(
                access, access, access::publish, targetLoader, bindings, identity, access::verify);
    }

    private static final class ExactAccess
            implements NativeDecisionAdapter.PacketAccess, CollisionProbe {
        private static final String NAMESPACE = "ApolloNativeAssist";

        private final EnumMap<ExactMemberBinding.Role, BoundMember> members =
                new EnumMap<>(ExactMemberBinding.Role.class);
        private final ClassLoader targetLoader;
        private Object currentHitContext;

        private ExactAccess(List<ExactMemberBinding> bindings, ClassLoader targetLoader) {
            this.targetLoader = targetLoader;
            for (ExactMemberBinding binding : bindings) {
                members.put(binding.role(), new BoundMember(binding));
            }
        }

        private synchronized void verify() throws Throwable {
            for (BoundMember member : members.values()) {
                member.resolve(targetLoader);
            }
        }

        @Override
        public synchronized Optional<NativeDecisionAdapter.PlayerFacts> playerState(
                Object receiver, Object[] arguments) {
            try {
                return Optional.of(new NativeDecisionAdapter.PlayerFacts(
                        longValue(ExactMemberBinding.Role.PLAYER_ID, receiver, arguments),
                        longValue(ExactMemberBinding.Role.PLAYER_GENERATION, receiver, arguments),
                        longValue(ExactMemberBinding.Role.PLAYER_SERVER_TIME, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.PLAYER_X, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.PLAYER_Y, receiver, arguments),
                        intValue(ExactMemberBinding.Role.PLAYER_FLOOR, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.PLAYER_YAW, receiver, arguments),
                        longValue(ExactMemberBinding.Role.PLAYER_OWNER_EPOCH, receiver, arguments)));
            } catch (MissingFact expectedAbsence) {
                return Optional.empty();
            } catch (Throwable unexpected) {
                throw unchecked(unexpected);
            }
        }

        @Override
        public synchronized Optional<NativeDecisionAdapter.AttackFacts> nativeAttack(
                Object receiver, Object[] arguments) {
            try {
                return Optional.of(new NativeDecisionAdapter.AttackFacts(
                        longValue(ExactMemberBinding.Role.ATTACK_PLAYER_ID, receiver, arguments),
                        longValue(ExactMemberBinding.Role.ATTACK_GENERATION, receiver, arguments),
                        longValue(ExactMemberBinding.Role.ATTACK_OPENED_AT, receiver, arguments),
                        longValue(ExactMemberBinding.Role.ATTACK_WEAPON_ID, receiver, arguments),
                        stringValue(ExactMemberBinding.Role.ATTACK_MODE, receiver, arguments),
                        booleanValue(ExactMemberBinding.Role.ATTACK_PROJECTILE, receiver, arguments),
                        intValue(ExactMemberBinding.Role.ATTACK_MAX_HITS, receiver, arguments),
                        intValue(
                                ExactMemberBinding.Role.ATTACK_PROJECTILE_COUNT,
                                receiver,
                                arguments)));
            } catch (MissingFact expectedAbsence) {
                return Optional.empty();
            } catch (Throwable unexpected) {
                throw unchecked(unexpected);
            }
        }

        @Override
        public synchronized Optional<NativeDecisionAdapter.HitFacts> nativeHit(
                Object receiver, Object[] arguments) {
            currentHitContext = null;
            try {
                EntityKind targetKind = entityKind(
                        intValue(ExactMemberBinding.Role.HIT_TARGET_KIND, receiver, arguments));
                long serverTime =
                        longValue(ExactMemberBinding.Role.HIT_SERVER_TIME, receiver, arguments);
                var attacker = new NativeDecisionAdapter.PoseFacts(
                        longValue(ExactMemberBinding.Role.HIT_PLAYER_ID, receiver, arguments),
                        longValue(ExactMemberBinding.Role.HIT_GENERATION, receiver, arguments),
                        serverTime,
                        doubleValue(ExactMemberBinding.Role.HIT_ATTACKER_X, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.HIT_ATTACKER_Y, receiver, arguments),
                        intValue(ExactMemberBinding.Role.HIT_ATTACKER_FLOOR, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.HIT_ATTACKER_YAW, receiver, arguments),
                        longValue(ExactMemberBinding.Role.HIT_ATTACKER_EPOCH, receiver, arguments),
                        booleanValue(ExactMemberBinding.Role.HIT_ATTACKER_ALIVE, receiver, arguments));
                var target = new NativeDecisionAdapter.PoseFacts(
                        longValue(ExactMemberBinding.Role.HIT_TARGET_ID, receiver, arguments),
                        longValue(
                                ExactMemberBinding.Role.HIT_TARGET_GENERATION,
                                receiver,
                                arguments),
                        serverTime,
                        doubleValue(ExactMemberBinding.Role.HIT_TARGET_X, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.HIT_TARGET_Y, receiver, arguments),
                        intValue(ExactMemberBinding.Role.HIT_TARGET_FLOOR, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.HIT_TARGET_YAW, receiver, arguments),
                        longValue(ExactMemberBinding.Role.HIT_TARGET_EPOCH, receiver, arguments),
                        booleanValue(ExactMemberBinding.Role.HIT_TARGET_ALIVE, receiver, arguments));
                var facts = new NativeDecisionAdapter.HitFacts(
                        attacker.entityId(),
                        attacker.generation(),
                        serverTime,
                        targetKind,
                        attacker,
                        target,
                        longValue(ExactMemberBinding.Role.HIT_WEAPON_ID, receiver, arguments),
                        stringValue(ExactMemberBinding.Role.HIT_MODE, receiver, arguments),
                        booleanValue(ExactMemberBinding.Role.HIT_PROJECTILE, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.HIT_RANGE, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.HIT_HALF_ANGLE, receiver, arguments),
                        booleanValue(ExactMemberBinding.Role.HIT_EQUIPPED, receiver, arguments),
                        booleanValue(
                                ExactMemberBinding.Role.HIT_AMMO_AUTHORIZED,
                                receiver,
                                arguments),
                        intValue(
                                ExactMemberBinding.Role.HIT_PROJECTILE_BUDGET,
                                receiver,
                                arguments),
                        longValue(
                                ExactMemberBinding.Role.HIT_NETWORK_SAMPLE_TIME,
                                receiver,
                                arguments),
                        doubleValue(
                                ExactMemberBinding.Role.HIT_NETWORK_RTT,
                                receiver,
                                arguments),
                        NativeDecisionAdapter.NativeValidationStage.VALIDATED_PRE_APPLY);
                currentHitContext = receiver;
                return Optional.of(facts);
            } catch (MissingFact expectedAbsence) {
                return Optional.empty();
            } catch (Throwable unexpected) {
                throw unchecked(unexpected);
            }
        }

        @Override
        public synchronized Optional<NativeDecisionAdapter.ZombieStateFacts> zombieState(
                Object receiver, Object[] arguments) {
            try {
                return Optional.of(new NativeDecisionAdapter.ZombieStateFacts(
                        longValue(ExactMemberBinding.Role.ZOMBIE_ID, receiver, arguments),
                        longValue(ExactMemberBinding.Role.ZOMBIE_GENERATION, receiver, arguments),
                        longValue(ExactMemberBinding.Role.ZOMBIE_SERVER_TIME, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.ZOMBIE_X, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.ZOMBIE_Y, receiver, arguments),
                        intValue(ExactMemberBinding.Role.ZOMBIE_FLOOR, receiver, arguments),
                        doubleValue(ExactMemberBinding.Role.ZOMBIE_YAW, receiver, arguments),
                        longValue(ExactMemberBinding.Role.ZOMBIE_OWNER_EPOCH, receiver, arguments)));
            } catch (MissingFact expectedAbsence) {
                return Optional.empty();
            } catch (Throwable unexpected) {
                throw unchecked(unexpected);
            }
        }

        @Override
        public synchronized Optional<NativeDecisionAdapter.ZombieOwnershipFacts> zombieOwnership(
                Object receiver, Object[] arguments) {
            try {
                return Optional.of(new NativeDecisionAdapter.ZombieOwnershipFacts(
                        longValue(ExactMemberBinding.Role.OWNERSHIP_ZOMBIE_ID, receiver, arguments),
                        longValue(ExactMemberBinding.Role.OWNERSHIP_GENERATION, receiver, arguments),
                        longValue(
                                ExactMemberBinding.Role.OWNERSHIP_CURRENT_OWNER,
                                receiver,
                                arguments),
                        longValue(
                                ExactMemberBinding.Role.OWNERSHIP_TARGET_OWNER,
                                receiver,
                                arguments),
                        longValue(
                                ExactMemberBinding.Role.OWNERSHIP_SERVER_TIME,
                                receiver,
                                arguments),
                        booleanValue(
                                ExactMemberBinding.Role.OWNERSHIP_GRAPPLE,
                                receiver,
                                arguments)));
            } catch (MissingFact expectedAbsence) {
                return Optional.empty();
            } catch (Throwable unexpected) {
                throw unchecked(unexpected);
            }
        }

        @Override
        public synchronized Optional<NativeDecisionAdapter.ZombieKeyframeFacts> zombieKeyframe(
                Object receiver, Object[] arguments) {
            try {
                return Optional.of(new NativeDecisionAdapter.ZombieKeyframeFacts(
                        intValue(ExactMemberBinding.Role.KEYFRAME_EVENT, receiver, arguments)));
            } catch (MissingFact expectedAbsence) {
                return Optional.empty();
            } catch (Throwable unexpected) {
                throw unchecked(unexpected);
            }
        }

        @Override
        public synchronized boolean squaresLoaded(
                HistoricalPose attacker, HistoricalPose target) {
            return collision(ExactMemberBinding.Role.COLLISION_SQUARES_LOADED, attacker, target);
        }

        @Override
        public synchronized boolean currentLineOfSight(
                HistoricalPose attacker, HistoricalPose target) {
            return collision(ExactMemberBinding.Role.COLLISION_LINE_OF_SIGHT, attacker, target);
        }

        private boolean collision(
                ExactMemberBinding.Role role, HistoricalPose attacker, HistoricalPose target) {
            Object context = currentHitContext;
            if (context == null) {
                return false;
            }
            try {
                Object result = members.get(role).invoke(
                        targetLoader,
                        context,
                        attacker.position().x(),
                        attacker.position().y(),
                        attacker.position().z(),
                        target.position().x(),
                        target.position().y(),
                        target.position().z());
                return result instanceof Boolean value && value;
            } catch (MissingFact expectedAbsence) {
                return false;
            } catch (Throwable unexpected) {
                throw unchecked(unexpected);
            }
        }

        private synchronized void publish(
                Object receiver, Object[] arguments, BridgePublisher.Exports exports)
                throws Exception {
            try {
                Object root = value(ExactMemberBinding.Role.LUA_ROOT, receiver, arguments);
                Map<String, Object> allowlist = new LinkedHashMap<>();
                allowlist.put("status", exports.status());
                allowlist.put("metrics", exports.metrics());
                allowlist.put("handshake", exports.handshake());
                members.get(ExactMemberBinding.Role.LUA_PUBLISH_ATOMIC)
                        .invoke(targetLoader, root, NAMESPACE, Map.copyOf(allowlist));
            } catch (RuntimeException error) {
                throw error;
            } catch (Error fatal) {
                throw fatal;
            } catch (Exception error) {
                throw error;
            } catch (Throwable error) {
                throw new Exception("exact publication failed", error);
            }
        }

        private Object value(
                ExactMemberBinding.Role role, Object receiver, Object[] arguments)
                throws Throwable {
            BoundMember member = members.get(role);
            Object source = switch (member.binding.source()) {
                case THIS -> receiver;
                case ARG0, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7 -> {
                    int index = member.binding.source().argumentIndex();
                    if (arguments == null || index >= arguments.length) {
                        throw new MissingFact();
                    }
                    yield arguments[index];
                }
                case HIT_CONTEXT -> currentHitContext;
                case LUA_ROOT -> throw new MissingFact();
            };
            if (source == null) {
                throw new MissingFact();
            }
            Object selected = member.invoke(targetLoader, source);
            if (selected == null) {
                throw new MissingFact();
            }
            return selected;
        }

        private long longValue(
                ExactMemberBinding.Role role, Object receiver, Object[] arguments)
                throws Throwable {
            Object selected = value(role, receiver, arguments);
            if (!(selected instanceof Number number)) {
                throw new MissingFact();
            }
            return number.longValue();
        }

        private int intValue(
                ExactMemberBinding.Role role, Object receiver, Object[] arguments)
                throws Throwable {
            Object selected = value(role, receiver, arguments);
            if (!(selected instanceof Number number)) {
                throw new MissingFact();
            }
            return number.intValue();
        }

        private double doubleValue(
                ExactMemberBinding.Role role, Object receiver, Object[] arguments)
                throws Throwable {
            Object selected = value(role, receiver, arguments);
            if (!(selected instanceof Number number)) {
                throw new MissingFact();
            }
            return number.doubleValue();
        }

        private boolean booleanValue(
                ExactMemberBinding.Role role, Object receiver, Object[] arguments)
                throws Throwable {
            Object selected = value(role, receiver, arguments);
            if (!(selected instanceof Boolean value)) {
                throw new MissingFact();
            }
            return value;
        }

        private String stringValue(
                ExactMemberBinding.Role role, Object receiver, Object[] arguments)
                throws Throwable {
            Object selected = value(role, receiver, arguments);
            if (!(selected instanceof String value) || value.isBlank()) {
                throw new MissingFact();
            }
            return value;
        }

        private static EntityKind entityKind(int ordinal) {
            EntityKind[] values = EntityKind.values();
            if (ordinal < 0 || ordinal >= values.length) {
                throw new MissingFact();
            }
            return values[ordinal];
        }
    }

    private static final class BoundMember {
        private final ExactMemberBinding binding;
        private MethodHandle handle;
        private Class<?> owner;

        private BoundMember(ExactMemberBinding binding) {
            this.binding = binding;
        }

        private synchronized void resolve(ClassLoader loader) throws Throwable {
            if (handle != null) {
                return;
            }
            Class<?> resolvedOwner = Class.forName(binding.ownerClass(), false, loader);
            if (resolvedOwner.getClassLoader() != loader) {
                throw new IllegalAccessException("binding owner loader mismatch");
            }
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    resolvedOwner, MethodHandles.lookup());
            if (binding.kind() == ExactMemberBinding.Kind.FIELD) {
                Class<?> fieldType = MethodType.fromMethodDescriptorString(
                                "()" + binding.descriptor(), loader)
                        .returnType();
                handle = lookup.findGetter(resolvedOwner, binding.memberName(), fieldType);
            } else {
                MethodType methodType = MethodType.fromMethodDescriptorString(
                        binding.descriptor(), loader);
                handle = lookup.findVirtual(resolvedOwner, binding.memberName(), methodType);
            }
            owner = resolvedOwner;
        }

        private Object invoke(ClassLoader loader, Object receiver, Object... arguments)
                throws Throwable {
            resolve(loader);
            if (!owner.isInstance(receiver)) {
                throw new MissingFact();
            }
            Object[] invocation = new Object[arguments.length + 1];
            invocation[0] = receiver;
            System.arraycopy(arguments, 0, invocation, 1, arguments.length);
            return handle.invokeWithArguments(invocation);
        }
    }

    private static RuntimeException unchecked(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        if (error instanceof Error fatal) {
            throw fatal;
        }
        return new IllegalStateException("exact native binding invocation failed", error);
    }

    private static final class MissingFact extends RuntimeException {}
}
