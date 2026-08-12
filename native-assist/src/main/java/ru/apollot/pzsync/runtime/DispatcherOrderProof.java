package ru.apollot.pzsync.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Read-only bytecode proof; the dispatcher itself is never transformed. */
final class DispatcherOrderProof {
    private static final String METHOD = "onServerPacket";
    private static final String DESCRIPTOR =
            "(Lzombie/core/network/ByteBufferReader;"
                    + "Lzombie/core/raknet/UdpConnection;)V";
    private static final int ACCESS_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE
            | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_BRIDGE | Opcodes.ACC_VARARGS
            | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_STRICT
            | Opcodes.ACC_SYNTHETIC;

    private DispatcherOrderProof() {}

    static boolean verifies(byte[] bytes, String expectedSha256) {
        if (bytes == null) {
            return false;
        }
        String owner = new ClassReader(bytes).getClassName().replace('/', '.');
        return verifies(bytes, owner, METHOD, DESCRIPTOR, Opcodes.ACC_PUBLIC, expectedSha256);
    }

    static boolean verifies(
            byte[] bytes,
            String expectedOwner,
            String expectedMember,
            String expectedDescriptor,
            int expectedAccess,
            String expectedSha256) {
        if (bytes == null
                || expectedOwner == null
                || expectedMember == null
                || expectedDescriptor == null
                || expectedSha256 == null
                || !expectedSha256.equals(sha256(bytes))) {
            return false;
        }
        ClassReader reader = new ClassReader(bytes);
        if (!expectedOwner.equals(reader.getClassName().replace('/', '.'))) {
            return false;
        }
        int[] methods = {0};
        boolean[] accessMatches = {false};
        List<Integer> stages = new ArrayList<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions) {
                if (!expectedMember.equals(name) || !expectedDescriptor.equals(descriptor)) {
                    return null;
                }
                methods[0]++;
                accessMatches[0] = (access & ACCESS_MASK) == expectedAccess
                        && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String member,
                            String memberDescriptor,
                            boolean isInterface) {
                        int stage = stage(owner, member, memberDescriptor);
                        if (stage >= 0) {
                            stages.add(stage);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return methods[0] == 1
                && accessMatches[0]
                && stages.equals(List.of(0, 1, 2, 3));
    }

    private static int stage(String owner, String member, String descriptor) {
        if ("zombie/network/packets/INetworkPacket".equals(owner)
                && "parseServer".equals(member)
                && "(Lzombie/core/network/ByteBufferReader;"
                        .concat("Lzombie/core/raknet/UdpConnection;)V")
                        .equals(descriptor)) {
            return 0;
        }
        if ("zombie/network/packets/INetworkPacket".equals(owner)
                && "isConsistent".equals(member)
                && "(Lzombie/network/IConnection;)Z".equals(descriptor)) {
            return 1;
        }
        if ("zombie/network/anticheats/AntiCheat".equals(owner)
                && "isValid".equals(member)
                && "(Lzombie/core/raknet/UdpConnection;"
                        .concat("Lzombie/network/packets/INetworkPacket;)Z")
                        .equals(descriptor)) {
            return 2;
        }
        if ("zombie/network/packets/INetworkPacket".equals(owner)
                && "processServer".equals(member)
                && "(Lzombie/network/PacketTypes$PacketType;"
                        .concat("Lzombie/core/raknet/UdpConnection;)V")
                        .equals(descriptor)) {
            return 3;
        }
        return -1;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
