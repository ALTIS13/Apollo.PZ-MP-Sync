package ru.apollot.pzsync.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import ru.apollot.pzsync.gate.FingerprintLoader;
import ru.apollot.pzsync.gate.RuntimeFingerprint;
import ru.apollot.pzsync.gate.RuntimeFingerprint.EntrypointElement;
import ru.apollot.pzsync.runtime.RuntimeAdapterSpec;

/**
 * Deterministically emits the one production fingerprint supported by Native Assist 0.2.2.
 * It reads class entries directly and never loads or initializes Project Zomboid classes.
 */
public final class ProductionFingerprintGenerator {
    public static final String SERVER_JAR_SHA256 =
            "bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227";
    public static final String NATIVE_MANIFEST_SHA256 =
            "86dcfd62671e7a8618c9bbba8433a82425b9c2e896a635c4f21aa70de17108ba";
    public static final String IMAGE_REFERENCE =
            "ghcr.io/renegade-master/zomboid-dedicated-server@sha256:"
                    + "5e3479ea2ef66a4f14686fd3abc3286cf31a82c0e37f737b4b5976ff37da9951";
    public static final List<EntrypointElement> IMAGE_ENTRYPOINT = List.of(
            new EntrypointElement(
                    "/bin/bash", "file", "0755",
                    "7e8d290708f90eec5e87c6715df90140b7b02fb7a4deb3be08b71d201703ae58"),
            new EntrypointElement(
                    "/home/steam/run_server.sh", "file", "0755",
                    "7a173dfaa49f7270f542ae3ab8e12266a6ee63e07cbabec71d88f8f1e3169be8"));
    public static final String IMAGE_CMD = "null";
    public static final String RUNTIME_LOCK_MODE = "none-captured";

    private static final String APP_ID = "380870";
    private static final String BUILD_ID = "24775771";
    private static final String GAME_VERSION = "42.20.3";
    private static final String WORKSHOP_ID = "3780069702";
    private static final String LUA_MOD_ID = "ApolloMPSyncB42";
    private static final String BRIDGE_PROTOCOL = "1";
    private static final String FACTORY =
            "ru.apollot.pzsync.hooks.ExactRuntimeBindingsFactory";
    private static final String FACTORY_DESCRIPTOR =
            "(Lru/apollot/pzsync/gate/RuntimeFingerprint;)"
                    + "Lru/apollot/pzsync/hooks/HookInstaller$ProductionBindings;";
    private static final int ACCESS_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE
            | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_BRIDGE | Opcodes.ACC_VARARGS
            | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_STRICT
            | Opcodes.ACC_SYNTHETIC;
    private static final long MAX_SERVER_JAR_BYTES = 128L * 1024 * 1024;
    private static final long MAX_AGENT_JAR_BYTES = 16L * 1024 * 1024;
    private static final long MAX_NATIVE_MANIFEST_BYTES = 1024L * 1024;
    private static final JarLimits SERVER_JAR_LIMITS =
            new JarLimits(30_000, 16L * 1024 * 1024, 256L * 1024 * 1024);
    private static final JarLimits AGENT_JAR_LIMITS =
            new JarLimits(10_000, 4L * 1024 * 1024, 32L * 1024 * 1024);

    private ProductionFingerprintGenerator() {}

    public record Inputs(Path serverJar, Path nativeManifest, Path agentJar) {
        public Inputs {
            if (serverJar == null || nativeManifest == null || agentJar == null) {
                throw new IllegalArgumentException("production-fingerprint-input-missing");
            }
        }
    }

    public record Generated(byte[] bytes, RuntimeFingerprint fingerprint, String transportSha256) {
        public Generated {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public static Generated generate(Inputs inputs) throws IOException {
        InputSnapshot serverSnapshot = InputSnapshot.read(
                "production-server-jar", inputs.serverJar(), MAX_SERVER_JAR_BYTES);
        InputSnapshot nativeSnapshot = InputSnapshot.read(
                "production-native-manifest", inputs.nativeManifest(), MAX_NATIVE_MANIFEST_BYTES);
        InputSnapshot agentSnapshot = InputSnapshot.read(
                "production-agent-jar", inputs.agentJar(), MAX_AGENT_JAR_BYTES);

        JarIndex server = JarIndex.open(serverSnapshot, SERVER_JAR_LIMITS);
        JarIndex agent = JarIndex.open(agentSnapshot, AGENT_JAR_LIMITS);
        requireHash("production-server-jar-sha256-mismatch", SERVER_JAR_SHA256,
                serverSnapshot.bytes());
        requireHash("production-native-manifest-sha256-mismatch", NATIVE_MANIFEST_SHA256,
                nativeSnapshot.bytes());
        String agentSha = sha256(agentSnapshot.bytes());

        {
            var model = new FingerprintModel();
            model.methodDescriptors.put("RUNTIME_ADAPTER|PZ_42_20_3", "1");
            declareHooks(model, server);
            declareVariants(model, server);
            declareA4Chains(model, server);
            declareA5ChainsAndSupport(model, server);
            declareA6Chains(model, server);
            declareCapabilitiesAndProof(model, server);
            Member factory = agent.member(FACTORY, "create", true, true, FACTORY_DESCRIPTOR);
            model.classHashes.put(FACTORY, agent.classHash(FACTORY));
            model.methodDescriptors.put(
                    "RUNTIME_BINDINGS_FACTORY|" + FACTORY + "#create", factory.descriptor());

            byte[] canonical = canonicalBytes(model, agentSha);
            RuntimeFingerprint fingerprint = new FingerprintLoader().loadBytes(canonical);
            RuntimeAdapterSpec spec = RuntimeAdapterSpec.requiredFrom(fingerprint);
            try (var loader = server.resourceLoader(
                    ProductionFingerprintGenerator.class.getClassLoader())) {
                spec.preflight(loader);
            }
            return new Generated(canonical, fingerprint, sha256(canonical));
        }
    }

    /** Resource-only exact-JAR loader for the bounded offline dry preflight. */
    public static JarResourceLoader exactJarResourceLoader(Path serverJar, ClassLoader parent)
            throws IOException {
        InputSnapshot snapshot = InputSnapshot.read(
                "production-server-jar", serverJar, MAX_SERVER_JAR_BYTES);
        JarIndex index = JarIndex.open(snapshot, SERVER_JAR_LIMITS);
        requireHash("production-server-jar-sha256-mismatch", SERVER_JAR_SHA256,
                snapshot.bytes());
        return index.resourceLoader(parent);
    }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = parseArguments(arguments);
        var generated = generate(new Inputs(
                Path.of(required(options, "--server-jar")),
                Path.of(required(options, "--native-manifest")),
                Path.of(required(options, "--agent-jar"))));
        Path output = Path.of(required(options, "--output"));
        Path parent = output.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("production-fingerprint-output-parent-missing");
        }
        Path temporary = Files.createTempFile(parent, output.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, generated.bytes());
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
        System.out.println("fingerprintSha256=" + generated.fingerprint().fingerprintSha256());
        System.out.println("transportSha256=" + generated.transportSha256());
        System.out.println("agentSha256=" + generated.fingerprint().agentSha256());
    }

    private static void declareHooks(FingerprintModel target, JarIndex jar) throws IOException {
        hook(target, jar, "PLAYER_ACCEPTED", "VOID_SUCCESS", "INSTANCE",
                "zombie.characters.NetworkPlayerAI", "parse",
                "(Lzombie/network/packets/character/PlayerPacket;)V");
        hook(target, jar, "ATTACK_ACCEPTED", "VOID_SUCCESS", "INSTANCE",
                "zombie.network.packets.hit.AttackCollisionCheckPacket", "processServer",
                "(Lzombie/network/PacketTypes$PacketType;"
                        + "Lzombie/core/raknet/UdpConnection;)V");
        hook(target, jar, "HIT_PRE_APPLY", "PREVALIDATED_VOID", "INSTANCE",
                "zombie.network.packets.hit.HitCharacter", "processServer",
                "(Lzombie/network/PacketTypes$PacketType;"
                        + "Lzombie/core/raknet/UdpConnection;)V");
        hook(target, jar, "ZOMBIE_APPLY", "VOID_SUCCESS", "INSTANCE",
                "zombie.popman.NetworkZombiePacker", "applyZombie",
                "(Lzombie/characters/IsoZombie;)V");
        hook(target, jar, "ZOMBIE_OWNER", "VOID_SUCCESS", "INSTANCE",
                "zombie.popman.NetworkZombieManager", "moveZombie",
                "(Lzombie/characters/IsoZombie;Lzombie/core/raknet/UdpConnection;"
                        + "Lzombie/characters/IsoPlayer;)V");
        hook(target, jar, "LUA_INIT", "VOID_SUCCESS", "STATIC",
                "zombie.Lua.LuaManager", "init", "()V");
    }

    private static void declareVariants(FingerprintModel target, JarIndex jar) throws IOException {
        variant(target, jar, "PLAYER_HIT_PLAYER", "PLAYER",
                "zombie.network.packets.hit.PlayerHitPlayerPacket",
                "zombie.network.packets.hit.PlayerHit",
                "zombie.network.packets.hit.HitCharacter");
        variant(target, jar, "PLAYER_HIT_ZOMBIE", "ZOMBIE",
                "zombie.network.packets.hit.PlayerHitZombiePacket",
                "zombie.network.packets.hit.PlayerHit",
                "zombie.network.packets.hit.HitCharacter");
        variant(target, jar, "ZOMBIE_HIT_PLAYER", "PLAYER",
                "zombie.network.packets.hit.ZombieHitPlayerPacket",
                "zombie.network.packets.hit.ZombieHit",
                "zombie.network.packets.hit.HitCharacter");
    }

    private static void declareA4Chains(FingerprintModel target, JarIndex jar) throws IOException {
        chain(target, jar, "PLAYER_RELATED_PLAYER", "THIS", 0, "INSTANCE_FIELD",
                "zombie.characters.NetworkPlayerAI", "player", false,
                "Lzombie/characters/IsoPlayer;");
        chain(target, jar, "PLAYER_RELATED_PLAYER", "THIS", 1, "VIRTUAL0",
                "zombie.characters.IsoPlayer", "getOnlineID", true, "()S");
        chain(target, jar, "PLAYER_PREDICTION", "ARG0", 0, "INSTANCE_FIELD",
                "zombie.network.packets.character.PlayerPacket", "prediction", false,
                "Lzombie/network/fields/character/Prediction;");
        chain(target, jar, "PLAYER_PREDICTION", "ARG0", 1, "INSTANCE_FIELD",
                "zombie.network.fields.character.Prediction", "x", false, "F");
        chain(target, jar, "ATTACK_PLAYER", "THIS", 0, "INSTANCE_FIELD",
                "zombie.network.packets.hit.AttackCollisionCheckPacket", "wielder", false,
                "Lzombie/network/fields/character/PlayerID;");
        chain(target, jar, "ATTACK_PLAYER", "THIS", 1, "VIRTUAL0",
                "zombie.network.fields.character.PlayerID", "getPlayer", true,
                "()Lzombie/characters/IsoPlayer;");
        chain(target, jar, "ATTACK_WEAPON", "THIS", 0, "INSTANCE_FIELD",
                "zombie.network.packets.hit.AttackCollisionCheckPacket", "weapon", false,
                "Lzombie/network/fields/hit/Weapon;");
        chain(target, jar, "ATTACK_WEAPON", "THIS", 1, "VIRTUAL0",
                "zombie.network.fields.hit.Weapon", "getWeapon", true,
                "()Lzombie/inventory/types/HandWeapon;");
        chain(target, jar, "ATTACK_WEAPON", "THIS", 2, "VIRTUAL0",
                "zombie.inventory.types.HandWeapon", "getMaxHitCount", true, "()I");
        chain(target, jar, "ATTACK_HIT_COUNT", "THIS", 0, "INSTANCE_FIELD",
                "zombie.network.packets.hit.AttackCollisionCheckPacket", "hitCount", false, "I");
        chain(target, jar, "CONNECTION_AVERAGE_PING", "ARG1", 0, "VIRTUAL0",
                "zombie.core.raknet.UdpConnection", "getAveragePing", true, "()I");
    }

    private static void declareA5ChainsAndSupport(FingerprintModel target, JarIndex jar)
            throws IOException {
        playerRoot(target, jar, "HIT_PVP_ATTACKER", "zombie.network.packets.hit.PlayerHit",
                "wielder");
        playerRoot(target, jar, "HIT_PVP_TARGET",
                "zombie.network.packets.hit.PlayerHitPlayerPacket", "target");
        weaponRoot(target, jar, "HIT_PVP_WEAPON");
        playerRoot(target, jar, "HIT_PVZ_ATTACKER", "zombie.network.packets.hit.PlayerHit",
                "wielder");
        zombieRoot(target, jar, "HIT_PVZ_TARGET",
                "zombie.network.packets.hit.PlayerHitZombiePacket", "target");
        weaponRoot(target, jar, "HIT_PVZ_WEAPON");
        zombieRoot(target, jar, "HIT_ZVP_ATTACKER", "zombie.network.packets.hit.ZombieHit",
                "wielder");
        playerRoot(target, jar, "HIT_ZVP_TARGET",
                "zombie.network.packets.hit.ZombieHitPlayerPacket", "target");
        chain(target, jar, "WORLD_SERVER_MAP", "STATIC", 0, "STATIC_FIELD",
                "zombie.network.ServerMap", "instance", false, "Lzombie/network/ServerMap;");
        chain(target, jar, "WORLD_LOS_CLEAR", "STATIC", 0, "STATIC_FIELD",
                "zombie.iso.LosUtil$TestResults", "Clear", false,
                "Lzombie/iso/LosUtil$TestResults;");
        chain(target, jar, "WORLD_LOS_OPEN_DOOR", "STATIC", 0, "STATIC_FIELD",
                "zombie.iso.LosUtil$TestResults", "ClearThroughOpenDoor", false,
                "Lzombie/iso/LosUtil$TestResults;");

        support(target, jar, "MOVING_X", "zombie.iso.IsoMovingObject", "getX", "()F");
        support(target, jar, "MOVING_Y", "zombie.iso.IsoMovingObject", "getY", "()F");
        support(target, jar, "MOVING_Z", "zombie.iso.IsoMovingObject", "getZ", "()F");
        support(target, jar, "CHARACTER_FORWARD", "zombie.characters.IsoGameCharacter",
                "getForwardDirection", "()Lzombie/iso/Vector2;");
        support(target, jar, "CHARACTER_ALIVE", "zombie.characters.IsoGameCharacter",
                "isAlive", "()Z");
        support(target, jar, "CHARACTER_PRIMARY_HAND", "zombie.characters.IsoGameCharacter",
                "getPrimaryHandItem", "()Lzombie/inventory/InventoryItem;");
        support(target, jar, "CHARACTER_SECONDARY_HAND", "zombie.characters.IsoGameCharacter",
                "getSecondaryHandItem", "()Lzombie/inventory/InventoryItem;");
        support(target, jar, "VECTOR_X", "zombie.iso.Vector2", "getX", "()F");
        support(target, jar, "VECTOR_Y", "zombie.iso.Vector2", "getY", "()F");
        support(target, jar, "WEAPON_ID", "zombie.inventory.InventoryItem", "getID", "()I");
        supportProof(target, jar, "WEAPON_ID",
                "zombie.inventory.types.HandWeapon", "zombie.inventory.InventoryItem");
        support(target, jar, "WEAPON_RANGED", "zombie.inventory.types.HandWeapon",
                "isRanged", "()Z");
        support(target, jar, "WEAPON_PROJECTILES", "zombie.inventory.types.HandWeapon",
                "getProjectileCount", "()I");
        support(target, jar, "WEAPON_MAX_RANGE", "zombie.inventory.types.HandWeapon",
                "getMaxRange", "()F");
        support(target, jar, "WEAPON_MIN_ANGLE", "zombie.inventory.types.HandWeapon",
                "getMinAngle", "()F");
        support(target, jar, "SQUARE_CELL", "zombie.iso.IsoGridSquare", "getCell",
                "()Lzombie/iso/IsoCell;");
    }

    private static void declareA6Chains(FingerprintModel target, JarIndex jar) throws IOException {
        packetField(target, jar, "ZOMBIE_PACKET", "id", "S");
        packetField(target, jar, "ZOMBIE_PACKET_REAL_X", "realX", "F");
        packetField(target, jar, "ZOMBIE_PACKET_REAL_Y", "realY", "F");
        packetField(target, jar, "ZOMBIE_PACKET_REAL_Z", "realZ", "B");
        packetField(target, jar, "ZOMBIE_PACKET_DIRECTION", "dirAngleRads", "F");
        packetField(target, jar, "ZOMBIE_PACKET_HEALTH", "health", "S");
        packetField(target, jar, "ZOMBIE_PACKET_TARGET", "target", "S");
        packetField(target, jar, "ZOMBIE_PACKET_STATE", "realState",
                "Lzombie/network/NetworkVariables$ZombieState;");
        chain(target, jar, "ZOMBIE_ENTITY", "ARG0", 0, "VIRTUAL0",
                "zombie.characters.IsoZombie", "getOnlineID", true, "()S");
        chain(target, jar, "ZOMBIE_CURRENT_OWNER", "ARG0", 0, "VIRTUAL0",
                "zombie.characters.IsoZombie", "getOwner", true,
                "()Lzombie/core/raknet/UdpConnection;");
        chain(target, jar, "OWNER_ZOMBIE", "ARG0", 0, "VIRTUAL0",
                "zombie.characters.IsoZombie", "getOnlineID", true, "()S");
        chain(target, jar, "OWNER_CONNECTION", "ARG0", 0, "VIRTUAL0",
                "zombie.characters.IsoZombie", "getOwner", true,
                "()Lzombie/core/raknet/UdpConnection;");
        chain(target, jar, "LUA_PLATFORM", "STATIC", 0, "STATIC_FIELD",
                "zombie.Lua.LuaManager", "platform", false,
                "Lse/krka/kahlua/j2se/J2SEPlatform;");
        chain(target, jar, "LUA_ENV", "STATIC", 0, "STATIC_FIELD",
                "zombie.Lua.LuaManager", "env", false,
                "Lse/krka/kahlua/vm/KahluaTable;");
    }

    private static void declareCapabilitiesAndProof(FingerprintModel target, JarIndex jar)
            throws IOException {
        capability(target, jar, "WORLD_SQUARES_LOADED", "VIRTUAL",
                "zombie.network.ServerMap", "getGridSquare",
                "(III)Lzombie/iso/IsoGridSquare;");
        capability(target, jar, "WORLD_LINE_OF_SIGHT", "STATIC", "zombie.iso.LosUtil",
                "lineClear", "(Lzombie/iso/IsoCell;IIIIIIZ)Lzombie/iso/LosUtil$TestResults;");
        capability(target, jar, "LUA_NEW_TABLE", "VIRTUAL",
                "se.krka.kahlua.j2se.J2SEPlatform", "newTable",
                "()Lse/krka/kahlua/vm/KahluaTable;");
        capability(target, jar, "LUA_RAWSET", "VIRTUAL", "se.krka.kahlua.vm.KahluaTable",
                "rawset", "(Ljava/lang/Object;Ljava/lang/Object;)V");
        capability(target, jar, "LUA_RAWGET", "VIRTUAL", "se.krka.kahlua.vm.KahluaTable",
                "rawget", "(Ljava/lang/Object;)Ljava/lang/Object;");
        capability(target, jar, "LUA_JAVA_FUNCTION_CALL", "VIRTUAL",
                "se.krka.kahlua.vm.JavaFunction", "call",
                "(Lse/krka/kahlua/vm/LuaCallFrame;I)I");
        capability(target, jar, "LUA_CALL_FRAME_GET", "VIRTUAL",
                "se.krka.kahlua.vm.LuaCallFrame", "get", "(I)Ljava/lang/Object;");
        capability(target, jar, "LUA_CALL_FRAME_PUSH", "VIRTUAL",
                "se.krka.kahlua.vm.LuaCallFrame", "push", "(Ljava/lang/Object;)I");
        Member proof = jar.member("zombie.network.PacketTypes$PacketType", "onServerPacket",
                true, false,
                "(Lzombie/core/network/ByteBufferReader;Lzombie/core/raknet/UdpConnection;)V");
        addClass(target, jar, proof.owner());
        target.methodDescriptors.put("ADAPTER_PROOF|DISPATCHER_ORDER|"
                + hex(proof.access()) + "|" + proof.owner() + "#" + proof.name(),
                proof.descriptor());
    }

    private static void hook(FingerprintModel target, JarIndex jar, String role, String policy,
            String invocation, String owner, String name, String descriptor) throws IOException {
        Member member = jar.member(owner, name, true, "STATIC".equals(invocation), descriptor);
        addClass(target, jar, owner);
        target.methodDescriptors.put("ADAPTER_HOOK|" + role + "|" + policy + "|"
                + invocation + "|" + hex(member.access()) + "|" + owner + "#" + name,
                member.descriptor());
    }

    private static void variant(FingerprintModel target, JarIndex jar, String role, String kind,
            String leaf, String parent, String root) throws IOException {
        jar.requireDirectParent(leaf, parent);
        jar.requireDirectParent(parent, root);
        addClass(target, jar, leaf);
        addClass(target, jar, parent);
        addClass(target, jar, root);
        target.methodDescriptors.put("ADAPTER_VARIANT|" + role + "|" + kind + "|" + leaf,
                "CLASS");
        target.methodDescriptors.put("ADAPTER_VARIANT_PROOF|" + role + "|0|" + parent,
                "CLASS");
        target.methodDescriptors.put("ADAPTER_VARIANT_PROOF|" + role + "|1|" + root,
                "CLASS");
    }

    private static void playerRoot(FingerprintModel target, JarIndex jar, String role,
            String owner, String field) throws IOException {
        chain(target, jar, role, "THIS", 0, "INSTANCE_FIELD", owner, field, false,
                "Lzombie/network/fields/hit/Player;");
        chain(target, jar, role, "THIS", 1, "VIRTUAL0",
                "zombie.network.fields.hit.Player", "getPlayer", true,
                "()Lzombie/characters/IsoPlayer;");
        chain(target, jar, role, "THIS", 2, "VIRTUAL0", "zombie.characters.IsoPlayer",
                "getOnlineID", true, "()S");
    }

    private static void zombieRoot(FingerprintModel target, JarIndex jar, String role,
            String owner, String field) throws IOException {
        chain(target, jar, role, "THIS", 0, "INSTANCE_FIELD", owner, field, false,
                "Lzombie/network/fields/hit/Zombie;");
        chain(target, jar, role, "THIS", 1, "VIRTUAL0",
                "zombie.network.fields.hit.Zombie", "getZombie", true,
                "()Lzombie/characters/IsoZombie;");
        chain(target, jar, role, "THIS", 2, "VIRTUAL0", "zombie.characters.IsoZombie",
                "getOnlineID", true, "()S");
    }

    private static void weaponRoot(FingerprintModel target, JarIndex jar, String role)
            throws IOException {
        chain(target, jar, role, "THIS", 0, "INSTANCE_FIELD",
                "zombie.network.packets.hit.PlayerHit", "weapon", false,
                "Lzombie/network/fields/hit/Weapon;");
        chain(target, jar, role, "THIS", 1, "VIRTUAL0", "zombie.network.fields.hit.Weapon",
                "getWeapon", true, "()Lzombie/inventory/types/HandWeapon;");
        chain(target, jar, role, "THIS", 2, "VIRTUAL0",
                "zombie.inventory.types.HandWeapon", "getMaxHitCount", true, "()I");
    }

    private static void packetField(FingerprintModel target, JarIndex jar, String role,
            String field, String descriptor) throws IOException {
        chain(target, jar, role, "THIS", 0, "INSTANCE_FIELD",
                "zombie.popman.NetworkZombiePacker", "packet", false,
                "Lzombie/network/packets/character/ZombiePacket;");
        chain(target, jar, role, "THIS", 1, "INSTANCE_FIELD",
                "zombie.network.packets.character.ZombiePacket", field, false, descriptor);
    }

    private static void chain(FingerprintModel target, JarIndex jar, String role, String source,
            int index, String kind, String owner, String name, boolean method,
            String descriptor) throws IOException {
        boolean expectedStatic = kind.startsWith("STATIC");
        Member member = jar.member(owner, name, method, expectedStatic, descriptor);
        if (method && Type.getArgumentTypes(member.descriptor()).length != 0) {
            throw new IllegalArgumentException("production-accessor-has-arguments:" + role);
        }
        addClass(target, jar, owner);
        target.methodDescriptors.put("ADAPTER_CHAIN|" + role + "|" + source + "|" + index
                + "|" + kind + "|" + hex(member.access()) + "|" + owner + "#" + name,
                member.descriptor());
    }

    private static void support(FingerprintModel target, JarIndex jar, String role, String owner,
            String name, String descriptor) throws IOException {
        Member member = jar.member(owner, name, true, false, descriptor);
        addClass(target, jar, owner);
        target.methodDescriptors.put("ADAPTER_SUPPORT|" + role + "|VIRTUAL|"
                + hex(member.access()) + "|" + owner + "#" + name, member.descriptor());
    }

    private static void supportProof(FingerprintModel target, JarIndex jar, String role,
            String child, String owner) throws IOException {
        jar.requireDirectParent(child, owner);
        addClass(target, jar, child);
        addClass(target, jar, owner);
        target.methodDescriptors.put(
                "ADAPTER_SUPPORT_PROOF|" + role + "|0|" + child, "CLASS");
        target.methodDescriptors.put(
                "ADAPTER_SUPPORT_PROOF|" + role + "|1|" + owner, "CLASS");
    }

    private static void capability(FingerprintModel target, JarIndex jar, String role,
            String invocation, String owner, String name, String descriptor) throws IOException {
        Member member = jar.member(owner, name, true, "STATIC".equals(invocation), descriptor);
        addClass(target, jar, owner);
        target.methodDescriptors.put("ADAPTER_CAPABILITY|" + role + "|" + invocation + "|"
                + hex(member.access()) + "|" + owner + "#" + name, member.descriptor());
    }

    private static void addClass(FingerprintModel target, JarIndex jar, String owner)
            throws IOException {
        String previous = target.classHashes.putIfAbsent(owner, jar.classHash(owner));
        if (previous != null && !previous.equals(jar.classHash(owner))) {
            throw new IllegalArgumentException("production-class-hash-conflict:" + owner);
        }
    }

    private static byte[] canonicalBytes(FingerprintModel model, String agentSha) {
        var before = new ArrayList<String>();
        before.add("appId=" + APP_ID);
        before.add("buildId=" + BUILD_ID);
        before.add("gameVersionRevision=" + GAME_VERSION);
        before.add("serverJarSha256=" + SERVER_JAR_SHA256);
        before.add("nativeLibrarySha256=" + NATIVE_MANIFEST_SHA256);
        before.add("agentSha256=" + agentSha);
        before.add("imageReference=" + IMAGE_REFERENCE);
        before.add("originalEntrypointCount=" + IMAGE_ENTRYPOINT.size());
        for (int index = 0; index < IMAGE_ENTRYPOINT.size(); index++) {
            EntrypointElement entry = IMAGE_ENTRYPOINT.get(index);
            String prefix = "originalEntrypoint." + index + ".";
            before.add(prefix + "path=" + entry.path());
            before.add(prefix + "kind=" + entry.kind());
            before.add(prefix + "mode=" + entry.mode());
            before.add(prefix + "sha256=" + entry.sha256());
        }
        before.add("imageCmd=" + IMAGE_CMD);
        before.add("runtimeLockMode=" + RUNTIME_LOCK_MODE);
        var after = new ArrayList<String>();
        after.add("jvmFeature=25");
        after.add("os=linux");
        after.add("arch=amd64");
        model.classHashes.entrySet().stream()
                .map(entry -> new PropertyLine(
                        "classHashes." + encode(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(
                        PropertyLine::key, ProductionFingerprintGenerator::asciiCompare))
                .map(PropertyLine::line)
                .forEach(after::add);
        model.methodDescriptors.entrySet().stream()
                .map(entry -> new PropertyLine(
                        "methodDescriptors." + encode(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(
                        PropertyLine::key, ProductionFingerprintGenerator::asciiCompare))
                .map(PropertyLine::line)
                .forEach(after::add);
        after.add("workshopId=" + WORKSHOP_ID);
        after.add("luaModId=" + LUA_MOD_ID);
        after.add("bridgeProtocol=" + BRIDGE_PROTOCOL);
        String omitted = String.join("\n", before) + "\n" + String.join("\n", after) + "\n";
        String identity = sha256(omitted.getBytes(StandardCharsets.UTF_8));
        String canonical = String.join("\n", before) + "\nfingerprintSha256=" + identity
                + "\n" + String.join("\n", after) + "\n";
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    private static int asciiCompare(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.US_ASCII);
        byte[] b = right.getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < Math.min(a.length, b.length); index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.length, b.length);
    }

    private static String encode(String value) {
        if (value.isEmpty() || value.indexOf('%') >= 0 || value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("production-fingerprint-member-name-invalid");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current < 0x21 || current > 0x7e) {
                throw new IllegalArgumentException("production-fingerprint-member-name-invalid");
            }
        }
        return value.replace("#", "%23");
    }

    private static String hex(int access) { return Integer.toHexString(access & ACCESS_MASK); }

    private static void requireHash(String reason, String expected, byte[] bytes) {
        if (!expected.equals(sha256(bytes))) throw new IllegalArgumentException(reason);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Map<String, String> parseArguments(String[] arguments) {
        if (arguments.length % 2 != 0) {
            throw new IllegalArgumentException("production-fingerprint-argument-invalid");
        }
        Set<String> allowed = Set.of("--server-jar", "--native-manifest", "--agent-jar", "--output");
        var options = new HashMap<String, String>();
        for (int index = 0; index < arguments.length; index += 2) {
            if (!allowed.contains(arguments[index])
                    || options.putIfAbsent(arguments[index], arguments[index + 1]) != null) {
                throw new IllegalArgumentException("production-fingerprint-argument-invalid");
            }
        }
        return options;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("production-fingerprint-argument-missing:" + key);
        }
        return value;
    }

    private static final class FingerprintModel {
        private final Map<String, String> classHashes = new TreeMap<>();
        private final Map<String, String> methodDescriptors = new TreeMap<>();
    }

    private record Member(String owner, String name, String descriptor, int access) {}

    private record PropertyLine(String key, String value) {
        String line() { return key + "=" + value; }
    }

    public static final class JarResourceLoader extends ClassLoader implements AutoCloseable {
        private final Map<String, byte[]> classes;
        private int targetClassLoadAttempts;

        private JarResourceLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            this.classes = Map.copyOf(classes);
        }

        @Override public InputStream getResourceAsStream(String name) {
            byte[] bytes = classes.get(name);
            return bytes == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(bytes);
        }

        @Override protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            if (classes.containsKey(resource)
                    && (name.startsWith("zombie.") || name.startsWith("se.krka.kahlua."))) {
                targetClassLoadAttempts++;
                throw new ClassNotFoundException("resource-only exact-JAR preflight: " + name);
            }
            return super.loadClass(name, resolve);
        }

        public int targetClassLoadAttempts() { return targetClassLoadAttempts; }

        public JarResourceLoader withOneByteClassMutation(String owner) {
            String resource = owner.replace('.', '/') + ".class";
            byte[] original = classes.get(resource);
            if (original == null || original.length < 16) {
                throw new IllegalArgumentException("production-class-missing:" + owner);
            }
            var mutated = new HashMap<String, byte[]>(classes);
            byte[] changed = original.clone();
            changed[changed.length - 1] ^= 0x01;
            mutated.put(resource, changed);
            return new JarResourceLoader(getParent(), mutated);
        }

        @Override public void close() {}
    }

    private record InputSnapshot(byte[] bytes) {
        private InputSnapshot {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }

        static InputSnapshot read(String label, Path path, long maximumBytes) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IllegalArgumentException(label + "-link-forbidden");
            }
            if (!attributes.isRegularFile()) {
                throw new IllegalArgumentException(label + "-regular-file-required");
            }
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (FileChannel channel = FileChannel.open(path, options)) {
                long size = channel.size();
                if (size > maximumBytes || size > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(label + "-too-large");
                }
                if (size < 1) throw new IllegalArgumentException(label + "-empty");
                ByteBuffer target = ByteBuffer.allocate((int) size);
                while (target.hasRemaining()) {
                    int read = channel.read(target);
                    if (read < 0) throw new IllegalArgumentException(label + "-changed-during-read");
                }
                ByteBuffer extra = ByteBuffer.allocate(1);
                if (channel.read(extra) >= 0 || channel.size() != size) {
                    throw new IllegalArgumentException(label + "-changed-during-read");
                }
                return new InputSnapshot(target.array());
            }
        }
    }

    private record JarLimits(int entries, long entryBytes, long totalBytes) {
        private JarLimits {
            if (entries < 1 || entryBytes < 1 || totalBytes < entryBytes) {
                throw new IllegalArgumentException("production-jar-limits-invalid");
            }
        }
    }

    private record CentralEntry(String name, long uncompressedBytes, boolean directory) {}

    private static final class JarIndex {
        private final Map<String, byte[]> entries;
        private final Map<String, ClassModel> models = new HashMap<>();

        private JarIndex(Map<String, byte[]> entries) { this.entries = Map.copyOf(entries); }

        static JarIndex open(InputSnapshot snapshot, JarLimits limits) {
            byte[] archive = snapshot.bytes();
            String label = limits == SERVER_JAR_LIMITS
                    ? "production-server-jar" : "production-agent-jar";
            try {
                return new JarIndex(readEntries(label, archive, limits));
            } catch (IllegalArgumentException error) {
                throw error;
            } catch (IOException error) {
                throw new IllegalArgumentException(label + "-invalid", error);
            }
        }

        private static Map<String, byte[]> readEntries(
                String label, byte[] archive, JarLimits limits) throws IOException {
            Map<String, CentralEntry> central = readCentralDirectory(label, archive, limits);
            var classEntries = new LinkedHashMap<String, byte[]>();
            var extracted = new java.util.HashSet<String>();
            long total = 0;
            try (var input = new ZipInputStream(new ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String name = entry.getName();
                    CentralEntry expected = central.get(name);
                    if (expected == null || !extracted.add(name)
                            || expected.directory() != entry.isDirectory()) {
                        throw new IllegalArgumentException(label + "-invalid");
                    }
                    long entryBytes = 0;
                    ByteArrayOutputStream classBytes = name.endsWith(".class")
                            && !entry.isDirectory()
                            ? new ByteArrayOutputStream((int) Math.min(expected.uncompressedBytes(), 8192))
                            : null;
                    byte[] block = new byte[8192];
                    for (int read; (read = input.read(block)) >= 0;) {
                        if (read == 0) continue;
                        entryBytes += read;
                        total += read;
                        if (entryBytes > limits.entryBytes()) {
                            throw new IllegalArgumentException(label + "-entry-too-large");
                        }
                        if (total > limits.totalBytes()) {
                            throw new IllegalArgumentException(label + "-uncompressed-total-exceeded");
                        }
                        if (classBytes != null) classBytes.write(block, 0, read);
                    }
                    if (entryBytes != expected.uncompressedBytes()) {
                        throw new IllegalArgumentException(label + "-invalid");
                    }
                    if (classBytes != null
                            && classEntries.putIfAbsent(name, classBytes.toByteArray()) != null) {
                        throw new IllegalArgumentException(label + "-duplicate-entry:" + name);
                    }
                    input.closeEntry();
                }
            }
            if (extracted.size() != central.size()) {
                throw new IllegalArgumentException(label + "-invalid");
            }
            return classEntries;
        }

        private static Map<String, CentralEntry> readCentralDirectory(
                String label, byte[] archive, JarLimits limits) {
            int eocd = findEndOfCentralDirectory(archive);
            if (eocd < 0) throw new IllegalArgumentException(label + "-invalid");
            int disk = u16(archive, eocd + 4);
            int centralDisk = u16(archive, eocd + 6);
            int entriesOnDisk = u16(archive, eocd + 8);
            int entryCount = u16(archive, eocd + 10);
            long centralBytes = u32(archive, eocd + 12);
            long centralOffset = u32(archive, eocd + 16);
            if (disk != 0 || centralDisk != 0 || entriesOnDisk != entryCount
                    || entryCount < 1 || entryCount > limits.entries()
                    || centralOffset + centralBytes != eocd
                    || centralOffset > Integer.MAX_VALUE) {
                String suffix = entryCount > limits.entries()
                        ? "-entry-count-exceeded" : "-invalid";
                throw new IllegalArgumentException(label + suffix);
            }

            var result = new LinkedHashMap<String, CentralEntry>();
            int cursor = (int) centralOffset;
            long declaredTotal = 0;
            for (int index = 0; index < entryCount; index++) {
                requireRange(archive, cursor, 46, label);
                if (u32(archive, cursor) != 0x02014b50L) {
                    throw new IllegalArgumentException(label + "-invalid");
                }
                int flags = u16(archive, cursor + 8);
                int method = u16(archive, cursor + 10);
                long compressedBytes = u32(archive, cursor + 20);
                long uncompressedBytes = u32(archive, cursor + 24);
                int nameBytes = u16(archive, cursor + 28);
                int extraBytes = u16(archive, cursor + 30);
                int commentBytes = u16(archive, cursor + 32);
                int startingDisk = u16(archive, cursor + 34);
                long localOffset = u32(archive, cursor + 42);
                long recordBytes = 46L + nameBytes + extraBytes + commentBytes;
                if ((flags & 1) != 0 || (method != 0 && method != 8)
                        || compressedBytes == 0xffff_ffffL || uncompressedBytes == 0xffff_ffffL
                        || localOffset == 0xffff_ffffL || startingDisk != 0
                        || recordBytes > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(label + "-invalid");
                }
                requireRange(archive, cursor, (int) recordBytes, label);
                String name = new String(
                        archive, cursor + 46, nameBytes, StandardCharsets.UTF_8);
                validateEntryPath(label, name);
                boolean directory = name.endsWith("/");
                if (uncompressedBytes > limits.entryBytes()) {
                    throw new IllegalArgumentException(label + "-entry-too-large");
                }
                declaredTotal += uncompressedBytes;
                if (declaredTotal > limits.totalBytes()) {
                    throw new IllegalArgumentException(label + "-uncompressed-total-exceeded");
                }
                if (result.putIfAbsent(name,
                        new CentralEntry(name, uncompressedBytes, directory)) != null) {
                    throw new IllegalArgumentException(label + "-duplicate-entry:" + name);
                }
                cursor += (int) recordBytes;
            }
            if (cursor != eocd) throw new IllegalArgumentException(label + "-invalid");
            return result;
        }

        private static int findEndOfCentralDirectory(byte[] archive) {
            int minimum = Math.max(0, archive.length - 65_557);
            for (int cursor = archive.length - 22; cursor >= minimum; cursor--) {
                if (u32Unchecked(archive, cursor) == 0x06054b50L) {
                    int commentBytes = u16(archive, cursor + 20);
                    if (cursor + 22 + commentBytes == archive.length) return cursor;
                }
            }
            return -1;
        }

        private static void validateEntryPath(String label, String name) {
            if (name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0
                    || name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
                throw new IllegalArgumentException(label + "-entry-path-unsafe");
            }
            String normalized = name.endsWith("/")
                    ? name.substring(0, name.length() - 1) : name;
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(label + "-entry-path-unsafe");
            }
            for (String segment : normalized.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException(label + "-entry-path-unsafe");
                }
            }
        }

        private static void requireRange(byte[] bytes, int offset, int length, String label) {
            if (offset < 0 || length < 0 || offset > bytes.length - length) {
                throw new IllegalArgumentException(label + "-invalid");
            }
        }

        private static int u16(byte[] bytes, int offset) {
            if (offset < 0 || offset > bytes.length - 2) return -1;
            return Byte.toUnsignedInt(bytes[offset])
                    | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
        }

        private static long u32(byte[] bytes, int offset) {
            if (offset < 0 || offset > bytes.length - 4) return -1;
            return u32Unchecked(bytes, offset);
        }

        private static long u32Unchecked(byte[] bytes, int offset) {
            if (offset < 0 || offset > bytes.length - 4) return -1;
            return Integer.toUnsignedLong(Byte.toUnsignedInt(bytes[offset])
                    | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
                    | (Byte.toUnsignedInt(bytes[offset + 2]) << 16)
                    | (Byte.toUnsignedInt(bytes[offset + 3]) << 24));
        }

        String classHash(String owner) throws IOException { return sha256(classBytes(owner)); }

        Member member(String owner, String name, boolean method, boolean expectedStatic,
                String expectedDescriptor) throws IOException {
            ClassModel model = model(owner);
            List<Member> candidates = (method ? model.methods() : model.fields()).stream()
                    .filter(value -> value.name().equals(name))
                    .filter(value -> expectedDescriptor == null
                            || value.descriptor().equals(expectedDescriptor))
                    .filter(value -> ((value.access() & Opcodes.ACC_STATIC) != 0) == expectedStatic)
                    .toList();
            if (candidates.size() != 1) {
                throw new IllegalArgumentException("production-member-not-exact:" + owner + "#" + name);
            }
            return candidates.getFirst();
        }

        void requireDirectParent(String child, String parent) throws IOException {
            if (!model(child).parents().contains(parent)) {
                throw new IllegalArgumentException("production-variant-hierarchy-mismatch:" + child);
            }
        }

        JarResourceLoader resourceLoader(ClassLoader parent) {
            return new JarResourceLoader(parent, entries);
        }

        private ClassModel model(String owner) throws IOException {
            ClassModel cached = models.get(owner);
            if (cached != null) return cached;
            var fields = new ArrayList<Member>();
            var methods = new ArrayList<Member>();
            var parents = new ArrayList<String>();
            new ClassReader(classBytes(owner)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visit(int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    if (superName != null) parents.add(superName.replace('/', '.'));
                    if (interfaces != null) for (String value : interfaces) {
                        parents.add(value.replace('/', '.'));
                    }
                }

                @Override public FieldVisitor visitField(int access, String name,
                        String descriptor, String signature, Object value) {
                    fields.add(new Member(owner, name, descriptor, access));
                    return null;
                }

                @Override public MethodVisitor visitMethod(int access, String name,
                        String descriptor, String signature, String[] exceptions) {
                    methods.add(new Member(owner, name, descriptor, access));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            ClassModel created = new ClassModel(List.copyOf(fields), List.copyOf(methods),
                    Set.copyOf(parents));
            models.put(owner, created);
            return created;
        }

        private byte[] classBytes(String owner) throws IOException {
            String entry = owner.replace('.', '/') + ".class";
            byte[] bytes = entries.get(entry);
            if (bytes == null) throw new IllegalArgumentException("production-class-missing:" + owner);
            return bytes;
        }

    }

    private record ClassModel(List<Member> fields, List<Member> methods, Set<String> parents) {}
}
