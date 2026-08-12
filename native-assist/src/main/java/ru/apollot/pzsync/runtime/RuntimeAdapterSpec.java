package ru.apollot.pzsync.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import ru.apollot.pzsync.gate.RuntimeFingerprint;
import ru.apollot.pzsync.hooks.HookDescriptor;

/**
 * Closed, data-only description of the one supported Project Zomboid runtime.
 * It intentionally exposes no general reflection or arbitrary invocation API.
 */
public final class RuntimeAdapterSpec {
    public static final String EXACT_APP_ID = "380870";
    public static final String EXACT_BUILD_ID = "24574884";
    public static final String EXACT_GAME_VERSION = "42.20.2";
    public static final int MAX_CHAIN_DEPTH = 4;
    private static final String ADAPTER_PREFIX = "RUNTIME_ADAPTER|";
    private static final String HOOK_PREFIX = "ADAPTER_HOOK|";
    private static final String VARIANT_PREFIX = "ADAPTER_VARIANT|";
    private static final String VARIANT_PROOF_PREFIX = "ADAPTER_VARIANT_PROOF|";
    private static final String CHAIN_PREFIX = "ADAPTER_CHAIN|";
    private static final String CAPABILITY_PREFIX = "ADAPTER_CAPABILITY|";
    private static final String SUPPORT_PREFIX = "ADAPTER_SUPPORT|";
    private static final String SUPPORT_PROOF_PREFIX = "ADAPTER_SUPPORT_PROOF|";
    private static final String PROOF_PREFIX = "ADAPTER_PROOF|";
    private static final String FACTORY_PREFIX = "RUNTIME_BINDINGS_FACTORY|";
    private static final String FACTORY_CLASS =
            "ru.apollot.pzsync.hooks.ExactRuntimeBindingsFactory";
    private static final String RTT_OWNER = "zombie.core.raknet.UdpConnection";
    private static final String RTT_MEMBER = "getAveragePing";
    private static final String RTT_DESCRIPTOR = "()I";
    private static final String RTT_HIT_DESCRIPTOR =
            "(Lzombie/network/PacketTypes$PacketType;"
                    + "Lzombie/core/raknet/UdpConnection;)V";
    private static final int RTT_ACCESS = Opcodes.ACC_PUBLIC;
    private static final Pattern BINARY_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Pattern MEMBER_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final int MEMBER_ACCESS_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE
            | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_BRIDGE | Opcodes.ACC_VARARGS
            | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_STRICT
            | Opcodes.ACC_SYNTHETIC;

    private final AdapterId id;
    private final Map<HookRole, Hook> hooks;
    private final Map<HitVariant, Variant> variants;
    private final Map<AccessorRole, AccessorChain> chains;
    private final Map<CapabilityRole, Capability> capabilities;
    private final Map<SupportRole, Support> supports;
    private final Map<ProofRole, Proof> proofs;
    private final Map<String, String> ownerHashes;
    private final Set<String> ownerClasses;
    private final String identity;

    private RuntimeAdapterSpec(
            AdapterId id,
            Map<HookRole, Hook> hooks,
            Map<HitVariant, Variant> variants,
            Map<AccessorRole, AccessorChain> chains,
            Map<CapabilityRole, Capability> capabilities,
            Map<SupportRole, Support> supports,
            Map<ProofRole, Proof> proofs,
            Map<String, String> ownerHashes,
            Set<String> ownerClasses,
            String identity) {
        this.id = id;
        this.hooks = Map.copyOf(hooks);
        this.variants = Map.copyOf(variants);
        this.chains = Map.copyOf(chains);
        this.capabilities = Map.copyOf(capabilities);
        this.supports = Map.copyOf(supports);
        this.proofs = Map.copyOf(proofs);
        this.ownerHashes = Map.copyOf(ownerHashes);
        this.ownerClasses = Set.copyOf(ownerClasses);
        this.identity = identity;
    }

    public static boolean isDeclared(RuntimeFingerprint fingerprint) {
        return fingerprint.methodDescriptors().keySet().stream()
                .anyMatch(key -> key.startsWith(ADAPTER_PREFIX));
    }

    /** Selects exact or fixture schema before any factory resolution can fall through. */
    public static void validateSelection(RuntimeFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        boolean declared = isDeclared(fingerprint);
        boolean exactTuple = EXACT_APP_ID.equals(fingerprint.appId())
                && EXACT_BUILD_ID.equals(fingerprint.buildId())
                && EXACT_GAME_VERSION.equals(fingerprint.gameVersionRevision())
                && fingerprint.jvmFeature() == 25
                && "linux".equals(fingerprint.os())
                && "amd64".equals(fingerprint.arch());
        if (declared && !exactTuple) {
            fail("runtime-adapter-tuple-mismatch");
        }
        if (!declared && !syntheticTuple(fingerprint)) {
            fail("runtime-adapter-marker-missing");
        }
    }

    private static boolean syntheticTuple(RuntimeFingerprint fingerprint) {
        return fingerprint.appId().startsWith("fixture-")
                && fingerprint.buildId().startsWith("fixture-")
                && fingerprint.gameVersionRevision().startsWith("fixture-");
    }

    public static RuntimeAdapterSpec requiredFrom(RuntimeFingerprint fingerprint) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        validateSelection(fingerprint);
        var hooks = new EnumMap<HookRole, Hook>(HookRole.class);
        var variants = new EnumMap<HitVariant, Variant>(HitVariant.class);
        var variantProofs = new EnumMap<HitVariant, List<VariantProofEntry>>(HitVariant.class);
        var stepGroups = new EnumMap<AccessorRole, List<StepEntry>>(AccessorRole.class);
        var capabilities = new EnumMap<CapabilityRole, Capability>(CapabilityRole.class);
        var supports = new EnumMap<SupportRole, Support>(SupportRole.class);
        var supportProofs = new EnumMap<SupportRole, List<SupportProofEntry>>(SupportRole.class);
        var proofs = new EnumMap<ProofRole, Proof>(ProofRole.class);
        AdapterId selectedId = null;
        var consumedDescriptors = new HashSet<String>();

        for (var entry : fingerprint.methodDescriptors().entrySet()) {
            String key = entry.getKey();
            String descriptor = entry.getValue();
            if (key.startsWith(ADAPTER_PREFIX)) {
                String encoded = key.substring(ADAPTER_PREFIX.length());
                AdapterId parsed = enumValue(AdapterId.class, encoded, "runtime-adapter-id-unknown");
                if (!"1".equals(descriptor) || selectedId != null) {
                    fail("runtime-adapter-id-duplicate");
                }
                selectedId = parsed;
                consumedDescriptors.add(key);
            } else if (key.startsWith(HOOK_PREFIX)) {
                Hook hook = parseHook(key, descriptor, fingerprint);
                if (hooks.putIfAbsent(hook.role(), hook) != null) {
                    fail("runtime-adapter-hook-duplicate");
                }
                consumedDescriptors.add(key);
            } else if (key.startsWith(VARIANT_PREFIX)) {
                Variant variant = parseVariant(key, descriptor, fingerprint);
                if (variants.putIfAbsent(variant.variant(), variant) != null) {
                    fail("runtime-adapter-variant-duplicate");
                }
                consumedDescriptors.add(key);
            } else if (key.startsWith(VARIANT_PROOF_PREFIX)) {
                VariantProofEntry proof = parseVariantProof(key, descriptor, fingerprint);
                variantProofs.computeIfAbsent(proof.variant(), ignored -> new ArrayList<>()).add(proof);
                consumedDescriptors.add(key);
            } else if (key.startsWith(CHAIN_PREFIX)) {
                StepEntry step = parseStep(key, descriptor, fingerprint);
                stepGroups.computeIfAbsent(step.role(), ignored -> new ArrayList<>()).add(step);
                consumedDescriptors.add(key);
            } else if (key.startsWith(CAPABILITY_PREFIX)) {
                Capability capability = parseCapability(key, descriptor, fingerprint);
                if (capabilities.putIfAbsent(capability.role(), capability) != null) {
                    fail("runtime-adapter-capability-duplicate");
                }
                consumedDescriptors.add(key);
            } else if (key.startsWith(SUPPORT_PREFIX)) {
                Support support = parseSupport(key, descriptor, fingerprint);
                if (supports.putIfAbsent(support.role(), support) != null) {
                    fail("runtime-adapter-support-duplicate");
                }
                consumedDescriptors.add(key);
            } else if (key.startsWith(SUPPORT_PROOF_PREFIX)) {
                SupportProofEntry proof = parseSupportProof(key, descriptor, fingerprint);
                supportProofs.computeIfAbsent(proof.role(), ignored -> new ArrayList<>()).add(proof);
                consumedDescriptors.add(key);
            } else if (key.startsWith(PROOF_PREFIX)) {
                Proof proof = parseProof(key, descriptor, fingerprint);
                if (proofs.putIfAbsent(proof.role(), proof) != null) {
                    fail("runtime-adapter-proof-duplicate");
                }
                consumedDescriptors.add(key);
            } else if (key.startsWith(FACTORY_PREFIX)) {
                consumedDescriptors.add(key);
            }
        }
        if (selectedId == null) {
            fail("runtime-adapter-id-missing");
        }
        if (consumedDescriptors.size() != fingerprint.methodDescriptors().size()) {
            fail("runtime-adapter-entry-unused");
        }
        requireComplete(hooks, HookRole.values(), "runtime-adapter-hook-missing");
        requireComplete(variants, HitVariant.values(), "runtime-adapter-variant-missing");
        for (var entry : variantProofs.entrySet()) {
            Variant current = variants.get(entry.getKey());
            if (current == null) fail("runtime-adapter-variant-missing");
            var hierarchyProofs = new ArrayList<>(entry.getValue());
            hierarchyProofs.sort(Comparator.comparingInt(VariantProofEntry::index));
            var hierarchy = new ArrayList<String>();
            for (int index = 0; index < hierarchyProofs.size(); index++) {
                if (hierarchyProofs.get(index).index() != index) {
                    fail("runtime-adapter-variant-proof-gap");
                }
                hierarchy.add(hierarchyProofs.get(index).ownerClass());
            }
            variants.put(entry.getKey(), new Variant(current.variant(), current.targetKind(),
                    current.ownerClass(), current.ownerSha256(), List.copyOf(hierarchy)));
        }
        requireComplete(capabilities, CapabilityRole.values(), "runtime-adapter-capability-missing");
        if (!supports.isEmpty()) {
            requireComplete(supports, SupportRole.values(), "runtime-adapter-a5-support-missing");
            bindSupportProofs(supports, supportProofs);
        } else if (!supportProofs.isEmpty()) {
            fail("runtime-adapter-support-proof-without-support");
        }
        requireComplete(proofs, ProofRole.values(), "runtime-adapter-dispatcher-proof-missing");

        var chains = new EnumMap<AccessorRole, AccessorChain>(AccessorRole.class);
        for (var entry : stepGroups.entrySet()) {
            chains.put(entry.getKey(), buildChain(
                    entry.getKey(), entry.getValue(), hooks, variants));
        }
        if (chains.isEmpty()) {
            fail("runtime-adapter-chain-missing");
        }
        if (!chains.containsKey(AccessorRole.CONNECTION_AVERAGE_PING)) {
            fail("runtime-adapter-rtt-accessor-missing");
        }

        var memberRoles = new HashMap<String, String>();
        hooks.values().forEach(value -> claimMember(memberRoles, value.memberIdentity(),
                "HOOK:" + value.role()));
        chains.values().forEach(chain -> chain.steps().forEach(value -> claimChainMember(
                memberRoles, value.memberIdentity(), chain.role())));
        capabilities.values().forEach(value -> claimMember(memberRoles, value.memberIdentity(),
                "CAPABILITY:" + value.role()));
        supports.values().forEach(value -> claimMember(memberRoles, value.memberIdentity(),
                "SUPPORT:" + value.role()));
        proofs.values().forEach(value -> claimMember(memberRoles, value.memberIdentity(),
                "PROOF:" + value.role()));

        var owners = new LinkedHashSet<String>();
        hooks.values().forEach(value -> owners.add(value.ownerClass()));
        variants.values().forEach(value -> {
            owners.add(value.ownerClass());
            owners.addAll(value.hierarchyProof());
        });
        chains.values().forEach(value -> value.steps().forEach(step -> owners.add(step.ownerClass())));
        capabilities.values().forEach(value -> owners.add(value.ownerClass()));
        supports.values().forEach(value -> {
            owners.add(value.ownerClass());
            owners.addAll(value.receiverHierarchyProof());
        });
        proofs.values().forEach(value -> owners.add(value.ownerClass()));
        for (String owner : owners) {
            if (!fingerprint.classHashes().containsKey(owner)) {
                fail("runtime-adapter-owner-hash-missing");
            }
        }
        var allowedHashes = new HashSet<>(owners);
        if (fingerprint.methodDescriptors().keySet().stream().anyMatch(key -> key.startsWith(FACTORY_PREFIX))) {
            allowedHashes.add(FACTORY_CLASS);
        }
        if (!allowedHashes.equals(fingerprint.classHashes().keySet())) {
            fail("runtime-adapter-class-hash-unused");
        }

        String identity = canonicalIdentity(
                selectedId, hooks, variants, chains, capabilities, supports, proofs,
                fingerprint.classHashes());
        return new RuntimeAdapterSpec(
                selectedId, hooks, variants, chains, capabilities, supports, proofs,
                fingerprint.classHashes(), owners, identity);
    }

    public AdapterId id() { return id; }
    public Map<HookRole, Hook> hooks() { return hooks; }
    public Map<HitVariant, Variant> variants() { return variants; }
    public Map<AccessorRole, AccessorChain> chains() { return chains; }
    public Map<CapabilityRole, Capability> capabilities() { return capabilities; }
    public Map<SupportRole, Support> supports() { return supports; }
    public Map<ProofRole, Proof> proofs() { return proofs; }
    public Set<String> ownerClasses() { return ownerClasses; }
    public String identity() { return identity; }
    public String ownerSha256(String ownerClass) {
        String value = ownerHashes.get(ownerClass);
        if (value == null) fail("runtime-adapter-owner-hash-missing");
        return value;
    }

    public List<HookDescriptor> hookDescriptors() {
        var result = new ArrayList<HookDescriptor>();
        for (HookRole role : HookRole.values()) {
            Hook hook = hooks.get(role);
            HookDescriptor.HookPoint point = switch (role) {
                case PLAYER_ACCEPTED -> HookDescriptor.HookPoint.PLAYER_STATE;
                case ATTACK_ACCEPTED -> HookDescriptor.HookPoint.ATTACK_OPEN;
                case HIT_PRE_APPLY -> HookDescriptor.HookPoint.HIT_GATE;
                case ZOMBIE_APPLY -> HookDescriptor.HookPoint.ZOMBIE_STATE;
                case ZOMBIE_OWNER -> HookDescriptor.HookPoint.ZOMBIE_OWNERSHIP;
                case LUA_INIT -> HookDescriptor.HookPoint.BRIDGE_PUBLISH;
            };
            HookDescriptor.ReturnPolicy policy = switch (hook.returnPolicy()) {
                case VOID_SUCCESS -> HookDescriptor.ReturnPolicy.VOID_SUCCESS;
                case PREVALIDATED_VOID -> HookDescriptor.ReturnPolicy.PREVALIDATED_VOID;
            };
            result.add(new HookDescriptor(point, policy, hook.ownerClass(), hook.ownerSha256(),
                    hook.memberName(), hook.descriptor()));
        }
        return List.copyOf(result);
    }

    /** Returns a sanitized reason or {@code null}; it never initializes a target class. */
    public String preflightFailure(ClassLoader targetLoader) {
        try {
            preflight(targetLoader);
            return null;
        } catch (RuntimeAdapterException error) {
            return error.reasonCode();
        }
    }

    public void preflight(ClassLoader targetLoader) {
        Objects.requireNonNull(targetLoader, "targetLoader");
        var bytesByOwner = new LinkedHashMap<String, byte[]>();
        for (String owner : ownerClasses) {
            byte[] bytes = readClass(targetLoader, owner);
            if (bytes == null) fail("runtime-adapter-class-missing");
            if (!ownerHashes.get(owner).equals(sha256(bytes))) {
                fail("runtime-adapter-class-hash-mismatch");
            }
            bytesByOwner.put(owner, bytes);
        }
        hooks.values().forEach(value -> verifyHook(bytesByOwner.get(value.ownerClass()), value));
        chains.values().forEach(chain -> chain.steps().forEach(
                value -> verifyStep(bytesByOwner.get(value.ownerClass()), value)));
        capabilities.values().forEach(
                value -> verifyCapability(bytesByOwner.get(value.ownerClass()), value));
        verifyLuaCallableTypes(bytesByOwner);
        supports.values().forEach(value -> verifySupport(bytesByOwner, value));
        proofs.values().forEach(
                value -> verifyProof(bytesByOwner.get(value.ownerClass()), value));
        variants.values().forEach(value -> verifyVariant(
                bytesByOwner.get(value.ownerClass()), value, hooks.get(HookRole.HIT_PRE_APPLY),
                targetLoader));
    }

    /**
     * Confirms post-definition identity without initializing classes. HookInstaller calls this
     * only after every transform has been atomically claimed.
     */
    public void verifyDefinitions(
            ClassLoader targetLoader,
            ProtectionDomain expectedDomain,
            CodeSource expectedCodeSource) {
        for (String owner : ownerClasses) {
            try {
                Class<?> resolved = Class.forName(owner, false, targetLoader);
                if (resolved.getClassLoader() != targetLoader
                        || resolved.getProtectionDomain() != expectedDomain
                        || !Objects.equals(resolved.getProtectionDomain().getCodeSource(), expectedCodeSource)) {
                    fail("runtime-adapter-definition-identity-mismatch");
                }
            } catch (ClassNotFoundException | LinkageError error) {
                fail("runtime-adapter-definition-missing");
            }
        }
        for (Proof proof : proofs.values()) {
            byte[] bytes = readClass(targetLoader, proof.ownerClass());
            if (bytes == null || !ownerHashes.get(proof.ownerClass()).equals(sha256(bytes))) {
                fail("runtime-adapter-dispatcher-proof-hash-mismatch");
            }
            verifyProof(bytes, proof);
        }
    }

    private static Hook parseHook(String key, String descriptor, RuntimeFingerprint fingerprint) {
        String[] parts = key.split("\\|", 6);
        if (parts.length != 6) fail("runtime-adapter-key-unknown");
        HookRole role = enumValue(HookRole.class, parts[1], "runtime-adapter-key-unknown");
        ReturnPolicy policy = enumValue(ReturnPolicy.class, parts[2], "runtime-adapter-key-unknown");
        Invocation invocation = enumValue(Invocation.class, parts[3], "runtime-adapter-key-unknown");
        if (role.policy != policy || role.invocation != invocation) {
            fail("runtime-adapter-hook-modifier-mismatch");
        }
        int exactAccess = parseAccess(parts[4]);
        Member member = parseMember(parts[5], descriptor, fingerprint);
        Type method = methodType(descriptor, "runtime-adapter-hook-descriptor-mismatch");
        if (!policy.matches(method.getReturnType())) fail("runtime-adapter-hook-descriptor-mismatch");
        return new Hook(role, policy, invocation, exactAccess, member.owner(), member.name(), descriptor,
                member.ownerHash());
    }

    private static Variant parseVariant(String key, String descriptor, RuntimeFingerprint fingerprint) {
        String[] parts = key.split("\\|", 4);
        if (parts.length != 4 || !"CLASS".equals(descriptor)) fail("runtime-adapter-key-unknown");
        HitVariant variant = enumValue(HitVariant.class, parts[1], "runtime-adapter-key-unknown");
        TargetKind target = enumValue(TargetKind.class, parts[2], "runtime-adapter-key-unknown");
        if (variant.targetKind != target || !BINARY_NAME.matcher(parts[3]).matches()) {
            fail("runtime-adapter-variant-mismatch");
        }
        String hash = fingerprint.classHashes().get(parts[3]);
        if (hash == null) fail("runtime-adapter-owner-hash-missing");
        return new Variant(variant, target, parts[3], hash, List.of());
    }

    private static VariantProofEntry parseVariantProof(
            String key, String descriptor, RuntimeFingerprint fingerprint) {
        String[] parts = key.split("\\|", 4);
        if (parts.length != 4 || !"CLASS".equals(descriptor)) fail("runtime-adapter-key-unknown");
        HitVariant variant = enumValue(HitVariant.class, parts[1], "runtime-adapter-key-unknown");
        int index;
        try { index = Integer.parseInt(parts[2]); }
        catch (NumberFormatException error) { throw new RuntimeAdapterException("runtime-adapter-variant-proof-gap"); }
        if (index < 0 || index >= MAX_CHAIN_DEPTH || !BINARY_NAME.matcher(parts[3]).matches()) {
            fail("runtime-adapter-variant-proof-gap");
        }
        if (!fingerprint.classHashes().containsKey(parts[3])) {
            fail("runtime-adapter-owner-hash-missing");
        }
        return new VariantProofEntry(variant, index, parts[3]);
    }

    private static StepEntry parseStep(String key, String descriptor, RuntimeFingerprint fingerprint) {
        String[] parts = key.split("\\|", 7);
        if (parts.length != 7) fail("runtime-adapter-key-unknown");
        AccessorRole role = enumValue(AccessorRole.class, parts[1], "runtime-adapter-key-unknown");
        Source source = enumValue(Source.class, parts[2], "runtime-adapter-key-unknown");
        int index;
        try { index = Integer.parseInt(parts[3]); }
        catch (NumberFormatException error) { throw new RuntimeAdapterException("runtime-adapter-chain-depth"); }
        if (index < 0 || index >= MAX_CHAIN_DEPTH) fail("runtime-adapter-chain-depth");
        AccessorKind kind = enumValue(AccessorKind.class, parts[4], "runtime-adapter-key-unknown");
        if (kind.isStatic() != (source == Source.STATIC && index == 0)
                && (kind.isStatic() || source == Source.STATIC)) {
            fail("runtime-adapter-chain-static-context");
        }
        int exactAccess = parseAccess(parts[5]);
        Member member = parseMember(parts[6], descriptor, fingerprint);
        return new StepEntry(role, source, index, kind, exactAccess,
                member.owner(), member.name(), descriptor,
                member.ownerHash());
    }

    private static Capability parseCapability(
            String key, String descriptor, RuntimeFingerprint fingerprint) {
        String[] parts = key.split("\\|", 5);
        if (parts.length != 5) fail("runtime-adapter-key-unknown");
        CapabilityRole role = enumValue(CapabilityRole.class, parts[1], "runtime-adapter-key-unknown");
        Invocation invocation = enumValue(Invocation.class, parts[2], "runtime-adapter-key-unknown");
        int exactAccess = parseAccess(parts[3]);
        Member member = parseMember(parts[4], descriptor, fingerprint);
        if (role.invocation != invocation) {
            fail("runtime-adapter-capability-descriptor-mismatch");
        }
        if (!role.ownerClass.equals(member.owner()) || !role.memberName.equals(member.name())) {
            fail("runtime-adapter-capability-owner-mismatch");
        }
        if (!role.descriptor.equals(descriptor)) {
            fail("runtime-adapter-capability-descriptor-mismatch");
        }
        if (role.exactAccess != exactAccess) {
            fail("runtime-adapter-capability-access-mismatch");
        }
        return new Capability(role, invocation, exactAccess,
                member.owner(), member.name(), descriptor,
                member.ownerHash());
    }

    private static Support parseSupport(
            String key, String descriptor, RuntimeFingerprint fingerprint) {
        String[] parts = key.split("\\|", 5);
        if (parts.length != 5) fail("runtime-adapter-support-key-invalid");
        SupportRole role = enumValue(
                SupportRole.class, parts[1], "runtime-adapter-support-key-invalid");
        Invocation invocation = enumValue(
                Invocation.class, parts[2], "runtime-adapter-support-key-invalid");
        int exactAccess = parseAccess(parts[3]);
        Member member = parseMember(parts[4], descriptor, fingerprint);
        if (role.invocation != invocation
                || role.exactAccess != exactAccess
                || !role.ownerClass.equals(member.owner())
                || !role.memberName.equals(member.name())
                || !role.descriptor.equals(descriptor)) {
            fail("runtime-adapter-support-mismatch");
        }
        return new Support(
                role, invocation, exactAccess, member.owner(), member.name(), descriptor,
                member.ownerHash(), List.of());
    }

    private static SupportProofEntry parseSupportProof(
            String key, String descriptor, RuntimeFingerprint fingerprint) {
        String[] parts = key.split("\\|", 4);
        if (parts.length != 4 || !"CLASS".equals(descriptor)) {
            fail("runtime-adapter-support-proof-invalid");
        }
        SupportRole role = enumValue(
                SupportRole.class, parts[1], "runtime-adapter-support-proof-invalid");
        int index;
        try {
            index = Integer.parseInt(parts[2]);
        } catch (NumberFormatException error) {
            throw new RuntimeAdapterException("runtime-adapter-support-proof-gap");
        }
        if (index < 0 || index >= MAX_CHAIN_DEPTH
                || !BINARY_NAME.matcher(parts[3]).matches()) {
            fail("runtime-adapter-support-proof-gap");
        }
        if (!fingerprint.classHashes().containsKey(parts[3])) {
            fail("runtime-adapter-owner-hash-missing");
        }
        return new SupportProofEntry(role, index, parts[3]);
    }

    private static void bindSupportProofs(
            EnumMap<SupportRole, Support> supports,
            EnumMap<SupportRole, List<SupportProofEntry>> proofs) {
        for (SupportRole role : SupportRole.values()) {
            List<SupportProofEntry> unordered = proofs.getOrDefault(role, List.of());
            var ordered = new ArrayList<>(unordered);
            ordered.sort(Comparator.comparingInt(SupportProofEntry::index));
            var hierarchy = new ArrayList<String>(ordered.size());
            for (int index = 0; index < ordered.size(); index++) {
                if (ordered.get(index).index() != index) {
                    fail("runtime-adapter-support-proof-gap");
                }
                hierarchy.add(ordered.get(index).ownerClass());
            }
            if (!hierarchy.equals(role.receiverHierarchyProof)) {
                fail(hierarchy.isEmpty()
                        ? "runtime-adapter-support-proof-missing"
                        : "runtime-adapter-support-proof-mismatch");
            }
            Support support = supports.get(role);
            supports.put(role, new Support(
                    support.role(), support.invocation(), support.exactAccess(),
                    support.ownerClass(), support.memberName(), support.descriptor(),
                    support.ownerSha256(), hierarchy));
        }
        if (!proofs.keySet().stream().allMatch(supports::containsKey)) {
            fail("runtime-adapter-support-proof-without-support");
        }
    }

    private static Proof parseProof(
            String key, String descriptor, RuntimeFingerprint fingerprint) {
        String[] parts = key.split("\\|", 4);
        if (parts.length != 4) fail("runtime-adapter-proof-key-invalid");
        ProofRole role = enumValue(
                ProofRole.class, parts[1], "runtime-adapter-proof-key-invalid");
        int exactAccess = parseAccess(parts[2]);
        Member member = parseMember(parts[3], descriptor, fingerprint);
        if (!role.ownerClass.equals(member.owner())
                || !role.memberName.equals(member.name())
                || !role.descriptor.equals(descriptor)
                || role.exactAccess != exactAccess) {
            fail("runtime-adapter-dispatcher-proof-mismatch");
        }
        return new Proof(
                role,
                exactAccess,
                member.owner(),
                member.name(),
                descriptor,
                member.ownerHash());
    }

    private static Member parseMember(
            String encoded, String descriptor, RuntimeFingerprint fingerprint) {
        int separator = encoded.lastIndexOf('#');
        if (separator <= 0 || separator == encoded.length() - 1) {
            fail("runtime-adapter-key-unknown");
        }
        String owner = encoded.substring(0, separator);
        String member = encoded.substring(separator + 1);
        if (!BINARY_NAME.matcher(owner).matches() || !MEMBER_NAME.matcher(member).matches()) {
            fail("runtime-adapter-key-unknown");
        }
        String hash = fingerprint.classHashes().get(owner);
        if (hash == null) fail("runtime-adapter-owner-hash-missing");
        return new Member(owner, member, descriptor, hash);
    }

    private static AccessorChain buildChain(
            AccessorRole role,
            List<StepEntry> unordered,
            Map<HookRole, Hook> hooks,
            Map<HitVariant, Variant> variants) {
        var entries = new ArrayList<>(unordered);
        entries.sort(Comparator.comparingInt(StepEntry::index));
        if (role == AccessorRole.CONNECTION_AVERAGE_PING) {
            verifyExactRttChain(entries, hooks);
        }
        Source source = entries.getFirst().source();
        for (int index = 0; index < entries.size(); index++) {
            StepEntry step = entries.get(index);
            if (step.index() != index || step.source() != source) {
                fail("runtime-adapter-chain-index-gap");
            }
        }
        String expectedOwner = initialOwner(role, source, hooks);
        if (source == Source.THIS && role.hitVariant != null) {
            Variant variant = variants.get(role.hitVariant);
            if (variant == null) fail("runtime-adapter-variant-missing");
            String declaredRoot = entries.getFirst().ownerClass();
            if (!declaredRoot.equals(variant.ownerClass())
                    && !variant.hierarchyProof().contains(declaredRoot)) {
                fail("runtime-adapter-a5-chain-root-mismatch");
            }
            expectedOwner = declaredRoot;
        }
        var steps = new ArrayList<AccessorStep>(entries.size());
        for (StepEntry entry : entries) {
            if (!entry.kind().isStatic() && !entry.ownerClass().equals(expectedOwner)) {
                fail("runtime-adapter-chain-descriptor-discontinuity");
            }
            Type output = accessorOutput(entry.kind(), entry.descriptor());
            if (entry.kind().isMethod()
                    && Type.getMethodType(entry.descriptor()).getArgumentTypes().length != 0) {
                fail("runtime-adapter-chain-method-arguments");
            }
            steps.add(new AccessorStep(entry.index(), entry.kind(), entry.exactAccess(), entry.ownerClass(),
                    entry.memberName(), entry.descriptor(), entry.ownerSha256()));
            expectedOwner = referenceName(output);
            if (entry.index() + 1 < entries.size() && expectedOwner == null) {
                fail("runtime-adapter-chain-descriptor-discontinuity");
            }
        }
        return new AccessorChain(role, source, List.copyOf(steps));
    }

    private static void verifyExactRttChain(
            List<StepEntry> entries, Map<HookRole, Hook> hooks) {
        Hook hit = hooks.get(HookRole.HIT_PRE_APPLY);
        if (entries.size() != 1
                || entries.getFirst().source() != Source.ARG1
                || entries.getFirst().index() != 0
                || entries.getFirst().kind() != AccessorKind.VIRTUAL0
                || entries.getFirst().exactAccess() != RTT_ACCESS
                || !RTT_OWNER.equals(entries.getFirst().ownerClass())
                || !RTT_MEMBER.equals(entries.getFirst().memberName())
                || !RTT_DESCRIPTOR.equals(entries.getFirst().descriptor())
                || !RTT_HIT_DESCRIPTOR.equals(hit.descriptor())) {
            fail("runtime-adapter-rtt-contract-mismatch");
        }
    }

    private static String initialOwner(
            AccessorRole role, Source source, Map<HookRole, Hook> hooks) {
        Hook hook = hooks.get(role.hookRole);
        if (source == Source.THIS) return hook.ownerClass();
        if (source == Source.STATIC) return null;
        Type[] arguments = Type.getMethodType(hook.descriptor()).getArgumentTypes();
        int index = source.argumentIndex;
        if (index < 0 || index >= arguments.length || arguments[index].getSort() != Type.OBJECT) {
            fail("runtime-adapter-chain-source-mismatch");
        }
        return arguments[index].getClassName();
    }

    private static Type accessorOutput(AccessorKind kind, String descriptor) {
        try {
            return kind.isMethod()
                    ? Type.getMethodType(descriptor).getReturnType()
                    : Type.getType(descriptor);
        } catch (IllegalArgumentException error) {
            throw new RuntimeAdapterException("runtime-adapter-chain-descriptor-mismatch");
        }
    }

    private static String referenceName(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT -> type.getClassName();
            case Type.ARRAY -> type.getDescriptor();
            default -> null;
        };
    }

    private static void verifyHook(byte[] bytes, Hook hook) {
        MemberMatch match = findMember(bytes, hook.memberName(), hook.descriptor(), true);
        boolean staticMember = (match.access & Opcodes.ACC_STATIC) != 0;
        if (match.count != 1 || (match.access & MEMBER_ACCESS_MASK) != hook.exactAccess()
                || staticMember != (hook.invocation() == Invocation.STATIC)
                || (match.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            fail("runtime-adapter-hook-modifier-mismatch");
        }
    }

    private static void verifyStep(byte[] bytes, AccessorStep step) {
        boolean method = step.kind().isMethod();
        MemberMatch match = findMember(bytes, step.memberName(), step.descriptor(), method);
        boolean staticMember = (match.access & Opcodes.ACC_STATIC) != 0;
        if (match.count != 1 || (match.access & MEMBER_ACCESS_MASK) != step.exactAccess()
                || staticMember != step.kind().isStatic()
                || (method && (match.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)) {
            fail("runtime-adapter-chain-member-mismatch");
        }
    }

    private static void verifyCapability(byte[] bytes, Capability capability) {
        MemberMatch match = findMember(bytes, capability.memberName(), capability.descriptor(), true);
        boolean staticMember = (match.access & Opcodes.ACC_STATIC) != 0;
        if (match.count != 1
                || (match.access & MEMBER_ACCESS_MASK) != capability.exactAccess()
                || staticMember != (capability.invocation() == Invocation.STATIC)
                || (match.access & Opcodes.ACC_NATIVE) != 0
                || ((match.access & Opcodes.ACC_ABSTRACT) != 0
                        && capability.role() != CapabilityRole.LUA_RAWSET
                        && capability.role() != CapabilityRole.LUA_RAWGET
                        && capability.role() != CapabilityRole.LUA_JAVA_FUNCTION_CALL)) {
            fail("runtime-adapter-capability-member-mismatch");
        }
    }

    private static void verifyLuaCallableTypes(Map<String, byte[]> bytesByOwner) {
        ClassReader function = new ClassReader(
                bytesByOwner.get(CapabilityRole.LUA_JAVA_FUNCTION_CALL.ownerClass));
        if (!"se/krka/kahlua/vm/JavaFunction".equals(function.getClassName())
                || !"java/lang/Object".equals(function.getSuperName())
                || function.getInterfaces().length != 0
                || function.getAccess() != (Opcodes.ACC_PUBLIC
                        | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) {
            fail("runtime-adapter-lua-callable-type-mismatch");
        }
        ClassReader frame = new ClassReader(
                bytesByOwner.get(CapabilityRole.LUA_CALL_FRAME_GET.ownerClass));
        if (!"se/krka/kahlua/vm/LuaCallFrame".equals(frame.getClassName())
                || !"java/lang/Object".equals(frame.getSuperName())
                || frame.getInterfaces().length != 0
                || frame.getAccess() != (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL
                        | Opcodes.ACC_SUPER)) {
            fail("runtime-adapter-lua-callable-type-mismatch");
        }
    }

    private static void verifySupport(Map<String, byte[]> bytesByOwner, Support support) {
        byte[] bytes = bytesByOwner.get(support.ownerClass());
        MemberMatch match = findMember(bytes, support.memberName(), support.descriptor(), true);
        boolean staticMember = (match.access & Opcodes.ACC_STATIC) != 0;
        if (match.count != 1
                || (match.access & MEMBER_ACCESS_MASK) != support.exactAccess()
                || staticMember != (support.invocation() == Invocation.STATIC)
                || (match.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            fail("runtime-adapter-support-member-mismatch");
        }
        List<String> hierarchy = support.receiverHierarchyProof();
        for (int index = 0; index + 1 < hierarchy.size(); index++) {
            byte[] childBytes = bytesByOwner.get(hierarchy.get(index));
            String parent = hierarchy.get(index + 1);
            if (!parent.equals(directSuperclass(childBytes))) {
                fail("runtime-adapter-support-proof-mismatch");
            }
            MemberMatch override = findMember(
                    childBytes, support.memberName(), support.descriptor(), true);
            if (override.count() != 0) {
                fail("runtime-adapter-support-override");
            }
        }
        if (!hierarchy.isEmpty()
                && !hierarchy.getLast().equals(support.ownerClass())) {
            fail("runtime-adapter-support-proof-mismatch");
        }
    }

    private static void verifyProof(byte[] bytes, Proof proof) {
        if (proof.role() != ProofRole.DISPATCHER_ORDER
                || !DispatcherOrderProof.verifies(
                        bytes,
                        proof.ownerClass(),
                        proof.memberName(),
                        proof.descriptor(),
                        proof.exactAccess(),
                        proof.ownerSha256())) {
            fail("runtime-adapter-dispatcher-proof-mismatch");
        }
    }

    private static void verifyVariant(
            byte[] bytes, Variant variant, Hook hitHook, ClassLoader targetLoader) {
        String child = variant.ownerClass();
        byte[] childBytes = bytes;
        for (String parent : variant.hierarchyProof()) {
            if (!directParents(childBytes).contains(parent)) {
                fail("runtime-adapter-variant-subtype-mismatch");
            }
            child = parent;
            childBytes = readClass(targetLoader, parent);
            if (childBytes == null) fail("runtime-adapter-class-missing");
        }
        if (!child.equals(hitHook.ownerClass())
                && !directParents(childBytes).contains(hitHook.ownerClass())) {
            fail("runtime-adapter-variant-subtype-mismatch");
        }
    }

    private static Set<String> directParents(byte[] bytes) {
        var parents = new HashSet<String>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                if (superName != null) parents.add(superName.replace('/', '.'));
                if (interfaces != null) for (String value : interfaces) parents.add(value.replace('/', '.'));
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return parents;
    }

    private static String directSuperclass(byte[] bytes) {
        String[] parent = {null};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                parent[0] = superName == null ? null : superName.replace('/', '.');
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return parent[0];
    }

    private static MemberMatch findMember(byte[] bytes, String name, String descriptor, boolean method) {
        int[] count = {0};
        int[] access = {0};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int modifiers, String foundName,
                    String foundDescriptor, String signature, Object value) {
                if (!method && name.equals(foundName) && descriptor.equals(foundDescriptor)) {
                    count[0]++; access[0] = modifiers;
                }
                return null;
            }
            @Override public MethodVisitor visitMethod(int modifiers, String foundName,
                    String foundDescriptor, String signature, String[] exceptions) {
                if (method && name.equals(foundName) && descriptor.equals(foundDescriptor)) {
                    count[0]++; access[0] = modifiers;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new MemberMatch(count[0], access[0]);
    }

    private static String canonicalIdentity(AdapterId id, Map<HookRole, Hook> hooks,
            Map<HitVariant, Variant> variants, Map<AccessorRole, AccessorChain> chains,
            Map<CapabilityRole, Capability> capabilities,
            Map<SupportRole, Support> supports,
            Map<ProofRole, Proof> proofs,
            Map<String, String> hashes) {
        var lines = new ArrayList<String>();
        lines.add("id=" + id);
        hooks.values().forEach(value -> lines.add("hook=" + value));
        variants.values().forEach(value -> lines.add("variant=" + value));
        chains.values().forEach(value -> lines.add("chain=" + value));
        capabilities.values().forEach(value -> lines.add("capability=" + value));
        supports.values().forEach(value -> lines.add("support=" + value));
        proofs.values().forEach(value -> lines.add("proof=" + value));
        hashes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(value -> lines.add("hash=" + value.getKey() + ":" + value.getValue()));
        Collections.sort(lines);
        return sha256(String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] readClass(ClassLoader loader, String owner) {
        try (InputStream input = loader.getResourceAsStream(owner.replace('.', '/') + ".class")) {
            return input == null ? null : input.readAllBytes();
        } catch (IOException error) {
            fail("runtime-adapter-class-read-failed");
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Type methodType(String descriptor, String reason) {
        try { return Type.getMethodType(descriptor); }
        catch (IllegalArgumentException error) { throw new RuntimeAdapterException(reason); }
    }

    private static int parseAccess(String value) {
        if (!value.matches("(?:0|[1-9a-f][0-9a-f]{0,3})")) {
            fail("runtime-adapter-member-access-invalid");
        }
        int access = Integer.parseInt(value, 16);
        if ((access & ~MEMBER_ACCESS_MASK) != 0
                || Integer.bitCount(access
                        & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) > 1) {
            fail("runtime-adapter-member-access-invalid");
        }
        return access;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String reason) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException error) { throw new RuntimeAdapterException(reason); }
    }

    private static <K, V> void requireComplete(Map<K, V> values, K[] required, String reason) {
        for (K key : required) if (!values.containsKey(key)) fail(reason);
    }

    private static void claimMember(Map<String, String> roles, String member, String role) {
        String existing = roles.putIfAbsent(member, role);
        if (existing != null && !existing.equals(role)) fail("runtime-adapter-member-cross-role");
        if (existing != null) fail("runtime-adapter-member-duplicate");
    }

    private static void claimChainMember(
            Map<String, String> roles, String member, AccessorRole role) {
        String claim = (role.hitVariant == null ? "CHAIN:" : "A5_VARIANT_CHAIN:") + role;
        String existing = roles.putIfAbsent(member, claim);
        if (existing == null) return;
        if (existing.equals(claim)) fail("runtime-adapter-member-duplicate");
        if (allowedA5Share(member, existing, claim)) {
            return;
        }
        if ((claim.startsWith("A5_VARIANT_CHAIN:") && reusableA4Claim(member, existing))
                || (existing.startsWith("A5_VARIANT_CHAIN:")
                        && reusableA4Claim(member, claim))) {
            return;
        }
        if (allowedA6Share(member, existing, claim)) {
            return;
        }
        fail("runtime-adapter-member-cross-role");
    }

    private static boolean allowedA6Share(String member, String first, String second) {
        AccessorRole firstRole = chainRole(first);
        AccessorRole secondRole = chainRole(second);
        if (firstRole == null || secondRole == null) return false;
        if (("zombie.popman.NetworkZombiePacker#packet"
                        + "Lzombie/network/packets/character/ZombiePacket;").equals(member)) {
            return zombiePacketRole(firstRole) && zombiePacketRole(secondRole);
        }
        if ("zombie.characters.IsoZombie#getOnlineID()S".equals(member)) {
            return zombieIdentityRole(firstRole) && zombieIdentityRole(secondRole);
        }
        if (("zombie.characters.IsoZombie#getOwner()"
                        + "Lzombie/core/raknet/UdpConnection;").equals(member)) {
            return zombieOwnerRole(firstRole) && zombieOwnerRole(secondRole);
        }
        return false;
    }

    private static AccessorRole chainRole(String claim) {
        int separator = claim.indexOf(':');
        if (separator < 0) return null;
        String prefix = claim.substring(0, separator);
        if (!prefix.equals("CHAIN") && !prefix.equals("A5_VARIANT_CHAIN")) return null;
        try {
            return AccessorRole.valueOf(claim.substring(separator + 1));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean zombiePacketRole(AccessorRole role) {
        return switch (role) {
            case ZOMBIE_PACKET,
                    ZOMBIE_PACKET_REAL_X,
                    ZOMBIE_PACKET_REAL_Y,
                    ZOMBIE_PACKET_REAL_Z,
                    ZOMBIE_PACKET_DIRECTION,
                    ZOMBIE_PACKET_HEALTH,
                    ZOMBIE_PACKET_TARGET,
                    ZOMBIE_PACKET_STATE -> true;
            default -> false;
        };
    }

    private static boolean zombieIdentityRole(AccessorRole role) {
        return role == AccessorRole.HIT_PVZ_TARGET
                || role == AccessorRole.HIT_ZVP_ATTACKER
                || role == AccessorRole.ZOMBIE_ENTITY
                || role == AccessorRole.OWNER_ZOMBIE;
    }

    private static boolean zombieOwnerRole(AccessorRole role) {
        return role == AccessorRole.ZOMBIE_CURRENT_OWNER
                || role == AccessorRole.OWNER_CONNECTION;
    }

    private static boolean allowedA5Share(String member, String first, String second) {
        AccessorRole firstRole = a5Role(first);
        AccessorRole secondRole = a5Role(second);
        if (firstRole == null || secondRole == null) return false;
        return switch (member) {
            case "zombie.network.packets.hit.PlayerHit#wielder"
                    + "Lzombie/network/fields/hit/Player;" ->
                both(firstRole, secondRole,
                        AccessorRole.HIT_PVP_ATTACKER, AccessorRole.HIT_PVZ_ATTACKER);
            case "zombie.network.fields.hit.Player#getPlayer()"
                    + "Lzombie/characters/IsoPlayer;",
                    "zombie.characters.IsoPlayer#getOnlineID()S" ->
                playerEndpoint(firstRole) && playerEndpoint(secondRole);
            case "zombie.network.packets.hit.PlayerHit#weapon"
                    + "Lzombie/network/fields/hit/Weapon;",
                    "zombie.network.fields.hit.Weapon#getWeapon()"
                    + "Lzombie/inventory/types/HandWeapon;",
                    "zombie.inventory.types.HandWeapon#getMaxHitCount()I" ->
                both(firstRole, secondRole,
                        AccessorRole.HIT_PVP_WEAPON, AccessorRole.HIT_PVZ_WEAPON);
            case "zombie.network.fields.hit.Zombie#getZombie()"
                    + "Lzombie/characters/IsoZombie;",
                    "zombie.characters.IsoZombie#getOnlineID()S" ->
                both(firstRole, secondRole,
                        AccessorRole.HIT_PVZ_TARGET, AccessorRole.HIT_ZVP_ATTACKER);
            default -> false;
        };
    }

    private static AccessorRole a5Role(String claim) {
        String prefix = "A5_VARIANT_CHAIN:";
        if (!claim.startsWith(prefix)) return null;
        try {
            return AccessorRole.valueOf(claim.substring(prefix.length()));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static boolean playerEndpoint(AccessorRole role) {
        return role == AccessorRole.HIT_PVP_ATTACKER
                || role == AccessorRole.HIT_PVP_TARGET
                || role == AccessorRole.HIT_PVZ_ATTACKER
                || role == AccessorRole.HIT_ZVP_TARGET;
    }

    private static boolean both(
            AccessorRole first, AccessorRole second, AccessorRole left, AccessorRole right) {
        return (first == left && second == right) || (first == right && second == left);
    }

    private static boolean reusableA4Claim(String member, String claim) {
        return switch (claim) {
            case "CHAIN:PLAYER_RELATED_PLAYER" ->
                "zombie.characters.IsoPlayer#getOnlineID()S".equals(member);
            case "CHAIN:ATTACK_WEAPON" ->
                ("zombie.network.fields.hit.Weapon#getWeapon()"
                        + "Lzombie/inventory/types/HandWeapon;").equals(member)
                || "zombie.inventory.types.HandWeapon#getMaxHitCount()I".equals(member);
            default -> false;
        };
    }

    private static void fail(String reason) { throw new RuntimeAdapterException(reason); }

    public enum AdapterId { PZ_42_20_2 }
    public enum ReturnPolicy {
        VOID_SUCCESS, PREVALIDATED_VOID;
        boolean matches(Type returnType) { return returnType.getSort() == Type.VOID; }
    }
    public enum Invocation { INSTANCE, STATIC, VIRTUAL }
    public enum HookRole {
        PLAYER_ACCEPTED(ReturnPolicy.VOID_SUCCESS, Invocation.INSTANCE),
        ATTACK_ACCEPTED(ReturnPolicy.VOID_SUCCESS, Invocation.INSTANCE),
        HIT_PRE_APPLY(ReturnPolicy.PREVALIDATED_VOID, Invocation.INSTANCE),
        ZOMBIE_APPLY(ReturnPolicy.VOID_SUCCESS, Invocation.INSTANCE),
        ZOMBIE_OWNER(ReturnPolicy.VOID_SUCCESS, Invocation.INSTANCE),
        LUA_INIT(ReturnPolicy.VOID_SUCCESS, Invocation.STATIC);
        private final ReturnPolicy policy; private final Invocation invocation;
        HookRole(ReturnPolicy policy, Invocation invocation) { this.policy = policy; this.invocation = invocation; }
    }
    public enum HitVariant {
        PLAYER_HIT_PLAYER(TargetKind.PLAYER), PLAYER_HIT_ZOMBIE(TargetKind.ZOMBIE),
        ZOMBIE_HIT_PLAYER(TargetKind.PLAYER);
        private final TargetKind targetKind;
        HitVariant(TargetKind targetKind) { this.targetKind = targetKind; }
    }
    public enum TargetKind { PLAYER, ZOMBIE }
    public enum Source {
        THIS(-1), ARG0(0), ARG1(1), ARG2(2), ARG3(3), STATIC(-1);
        private final int argumentIndex;
        Source(int argumentIndex) { this.argumentIndex = argumentIndex; }
    }
    public enum AccessorKind {
        INSTANCE_FIELD(false, false), VIRTUAL0(false, true), STATIC_FIELD(true, false), STATIC0(true, true);
        private final boolean staticMember; private final boolean method;
        AccessorKind(boolean staticMember, boolean method) { this.staticMember = staticMember; this.method = method; }
        boolean isStatic() { return staticMember; } boolean isMethod() { return method; }
    }
    public enum AccessorRole {
        PLAYER_RELATED_PLAYER(HookRole.PLAYER_ACCEPTED), PLAYER_PREDICTION(HookRole.PLAYER_ACCEPTED),
        ATTACK_PLAYER(HookRole.ATTACK_ACCEPTED), ATTACK_WEAPON(HookRole.ATTACK_ACCEPTED),
        ATTACK_HIT_COUNT(HookRole.ATTACK_ACCEPTED),
        HIT_ATTACKER(HookRole.HIT_PRE_APPLY), HIT_TARGET(HookRole.HIT_PRE_APPLY),
        HIT_WEAPON(HookRole.HIT_PRE_APPLY),
        HIT_PVP_ATTACKER(HookRole.HIT_PRE_APPLY, HitVariant.PLAYER_HIT_PLAYER),
        HIT_PVP_TARGET(HookRole.HIT_PRE_APPLY, HitVariant.PLAYER_HIT_PLAYER),
        HIT_PVP_WEAPON(HookRole.HIT_PRE_APPLY, HitVariant.PLAYER_HIT_PLAYER),
        HIT_PVZ_ATTACKER(HookRole.HIT_PRE_APPLY, HitVariant.PLAYER_HIT_ZOMBIE),
        HIT_PVZ_TARGET(HookRole.HIT_PRE_APPLY, HitVariant.PLAYER_HIT_ZOMBIE),
        HIT_PVZ_WEAPON(HookRole.HIT_PRE_APPLY, HitVariant.PLAYER_HIT_ZOMBIE),
        HIT_ZVP_ATTACKER(HookRole.HIT_PRE_APPLY, HitVariant.ZOMBIE_HIT_PLAYER),
        HIT_ZVP_TARGET(HookRole.HIT_PRE_APPLY, HitVariant.ZOMBIE_HIT_PLAYER),
        CONNECTION_AVERAGE_PING(HookRole.HIT_PRE_APPLY),
        WORLD_SERVER_MAP(HookRole.HIT_PRE_APPLY),
        WORLD_LOS_CLEAR(HookRole.HIT_PRE_APPLY),
        WORLD_LOS_OPEN_DOOR(HookRole.HIT_PRE_APPLY),
        ZOMBIE_PACKET(HookRole.ZOMBIE_APPLY),
        ZOMBIE_PACKET_REAL_X(HookRole.ZOMBIE_APPLY),
        ZOMBIE_PACKET_REAL_Y(HookRole.ZOMBIE_APPLY),
        ZOMBIE_PACKET_REAL_Z(HookRole.ZOMBIE_APPLY),
        ZOMBIE_PACKET_DIRECTION(HookRole.ZOMBIE_APPLY),
        ZOMBIE_PACKET_HEALTH(HookRole.ZOMBIE_APPLY),
        ZOMBIE_PACKET_TARGET(HookRole.ZOMBIE_APPLY),
        ZOMBIE_PACKET_STATE(HookRole.ZOMBIE_APPLY),
        ZOMBIE_ENTITY(HookRole.ZOMBIE_APPLY),
        ZOMBIE_CURRENT_OWNER(HookRole.ZOMBIE_APPLY),
        OWNER_ZOMBIE(HookRole.ZOMBIE_OWNER),
        OWNER_CONNECTION(HookRole.ZOMBIE_OWNER), OWNER_PLAYER(HookRole.ZOMBIE_OWNER),
        LUA_ENV(HookRole.LUA_INIT), LUA_PLATFORM(HookRole.LUA_INIT);
        private final HookRole hookRole;
        private final HitVariant hitVariant;
        AccessorRole(HookRole hookRole) { this(hookRole, null); }
        AccessorRole(HookRole hookRole, HitVariant hitVariant) {
            this.hookRole = hookRole;
            this.hitVariant = hitVariant;
        }
        public HitVariant hitVariant() { return hitVariant; }
    }

    public enum SupportRole {
        MOVING_X(Invocation.VIRTUAL, "zombie.iso.IsoMovingObject", "getX", "()F", 1),
        MOVING_Y(Invocation.VIRTUAL, "zombie.iso.IsoMovingObject", "getY", "()F", 1),
        MOVING_Z(Invocation.VIRTUAL, "zombie.iso.IsoMovingObject", "getZ", "()F", 1),
        CHARACTER_FORWARD(Invocation.VIRTUAL, "zombie.characters.IsoGameCharacter",
                "getForwardDirection", "()Lzombie/iso/Vector2;", 1),
        CHARACTER_ALIVE(Invocation.VIRTUAL, "zombie.characters.IsoGameCharacter",
                "isAlive", "()Z", 1),
        CHARACTER_PRIMARY_HAND(Invocation.VIRTUAL, "zombie.characters.IsoGameCharacter",
                "getPrimaryHandItem", "()Lzombie/inventory/InventoryItem;", 1),
        CHARACTER_SECONDARY_HAND(Invocation.VIRTUAL, "zombie.characters.IsoGameCharacter",
                "getSecondaryHandItem", "()Lzombie/inventory/InventoryItem;", 1),
        VECTOR_X(Invocation.VIRTUAL, "zombie.iso.Vector2", "getX", "()F", 1),
        VECTOR_Y(Invocation.VIRTUAL, "zombie.iso.Vector2", "getY", "()F", 1),
        WEAPON_ID(Invocation.VIRTUAL, "zombie.inventory.InventoryItem", "getID", "()I", 1,
                "zombie.inventory.types.HandWeapon", "zombie.inventory.InventoryItem"),
        WEAPON_RANGED(Invocation.VIRTUAL, "zombie.inventory.types.HandWeapon", "isRanged", "()Z", 1),
        WEAPON_PROJECTILES(Invocation.VIRTUAL, "zombie.inventory.types.HandWeapon",
                "getProjectileCount", "()I", 1),
        WEAPON_MAX_RANGE(Invocation.VIRTUAL, "zombie.inventory.types.HandWeapon",
                "getMaxRange", "()F", 1),
        WEAPON_MIN_ANGLE(Invocation.VIRTUAL, "zombie.inventory.types.HandWeapon",
                "getMinAngle", "()F", 1),
        SQUARE_CELL(Invocation.VIRTUAL, "zombie.iso.IsoGridSquare", "getCell",
                "()Lzombie/iso/IsoCell;", 1);

        private final Invocation invocation;
        private final String ownerClass;
        private final String memberName;
        private final String descriptor;
        private final int exactAccess;
        private final List<String> receiverHierarchyProof;

        SupportRole(
                Invocation invocation,
                String ownerClass,
                String memberName,
                String descriptor,
                int exactAccess,
                String... receiverHierarchyProof) {
            this.invocation = invocation;
            this.ownerClass = ownerClass;
            this.memberName = memberName;
            this.descriptor = descriptor;
            this.exactAccess = exactAccess;
            this.receiverHierarchyProof = List.of(receiverHierarchyProof);
        }
    }
    public enum CapabilityRole {
        WORLD_SQUARES_LOADED(
                Invocation.VIRTUAL,
                "zombie.network.ServerMap",
                "getGridSquare",
                "(III)Lzombie/iso/IsoGridSquare;",
                Opcodes.ACC_PUBLIC),
        WORLD_LINE_OF_SIGHT(
                Invocation.STATIC,
                "zombie.iso.LosUtil",
                "lineClear",
                "(Lzombie/iso/IsoCell;IIIIIIZ)Lzombie/iso/LosUtil$TestResults;",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
        LUA_NEW_TABLE(
                Invocation.VIRTUAL,
                "se.krka.kahlua.j2se.J2SEPlatform",
                "newTable",
                "()Lse/krka/kahlua/vm/KahluaTable;",
                Opcodes.ACC_PUBLIC),
        LUA_RAWSET(
                Invocation.VIRTUAL,
                "se.krka.kahlua.vm.KahluaTable",
                "rawset",
                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT),
        LUA_RAWGET(
                Invocation.VIRTUAL,
                "se.krka.kahlua.vm.KahluaTable",
                "rawget",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT),
        LUA_JAVA_FUNCTION_CALL(
                Invocation.VIRTUAL,
                "se.krka.kahlua.vm.JavaFunction",
                "call",
                "(Lse/krka/kahlua/vm/LuaCallFrame;I)I",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT),
        LUA_CALL_FRAME_GET(
                Invocation.VIRTUAL,
                "se.krka.kahlua.vm.LuaCallFrame",
                "get",
                "(I)Ljava/lang/Object;",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL),
        LUA_CALL_FRAME_PUSH(
                Invocation.VIRTUAL,
                "se.krka.kahlua.vm.LuaCallFrame",
                "push",
                "(Ljava/lang/Object;)I",
                Opcodes.ACC_PUBLIC);
        private final Invocation invocation;
        private final String ownerClass;
        private final String memberName;
        private final String descriptor;
        private final int exactAccess;

        CapabilityRole(
                Invocation invocation,
                String ownerClass,
                String memberName,
                String descriptor,
                int exactAccess) {
            this.invocation = invocation;
            this.ownerClass = ownerClass;
            this.memberName = memberName;
            this.descriptor = descriptor;
            this.exactAccess = exactAccess;
        }
    }

    public enum ProofRole {
        DISPATCHER_ORDER(
                "zombie.network.PacketTypes$PacketType",
                "onServerPacket",
                "(Lzombie/core/network/ByteBufferReader;"
                        + "Lzombie/core/raknet/UdpConnection;)V",
                Opcodes.ACC_PUBLIC);

        private final String ownerClass;
        private final String memberName;
        private final String descriptor;
        private final int exactAccess;

        ProofRole(
                String ownerClass, String memberName, String descriptor, int exactAccess) {
            this.ownerClass = ownerClass;
            this.memberName = memberName;
            this.descriptor = descriptor;
            this.exactAccess = exactAccess;
        }
    }

    public record Hook(HookRole role, ReturnPolicy returnPolicy, Invocation invocation,
            int exactAccess,
            String ownerClass, String memberName, String descriptor, String ownerSha256) {
        String memberIdentity() { return ownerClass + "#" + memberName + descriptor; }
    }
    public record Variant(HitVariant variant, TargetKind targetKind, String ownerClass,
            String ownerSha256, List<String> hierarchyProof) {
        public Variant { hierarchyProof = List.copyOf(hierarchyProof); }
    }
    public record AccessorChain(AccessorRole role, Source source, List<AccessorStep> steps) {}
    public record AccessorStep(int index, AccessorKind kind, int exactAccess,
            String ownerClass, String memberName,
            String descriptor, String ownerSha256) {
        String memberIdentity() { return ownerClass + "#" + memberName + descriptor; }
    }
    public record Capability(CapabilityRole role, Invocation invocation, int exactAccess,
            String ownerClass,
            String memberName, String descriptor, String ownerSha256) {
        String memberIdentity() { return ownerClass + "#" + memberName + descriptor; }
    }
    public record Support(
            SupportRole role,
            Invocation invocation,
            int exactAccess,
            String ownerClass,
            String memberName,
            String descriptor,
            String ownerSha256,
            List<String> receiverHierarchyProof) {
        public Support { receiverHierarchyProof = List.copyOf(receiverHierarchyProof); }
        String memberIdentity() { return ownerClass + "#" + memberName + descriptor; }
    }
    public record Proof(
            ProofRole role,
            int exactAccess,
            String ownerClass,
            String memberName,
            String descriptor,
            String ownerSha256) {
        String memberIdentity() { return ownerClass + "#" + memberName + descriptor; }
    }
    private record Member(String owner, String name, String descriptor, String ownerHash) {}
    private record StepEntry(AccessorRole role, Source source, int index, AccessorKind kind,
            int exactAccess,
            String ownerClass, String memberName, String descriptor, String ownerSha256) {}
    private record MemberMatch(int count, int access) {}
    private record VariantProofEntry(HitVariant variant, int index, String ownerClass) {}
    private record SupportProofEntry(SupportRole role, int index, String ownerClass) {}
}
