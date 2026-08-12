package ru.apollot.pzsync.hooks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.bytebuddy.jar.asm.Type;
import ru.apollot.pzsync.gate.RuntimeFingerprint;

public record ExactMemberBinding(
        Role role,
        Source source,
        Kind kind,
        String ownerClass,
        String memberName,
        String descriptor,
        String ownerSha256) {
    private static final String PREFIX = "BINDING|";
    private static final Pattern BINARY_CLASS_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern MEMBER_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    public ExactMemberBinding {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ownerClass, "ownerClass");
        Objects.requireNonNull(memberName, "memberName");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(ownerSha256, "ownerSha256");
        if (!BINARY_CLASS_NAME.matcher(ownerClass).matches()
                || !MEMBER_NAME.matcher(memberName).matches()) {
            throw new IllegalArgumentException("exact binding owner/member is malformed");
        }
        role.validate(source, kind, descriptor);
    }

    public static List<ExactMemberBinding> requiredFrom(RuntimeFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        var selected = new EnumMap<Role, ExactMemberBinding>(Role.class);
        fingerprint.methodDescriptors().forEach((key, descriptor) -> {
            if (!key.startsWith(PREFIX)) {
                return;
            }
            String[] parts = key.split("\\|", 5);
            if (parts.length != 5) {
                throw new IllegalArgumentException("malformed exact binding key");
            }
            final Role role;
            final Source source;
            final Kind kind;
            try {
                role = Role.valueOf(parts[1]);
                source = Source.valueOf(parts[2]);
                kind = Kind.valueOf(parts[3]);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("unknown exact binding role/source/kind", error);
            }
            int separator = parts[4].lastIndexOf('#');
            if (separator <= 0 || separator == parts[4].length() - 1) {
                throw new IllegalArgumentException("malformed exact binding member");
            }
            String owner = parts[4].substring(0, separator);
            String member = parts[4].substring(separator + 1);
            String hash = fingerprint.classHashes().get(owner);
            if (hash == null) {
                throw new IllegalArgumentException("missing exact binding owner hash: " + role);
            }
            var binding =
                    new ExactMemberBinding(role, source, kind, owner, member, descriptor, hash);
            if (selected.putIfAbsent(role, binding) != null) {
                throw new IllegalArgumentException("duplicate exact binding role: " + role);
            }
        });
        var ordered = new ArrayList<ExactMemberBinding>(Role.values().length);
        var members = new HashSet<String>();
        for (Role role : Role.values()) {
            ExactMemberBinding binding = selected.get(role);
            if (binding == null) {
                throw new IllegalArgumentException("missing exact binding role: " + role);
            }
            String identity = binding.kind()
                    + "|"
                    + binding.ownerClass()
                    + "#"
                    + binding.memberName()
                    + binding.descriptor();
            if (!members.add(identity)) {
                throw new IllegalArgumentException(
                        "one exact member cannot carry multiple binding roles");
            }
            ordered.add(binding);
        }
        return List.copyOf(ordered);
    }

    static String contextFailure(
            List<ExactMemberBinding> bindings, List<HookDescriptor> hooks) {
        if (bindings.isEmpty()) {
            return null;
        }
        var hooksByPoint = new EnumMap<HookDescriptor.HookPoint, HookDescriptor>(
                HookDescriptor.HookPoint.class);
        hooks.forEach(hook -> hooksByPoint.put(hook.point(), hook));
        var bindingsByRole = new EnumMap<Role, ExactMemberBinding>(Role.class);
        bindings.forEach(binding -> bindingsByRole.put(binding.role(), binding));
        var hookMethods = new HashSet<String>();
        hooks.forEach(hook -> hookMethods.add(methodIdentity(
                hook.className(), hook.methodName(), hook.methodDescriptor())));

        for (ExactMemberBinding binding : bindings) {
            if (binding.kind() == Kind.METHOD
                    && hookMethods.contains(methodIdentity(
                            binding.ownerClass(),
                            binding.memberName(),
                            binding.descriptor()))) {
                return "runtime-binding-cross-namespace-collision";
            }
            HookDescriptor context = hooksByPoint.get(binding.role().hookPoint());
            if (context == null || !sourceMatches(binding, context, bindingsByRole)) {
                return "runtime-binding-context-mismatch";
            }
        }
        return null;
    }

    private static boolean sourceMatches(
            ExactMemberBinding binding,
            HookDescriptor hook,
            Map<Role, ExactMemberBinding> bindingsByRole) {
        return switch (binding.source()) {
            case THIS -> binding.ownerClass().equals(hook.className());
            case ARG0, ARG1, ARG2, ARG3, ARG4, ARG5, ARG6, ARG7 ->
                    exactReferenceArgument(binding, hook);
            case HIT_CONTEXT -> binding.role().group == Group.COLLISION
                    && hook.point() == HookDescriptor.HookPoint.HIT_GATE
                    && binding.ownerClass().equals(hook.className());
            case LUA_ROOT -> binding.role() == Role.LUA_PUBLISH_ATOMIC
                    && exactLuaRootOwner(binding, bindingsByRole.get(Role.LUA_ROOT));
        };
    }

    private static boolean exactReferenceArgument(
            ExactMemberBinding binding, HookDescriptor hook) {
        Type[] arguments;
        try {
            arguments = Type.getMethodType(hook.methodDescriptor()).getArgumentTypes();
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        int index = binding.source().argumentIndex();
        return index >= 0
                && index < arguments.length
                && arguments[index].getSort() == Type.OBJECT
                && binding.ownerClass().equals(arguments[index].getClassName());
    }

    private static boolean exactLuaRootOwner(
            ExactMemberBinding publisher, ExactMemberBinding root) {
        if (root == null) {
            return false;
        }
        try {
            Type rootType = root.kind() == Kind.METHOD
                    ? Type.getMethodType(root.descriptor()).getReturnType()
                    : Type.getType(root.descriptor());
            return rootType.getSort() == Type.OBJECT
                    && publisher.ownerClass().equals(rootType.getClassName());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static String methodIdentity(
            String owner, String member, String descriptor) {
        return owner + "#" + member + descriptor;
    }

    public enum Kind {
        FIELD,
        METHOD
    }

    public enum Source {
        THIS(-1),
        ARG0(0),
        ARG1(1),
        ARG2(2),
        ARG3(3),
        ARG4(4),
        ARG5(5),
        ARG6(6),
        ARG7(7),
        HIT_CONTEXT(-1),
        LUA_ROOT(-1);

        private final int argumentIndex;

        Source(int argumentIndex) {
            this.argumentIndex = argumentIndex;
        }

        int argumentIndex() {
            return argumentIndex;
        }
    }

    public enum Role {
        PLAYER_ID(Value.LONG, Group.PLAYER),
        PLAYER_GENERATION(Value.LONG, Group.PLAYER),
        PLAYER_SERVER_TIME(Value.LONG, Group.PLAYER),
        PLAYER_X(Value.DOUBLE, Group.PLAYER),
        PLAYER_Y(Value.DOUBLE, Group.PLAYER),
        PLAYER_FLOOR(Value.INT, Group.PLAYER),
        PLAYER_YAW(Value.DOUBLE, Group.PLAYER),
        PLAYER_OWNER_EPOCH(Value.LONG, Group.PLAYER),

        ATTACK_PLAYER_ID(Value.LONG, Group.ATTACK),
        ATTACK_GENERATION(Value.LONG, Group.ATTACK),
        ATTACK_OPENED_AT(Value.LONG, Group.ATTACK),
        ATTACK_WEAPON_ID(Value.LONG, Group.ATTACK),
        ATTACK_MODE(Value.STRING, Group.ATTACK),
        ATTACK_PROJECTILE(Value.BOOLEAN, Group.ATTACK),
        ATTACK_MAX_HITS(Value.INT, Group.ATTACK),
        ATTACK_PROJECTILE_COUNT(Value.INT, Group.ATTACK),

        HIT_PLAYER_ID(Value.LONG, Group.HIT),
        HIT_GENERATION(Value.LONG, Group.HIT),
        HIT_SERVER_TIME(Value.LONG, Group.HIT),
        HIT_TARGET_KIND(Value.INT, Group.HIT),
        HIT_ATTACKER_X(Value.DOUBLE, Group.HIT),
        HIT_ATTACKER_Y(Value.DOUBLE, Group.HIT),
        HIT_ATTACKER_FLOOR(Value.INT, Group.HIT),
        HIT_ATTACKER_YAW(Value.DOUBLE, Group.HIT),
        HIT_ATTACKER_EPOCH(Value.LONG, Group.HIT),
        HIT_ATTACKER_ALIVE(Value.BOOLEAN, Group.HIT),
        HIT_TARGET_ID(Value.LONG, Group.HIT),
        HIT_TARGET_GENERATION(Value.LONG, Group.HIT),
        HIT_TARGET_X(Value.DOUBLE, Group.HIT),
        HIT_TARGET_Y(Value.DOUBLE, Group.HIT),
        HIT_TARGET_FLOOR(Value.INT, Group.HIT),
        HIT_TARGET_YAW(Value.DOUBLE, Group.HIT),
        HIT_TARGET_EPOCH(Value.LONG, Group.HIT),
        HIT_TARGET_ALIVE(Value.BOOLEAN, Group.HIT),
        HIT_WEAPON_ID(Value.LONG, Group.HIT),
        HIT_MODE(Value.STRING, Group.HIT),
        HIT_PROJECTILE(Value.BOOLEAN, Group.HIT),
        HIT_RANGE(Value.DOUBLE, Group.HIT),
        HIT_HALF_ANGLE(Value.DOUBLE, Group.HIT),
        HIT_EQUIPPED(Value.BOOLEAN, Group.HIT),
        HIT_AMMO_AUTHORIZED(Value.BOOLEAN, Group.HIT),
        HIT_PROJECTILE_BUDGET(Value.INT, Group.HIT),
        HIT_NETWORK_SAMPLE_TIME(Value.LONG, Group.HIT),
        HIT_NETWORK_RTT(Value.DOUBLE, Group.HIT),

        ZOMBIE_ID(Value.LONG, Group.ZOMBIE_STATE),
        ZOMBIE_GENERATION(Value.LONG, Group.ZOMBIE_STATE),
        ZOMBIE_SERVER_TIME(Value.LONG, Group.ZOMBIE_STATE),
        ZOMBIE_X(Value.DOUBLE, Group.ZOMBIE_STATE),
        ZOMBIE_Y(Value.DOUBLE, Group.ZOMBIE_STATE),
        ZOMBIE_FLOOR(Value.INT, Group.ZOMBIE_STATE),
        ZOMBIE_YAW(Value.DOUBLE, Group.ZOMBIE_STATE),
        ZOMBIE_OWNER_EPOCH(Value.LONG, Group.ZOMBIE_STATE),

        OWNERSHIP_ZOMBIE_ID(Value.LONG, Group.OWNERSHIP),
        OWNERSHIP_GENERATION(Value.LONG, Group.OWNERSHIP),
        OWNERSHIP_CURRENT_OWNER(Value.LONG, Group.OWNERSHIP),
        OWNERSHIP_TARGET_OWNER(Value.LONG, Group.OWNERSHIP),
        OWNERSHIP_SERVER_TIME(Value.LONG, Group.OWNERSHIP),
        OWNERSHIP_GRAPPLE(Value.BOOLEAN, Group.OWNERSHIP),

        KEYFRAME_EVENT(Value.INT, Group.KEYFRAME),
        COLLISION_SQUARES_LOADED(Value.COLLISION, Group.COLLISION),
        COLLISION_LINE_OF_SIGHT(Value.COLLISION, Group.COLLISION),
        LUA_ROOT(Value.REFERENCE, Group.LUA),
        LUA_PUBLISH_ATOMIC(Value.LUA_PUBLISH, Group.LUA);

        private final Value value;
        private final Group group;

        Role(Value value, Group group) {
            this.value = value;
            this.group = group;
        }

        HookDescriptor.HookPoint hookPoint() {
            return switch (group) {
                case PLAYER -> HookDescriptor.HookPoint.PLAYER_STATE;
                case ATTACK -> HookDescriptor.HookPoint.ATTACK_OPEN;
                case HIT, COLLISION -> HookDescriptor.HookPoint.HIT_GATE;
                case ZOMBIE_STATE -> HookDescriptor.HookPoint.ZOMBIE_STATE;
                case OWNERSHIP -> HookDescriptor.HookPoint.ZOMBIE_OWNERSHIP;
                case KEYFRAME -> HookDescriptor.HookPoint.ZOMBIE_KEYFRAME;
                case LUA -> HookDescriptor.HookPoint.BRIDGE_PUBLISH;
            };
        }

        void validate(Source source, Kind kind, String descriptor) {
            if ((group == Group.COLLISION && source != Source.HIT_CONTEXT)
                    || (this == LUA_PUBLISH_ATOMIC && source != Source.LUA_ROOT)
                    || (group != Group.COLLISION
                            && this != LUA_PUBLISH_ATOMIC
                            && (source == Source.HIT_CONTEXT || source == Source.LUA_ROOT))) {
                throw new IllegalArgumentException("binding source does not match role: " + this);
            }
            Type type;
            try {
                type = kind == Kind.METHOD
                        ? Type.getMethodType(descriptor)
                        : Type.getType(descriptor);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("binding descriptor is not a JVM descriptor", error);
            }
            if (!value.matches(kind, type)) {
                throw new IllegalArgumentException("binding descriptor does not match role: " + this);
            }
        }
    }

    private enum Group {
        PLAYER,
        ATTACK,
        HIT,
        ZOMBIE_STATE,
        OWNERSHIP,
        KEYFRAME,
        COLLISION,
        LUA
    }

    private enum Value {
        LONG,
        DOUBLE,
        INT,
        BOOLEAN,
        STRING,
        REFERENCE,
        COLLISION,
        LUA_PUBLISH;

        private boolean matches(Kind kind, Type type) {
            if (kind == Kind.FIELD && (this == COLLISION || this == LUA_PUBLISH)) {
                return false;
            }
            Type selected = kind == Kind.METHOD ? type.getReturnType() : type;
            if (kind == Kind.METHOD && this != COLLISION && this != LUA_PUBLISH
                    && type.getArgumentTypes().length != 0) {
                return false;
            }
            return switch (this) {
                case LONG -> isIntegral(selected, true);
                case INT -> isIntegral(selected, false);
                case DOUBLE -> selected.getSort() == Type.FLOAT
                        || selected.getSort() == Type.DOUBLE;
                case BOOLEAN -> selected.getSort() == Type.BOOLEAN;
                case STRING -> "java.lang.String".equals(selected.getClassName());
                case REFERENCE -> selected.getSort() == Type.OBJECT
                        || selected.getSort() == Type.ARRAY;
                case COLLISION -> kind == Kind.METHOD
                        && "(DDDDDD)Z".equals(type.getDescriptor());
                case LUA_PUBLISH -> kind == Kind.METHOD
                        && "(Ljava/lang/String;Ljava/lang/Object;)V"
                                .equals(type.getDescriptor());
            };
        }

        private static boolean isIntegral(Type type, boolean includeLong) {
            return type.getSort() == Type.BYTE
                    || type.getSort() == Type.SHORT
                    || type.getSort() == Type.INT
                    || (includeLong && type.getSort() == Type.LONG);
        }
    }
}
