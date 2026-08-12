package ru.apollot.pzsync.hooks;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import ru.apollot.pzsync.bridge.ApolloNativeBridge;
import ru.apollot.pzsync.bridge.BridgeConfiguration;
import ru.apollot.pzsync.bridge.BridgePublisher;
import ru.apollot.pzsync.bridge.BridgeStatus;
import ru.apollot.pzsync.attack.AttackLedger;
import ru.apollot.pzsync.gate.AssistState;
import ru.apollot.pzsync.gate.GateResult;
import ru.apollot.pzsync.gate.RuntimeFingerprint;
import ru.apollot.pzsync.geometry.CollisionProbe;
import ru.apollot.pzsync.geometry.RewindValidator;
import ru.apollot.pzsync.history.HistoryStore;
import ru.apollot.pzsync.zombie.ZombieAuthorityEpochs;
import ru.apollot.pzsync.zombie.ZombieHandoffPolicy;
import ru.apollot.pzsync.zombie.ZombieKeyframePolicy;
import ru.apollot.pzsync.runtime.RuntimeAdapterException;
import ru.apollot.pzsync.runtime.RuntimeAdapterSpec;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

public final class HookInstaller implements BridgePublisher.Control {
    public static final int RUNTIME_ERROR_THRESHOLD = 3;
    private static final String RUNTIME_BINDINGS_PREFIX = "RUNTIME_BINDINGS_FACTORY|";
    private static final String RUNTIME_BINDINGS_CLASS =
            "ru.apollot.pzsync.hooks.ExactRuntimeBindingsFactory";
    private static final String RUNTIME_BINDINGS_METHOD = "create";
    private static final String RUNTIME_BINDINGS_DESCRIPTOR =
            "(Lru/apollot/pzsync/gate/RuntimeFingerprint;)"
                    + "Lru/apollot/pzsync/hooks/HookInstaller$ProductionBindings;";

    private static final Object REGISTRY_LOCK = new Object();
    private static final IdentityHashMap<Instrumentation, HookInstaller> INSTALLATIONS =
            new IdentityHashMap<>();
    private static final AtomicReference<HookInstaller> ACTIVE = new AtomicReference<>();
    private static final AtomicLong INJECTED_BINDING_SEQUENCE = new AtomicLong();

    private final NativeDecisionAdapter adapter;
    private final BridgePublisher.ContextualPublicationTarget publicationTarget;
    private final Thread mainThread;
    private final ClassLoader targetLoader;
    private final List<ExactMemberBinding> memberBindings;
    private final String bindingsIdentity;
    private final RuntimeAdapterSpec runtimeAdapterSpec;
    private final DefinitionVerifier definitionVerifier;
    private final AdviceRenderer adviceRenderer;
    private final EnumSet<HookDescriptor.HookPoint> transformed =
            EnumSet.noneOf(HookDescriptor.HookPoint.class);
    private final EnumSet<HookDescriptor.HookPoint> claimedPoints =
            EnumSet.noneOf(HookDescriptor.HookPoint.class);
    private final EnumMap<AdviceCategory, Integer> adviceErrors =
            new EnumMap<>(AdviceCategory.class);
    private final EnumMap<HookDescriptor.HookPoint, HookDescriptor> hookDescriptors =
            new EnumMap<>(HookDescriptor.HookPoint.class);

    private volatile BridgeStatus currentStatus =
            new BridgeStatus(AssistState.ABSENT, "hooks-not-installed", "", "");
    private RuntimeFingerprint fingerprint;
    private Instrumentation instrumentation;
    private ClassFileTransformer transformer;
    private BridgePublisher publisher;
    private boolean installAttempted;
    private boolean bridgePublished;
    private boolean handshakeAccepted;
    private boolean definitionsConfirmed;
    private boolean terminal;
    private boolean transformerRemoved;
    private long adviceErrorTotal;
    private long controlMetricSequence;
    private long transformFailures;
    private long transformedClassCount;
    private ProtectionDomain pinnedTargetDomain;
    private CodeSource pinnedTargetCodeSource;
    private boolean targetDomainPinned;

    public HookInstaller(
            NativeDecisionAdapter adapter,
            BridgePublisher.PublicationTarget publicationTarget,
            Thread mainThread) {
        this(
                adapter,
                publicationTarget,
                mainThread,
                Thread.currentThread().getContextClassLoader());
    }

    public HookInstaller(
            NativeDecisionAdapter adapter,
            BridgePublisher.PublicationTarget publicationTarget,
            Thread mainThread,
            ClassLoader targetLoader) {
        this(
                adapter,
                (receiver, arguments, exports) -> publicationTarget.publishAtomically(exports),
                mainThread,
                targetLoader,
                List.of(),
                "injected#" + INJECTED_BINDING_SEQUENCE.incrementAndGet(),
                null,
                () -> {},
                HookInstaller::renderAdvice);
    }

    HookInstaller(
            NativeDecisionAdapter adapter,
            BridgePublisher.PublicationTarget publicationTarget,
            Thread mainThread,
            ClassLoader targetLoader,
            AdviceRenderer adviceRenderer) {
        this(
                adapter,
                (receiver, arguments, exports) -> publicationTarget.publishAtomically(exports),
                mainThread,
                targetLoader,
                List.of(),
                "injected#" + INJECTED_BINDING_SEQUENCE.incrementAndGet(),
                null,
                () -> {},
                adviceRenderer);
    }

    private HookInstaller(
            NativeDecisionAdapter adapter,
            BridgePublisher.ContextualPublicationTarget publicationTarget,
            Thread mainThread,
            ClassLoader targetLoader,
            List<ExactMemberBinding> memberBindings,
            String bindingsIdentity,
            RuntimeAdapterSpec runtimeAdapterSpec,
            DefinitionVerifier definitionVerifier,
            AdviceRenderer adviceRenderer) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.publicationTarget = Objects.requireNonNull(publicationTarget, "publicationTarget");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.targetLoader = Objects.requireNonNull(targetLoader, "targetLoader");
        this.memberBindings = List.copyOf(memberBindings);
        this.bindingsIdentity = Objects.requireNonNull(bindingsIdentity, "bindingsIdentity");
        this.runtimeAdapterSpec = runtimeAdapterSpec;
        this.definitionVerifier = Objects.requireNonNull(definitionVerifier, "definitionVerifier");
        this.adviceRenderer = Objects.requireNonNull(adviceRenderer, "adviceRenderer");
    }

    public static GateResult bootstrap(
            Instrumentation instrumentation,
            RuntimeFingerprint fingerprint,
            Thread mainThread) {
        return bootstrap(
                instrumentation,
                fingerprint,
                mainThread,
                HookInstaller::resolveProductionBindings);
    }

    static GateResult bootstrap(
            Instrumentation instrumentation,
            RuntimeFingerprint fingerprint,
            Thread mainThread,
            BindingResolver bindingResolver) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(mainThread, "mainThread");
        Objects.requireNonNull(bindingResolver, "bindingResolver");
        final ProductionBindings bindings;
        try {
            bindings = bindingResolver.resolve(fingerprint);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            String reason = error instanceof PreflightFailure preflight
                    ? preflight.reasonCode
                    : "runtime-bindings-factory-failed";
            return new GateResult(
                    AssistState.INCOMPATIBLE,
                    reason,
                    "native runtime bindings were not accepted");
        }

        var attackLedger = new AttackLedger();
        var history = bindings.historyStore();
        var epochs = new ZombieAuthorityEpochs();
        var adapter =
                new NativeDecisionAdapter(
                        attackLedger,
                        new RewindValidator(),
                        bindings.collisionProbe(),
                        bindings.packetAccess(),
                        history,
                        new PlayerStateObserver(history),
                        new ZombieStateObserver(
                                history,
                                epochs,
                                new ZombieHandoffPolicy(epochs),
                                new ZombieKeyframePolicy(epochs)));
        return new HookInstaller(
                        adapter,
                        bindings.contextualPublicationTarget(),
                        mainThread,
                        bindings.targetLoader(),
                        bindings.memberBindings(),
                        bindings.identity(),
                        bindings.runtimeAdapterSpec(),
                        bindings.definitionVerifier(),
                        HookInstaller::renderAdvice)
                .install(instrumentation, fingerprint);
    }

    public GateResult install(
            Instrumentation candidateInstrumentation, RuntimeFingerprint candidateFingerprint) {
        Objects.requireNonNull(candidateInstrumentation, "instrumentation");
        Objects.requireNonNull(candidateFingerprint, "fingerprint");
        synchronized (this) {
            if (installAttempted) {
                if (!candidateFingerprint.equals(fingerprint)
                        || candidateInstrumentation != instrumentation) {
                    incompatible("repeat-install-mismatch");
                }
                return gateResult();
            }
            installAttempted = true;
            instrumentation = candidateInstrumentation;
            fingerprint = candidateFingerprint;
            updateStatus(AssistState.ABSENT, "hooks-preflight");
        }
        synchronized (REGISTRY_LOCK) {
            HookInstaller existing = INSTALLATIONS.get(candidateInstrumentation);
            if (existing != null && existing != this) {
                if (existing.sameInstallationTuple(
                        candidateInstrumentation,
                        candidateFingerprint,
                        targetLoader,
                        bindingsIdentity)) {
                    synchronized (this) {
                        currentStatus = existing.currentStatus;
                        emitControlMetric();
                    }
                    return existing.gateResult();
                }
                existing.incompatible("repeat-install-bindings-mismatch");
                incompatible("repeat-install-bindings-mismatch");
                return gateResult();
            }
            INSTALLATIONS.put(candidateInstrumentation, this);
        }

        final List<HookDescriptor> descriptors;
        final Map<String, PreparedClass> prepared;
        try {
            descriptors = runtimeAdapterSpec == null
                    ? HookDescriptor.requiredFrom(candidateFingerprint)
                    : runtimeAdapterSpec.hookDescriptors();
            synchronized (this) {
                descriptors.forEach(value -> hookDescriptors.put(value.point(), value));
            }
            String bindingContextFailure =
                    ExactMemberBinding.contextFailure(memberBindings, descriptors);
            if (bindingContextFailure != null) {
                throw new PreflightFailure(bindingContextFailure);
            }
            Set<String> targets = targetClassNames(descriptors, memberBindings, runtimeAdapterSpec);
            rejectAlreadyLoaded(candidateInstrumentation, targets);
            prepared = preflight(descriptors, memberBindings);
            rejectAlreadyLoaded(candidateInstrumentation, targets);
        } catch (PreflightFailure error) {
            incompatible(error.reasonCode);
            return gateResult();
        } catch (IllegalArgumentException error) {
            incompatible("hook-descriptor-set-invalid");
            return gateResult();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            incompatible("hook-preflight-failed");
            return gateResult();
        }

        var exactTransformer = new ExactTransformer(prepared);
        synchronized (this) {
            transformer = exactTransformer;
            publisher = new BridgePublisher(mainThread, this, publicationTarget);
            updateStatus(AssistState.ABSENT, "hooks-awaiting-definitions");
            ACTIVE.set(this);
        }
        try {
            candidateInstrumentation.addTransformer(exactTransformer, false);
            rejectLoadedWithoutTransform(
                    candidateInstrumentation,
                    targetClassNames(descriptors, memberBindings, runtimeAdapterSpec));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (PreflightFailure error) {
            incompatible(error.reasonCode);
        } catch (Throwable error) {
            rethrowFatalCause(error);
            incompatible("hook-transformer-registration-failed");
        }
        return gateResult();
    }

    public BridgeStatus status() {
        return currentStatus;
    }

    public synchronized Map<String, Long> metrics() {
        var snapshot = new LinkedHashMap<String, Long>();
        snapshot.put("hook_transformed_classes", transformedClassCount);
        snapshot.put("hook_transform_failures", transformFailures);
        snapshot.put("advice_errors_total", adviceErrorTotal);
        for (AdviceCategory category : AdviceCategory.values()) {
            snapshot.put(
                    "advice_errors_" + category.metricSuffix,
                    adviceErrors.getOrDefault(category, 0).longValue());
        }
        snapshot.putAll(adapter.metrics());
        emitControlMetric();
        return Collections.unmodifiableMap(snapshot);
    }

    private void emitControlMetric() {
        BridgeStatus current = currentStatus;
        controlMetricSequence++;
        System.err.println(
                "[ApolloNativeAssist] control seq="
                        + controlMetricSequence
                        + " state="
                        + current.state()
                        + " reason="
                        + safeDiagnosticReason(current.reasonCode())
                        + " advice_errors_total="
                        + adviceErrorTotal);
    }

    private static String safeDiagnosticReason(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) {
            return "invalid-reason";
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '.'
                    && character != '_'
                    && character != '-') {
                return "invalid-reason";
            }
        }
        return value;
    }

    @Override
    public synchronized BridgeStatus handshake(ApolloNativeBridge.Handshake handshake) {
        handshakeAccepted = false;
        adapter.clearConfiguration();
        if (terminal) {
            return currentStatus;
        }
        if (handshake == null) {
            updateStatus(AssistState.INCOMPATIBLE, "bridge-handshake-invalid");
            return currentStatus;
        }
        if (!fingerprint.workshopId().equals(handshake.workshopId())
                || !fingerprint.luaModId().equals(handshake.luaModId())
                || !fingerprint.bridgeProtocol().equals(handshake.bridgeProtocol())) {
            updateStatus(AssistState.INCOMPATIBLE, "bridge-handshake-mismatch");
            return currentStatus;
        }
        if (!handshake.nativeAssistEnabled()) {
            updateStatus(AssistState.DISABLED, "config-disabled");
            return currentStatus;
        }
        if (!validAuthorization(handshake)) {
            updateStatus(AssistState.INCOMPATIBLE, "bridge-handshake-config-invalid");
            return currentStatus;
        }
        adapter.configure(handshake);
        handshakeAccepted = true;
        updateStatus(AssistState.ABSENT, "hooks-awaiting-arm");
        maybeArm();
        return currentStatus;
    }

    private boolean validAuthorization(ApolloNativeBridge.Handshake handshake) {
        return BridgeConfiguration.valid(handshake);
    }

    @Override
    public synchronized void bridgePublished() {
        if (terminal) {
            return;
        }
        bridgePublished = true;
        maybeArm();
    }

    @Override
    public void bridgeFailure(String reasonCode) {
        circuitOpen(reasonCode);
    }

    public static void playerStateExit(
            Object receiver, Object[] arguments, Object returned, Throwable thrown) {
        HookInstaller active = ACTIVE.get();
        if (active != null
                && thrown == null
                && active.nativeAccepted(HookDescriptor.HookPoint.PLAYER_STATE, returned)) {
            active.observe(AdviceCategory.PLAYER_STATE,
                    () -> active.adapter.observePlayer(receiver, arguments));
        }
    }

    public static void attackOpenExit(
            Object receiver, Object[] arguments, Object returned, Throwable thrown) {
        HookInstaller active = ACTIVE.get();
        if (active != null
                && thrown == null
                && active.nativeAccepted(HookDescriptor.HookPoint.ATTACK_OPEN, returned)) {
            active.observe(AdviceCategory.ATTACK_OPEN,
                    () -> active.adapter.observeAttackOpen(receiver, arguments));
        }
    }

    public static boolean hitEnter(Object receiver, Object[] arguments) {
        HookInstaller active = ACTIVE.get();
        return active != null && active.rejectHit(receiver, arguments);
    }

    public static Object zombieStateEnter(Object receiver, Object[] arguments) {
        HookInstaller active = ACTIVE.get();
        if (active == null || !active.readyForAdvice(false)) return null;
        try {
            return active.adapter.captureZombieState(receiver, arguments);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            active.recordAdviceError(AdviceCategory.ZOMBIE_STATE);
            return null;
        }
    }

    public static void zombieStateExit(Object capture, Object returned, Throwable thrown) {
        HookInstaller active = ACTIVE.get();
        if (active != null && capture != null && thrown == null
                && active.nativeAccepted(HookDescriptor.HookPoint.ZOMBIE_STATE, returned)) {
            active.observe(AdviceCategory.ZOMBIE_STATE,
                    () -> active.adapter.completeZombieState(capture));
        }
    }

    public static void zombieStateExit(
            Object receiver, Object[] arguments, Object returned, Throwable thrown) {
        HookInstaller active = ACTIVE.get();
        if (active != null
                && thrown == null
                && active.nativeAccepted(HookDescriptor.HookPoint.ZOMBIE_STATE, returned)) {
            active.observe(AdviceCategory.ZOMBIE_STATE,
                    () -> active.adapter.observeZombieState(receiver, arguments));
        }
    }

    public static Object zombieOwnershipEnter(Object receiver, Object[] arguments) {
        HookInstaller active = ACTIVE.get();
        if (active == null || !active.readyForAdvice(false)) return null;
        try {
            return active.adapter.captureZombieOwnership(receiver, arguments);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            active.recordAdviceError(AdviceCategory.ZOMBIE_OWNERSHIP);
            return null;
        }
    }

    public static void zombieOwnershipExit(Object capture, Object returned, Throwable thrown) {
        HookInstaller active = ACTIVE.get();
        if (active != null && capture != null && thrown == null
                && active.nativeAccepted(HookDescriptor.HookPoint.ZOMBIE_OWNERSHIP, returned)) {
            active.observe(AdviceCategory.ZOMBIE_OWNERSHIP,
                    () -> active.adapter.completeZombieOwnership(capture));
        }
    }

    public static void zombieOwnershipExit(
            Object receiver, Object[] arguments, Object returned, Throwable thrown) {
        HookInstaller active = ACTIVE.get();
        if (active != null
                && thrown == null
                && active.nativeAccepted(
                        HookDescriptor.HookPoint.ZOMBIE_OWNERSHIP, returned)) {
            active.observe(AdviceCategory.ZOMBIE_OWNERSHIP,
                    () -> active.adapter.observeZombieOwnership(receiver, arguments));
        }
    }

    public static void zombieKeyframeExit(
            Object receiver, Object[] arguments, Object returned, Throwable thrown) {
        HookInstaller active = ACTIVE.get();
        if (active != null
                && thrown == null
                && active.nativeAccepted(HookDescriptor.HookPoint.ZOMBIE_KEYFRAME, returned)) {
            active.observe(AdviceCategory.ZOMBIE_KEYFRAME,
                    () -> active.adapter.observeZombieKeyframe(receiver, arguments));
        }
    }

    public static void bridgeExit(Object receiver, Object[] arguments, Throwable thrown) {
        HookInstaller active = ACTIVE.get();
        if (active != null && thrown == null) {
            active.observe(
                    AdviceCategory.BRIDGE,
                    () -> active.confirmDefinitionsAndPublish(receiver, arguments));
        }
    }

    private synchronized boolean nativeAccepted(
            HookDescriptor.HookPoint point, Object returned) {
        HookDescriptor descriptor = hookDescriptors.get(point);
        return descriptor != null && descriptor.nativeAccepted(returned);
    }

    private void observe(AdviceCategory category, AdviceOperation operation) {
        if (!readyForAdvice(category == AdviceCategory.BRIDGE)) {
            return;
        }
        try {
            operation.run();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            recordAdviceError(category);
        }
    }

    private boolean rejectHit(Object receiver, Object[] arguments) {
        if (!readyForAdvice(false)) {
            return false;
        }
        try {
            return !adapter.allowHit(receiver, arguments);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            recordAdviceError(AdviceCategory.HIT_GATE);
            return false;
        }
    }

    private synchronized boolean readyForAdvice(boolean bridgeAdvice) {
        if (terminal || Thread.currentThread() != mainThread) {
            if (!terminal && Thread.currentThread() != mainThread) {
                recordAdviceError(bridgeAdvice ? AdviceCategory.BRIDGE : AdviceCategory.THREAD);
            }
            return false;
        }
        if (bridgeAdvice) {
            return claimedPoints.contains(HookDescriptor.HookPoint.BRIDGE_PUBLISH);
        }
        return handshakeAccepted && currentStatus.state() == AssistState.READY;
    }

    private synchronized void recordAdviceError(AdviceCategory category) {
        if (terminal) {
            return;
        }
        adviceErrorTotal++;
        int categoryCount = adviceErrors.merge(category, 1, Integer::sum);
        if (categoryCount >= RUNTIME_ERROR_THRESHOLD) {
            circuitOpen("advice-error-threshold");
        } else {
            emitControlMetric();
        }
    }

    private Map<String, PreparedClass> preflight(
            List<HookDescriptor> descriptors, List<ExactMemberBinding> bindings)
            throws Throwable {
        if (runtimeAdapterSpec != null) {
            runtimeAdapterSpec.preflight(targetLoader);
        }
        var grouped = new LinkedHashMap<String, List<HookDescriptor>>();
        for (HookDescriptor descriptor : descriptors) {
            grouped.compute(
                    descriptor.className(),
                    (ignored, existing) -> {
                        var values = existing == null
                                ? new java.util.ArrayList<HookDescriptor>()
                                : new java.util.ArrayList<>(existing);
                        values.add(descriptor);
                        return List.copyOf(values);
                    });
        }
        for (ExactMemberBinding binding : bindings) {
            grouped.putIfAbsent(binding.ownerClass(), List.of());
        }
        var prepared = new LinkedHashMap<String, PreparedClass>();
        for (var entry : grouped.entrySet()) {
            String className = entry.getKey();
            List<HookDescriptor> classHooks = entry.getValue();
            byte[] source = locateTargetClass(className);
            if (source == null) {
                throw new PreflightFailure("hook-class-missing");
            }
            String observedHash = sha256(source);
            if (runtimeAdapterSpec != null
                    && !runtimeAdapterSpec.ownerSha256(className).equals(observedHash)) {
                throw new PreflightFailure("runtime-adapter-class-hash-mismatch");
            }
            for (HookDescriptor descriptor : classHooks) {
                if (!descriptor.classSha256().equals(observedHash)) {
                    throw new PreflightFailure("hook-class-hash-mismatch");
                }
                boolean requiresReceiver = bindings.stream().anyMatch(binding ->
                        binding.role().hookPoint() == descriptor.point()
                                && (binding.source() == ExactMemberBinding.Source.THIS
                                        || binding.source()
                                                == ExactMemberBinding.Source.HIT_CONTEXT));
                verifyExactMethod(source, descriptor, requiresReceiver);
            }
            for (ExactMemberBinding binding : bindings) {
                if (!binding.ownerClass().equals(className)) {
                    continue;
                }
                if (!binding.ownerSha256().equals(observedHash)) {
                    throw new PreflightFailure("runtime-binding-class-hash-mismatch");
                }
                verifyExactMember(source, binding);
            }
            String markerName = markerName(observedHash);
            int markerAccess = markerAccess(source);
            verifyMarkerAbsent(source, markerName);
            byte[] transformedBytes = adviceRenderer.render(
                    className, source, classHooks, markerName, targetLoader);
            prepared.put(
                    className,
                    new PreparedClass(
                            observedHash,
                            transformedBytes,
                            markerName,
                            markerAccess,
                            classHooks.stream()
                                    .map(HookDescriptor::point)
                                    .collect(
                                            () -> EnumSet.noneOf(HookDescriptor.HookPoint.class),
                                            Set::add,
                                            Set::addAll)));
        }
        return Map.copyOf(prepared);
    }

    private static void verifyExactMember(byte[] source, ExactMemberBinding binding)
            throws PreflightFailure {
        int[] matches = {0};
        boolean[] unusable = {false};
        new ClassReader(source)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public net.bytebuddy.jar.asm.FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                if (binding.kind() == ExactMemberBinding.Kind.FIELD
                                        && name.equals(binding.memberName())
                                        && descriptor.equals(binding.descriptor())) {
                                    matches[0]++;
                                    unusable[0] = (access & Opcodes.ACC_STATIC) != 0;
                                }
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (binding.kind() == ExactMemberBinding.Kind.METHOD
                                        && name.equals(binding.memberName())
                                        && descriptor.equals(binding.descriptor())) {
                                    matches[0]++;
                                    unusable[0] = (access
                                                    & (Opcodes.ACC_STATIC
                                                            | Opcodes.ACC_ABSTRACT
                                                            | Opcodes.ACC_NATIVE))
                                            != 0;
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE
                                | ClassReader.SKIP_DEBUG
                                | ClassReader.SKIP_FRAMES);
        if (matches[0] != 1 || unusable[0]) {
            throw new PreflightFailure("runtime-binding-member-mismatch");
        }
    }

    private static byte[] renderAdvice(
            String className,
            byte[] source,
            List<HookDescriptor> descriptors,
            String markerName,
            ClassLoader targetLoader)
            throws PreflightFailure {
        try {
            ClassFileLocator locator =
                    new ClassFileLocator.Compound(
                            ClassFileLocator.Simple.of(className, source),
                            ClassFileLocator.ForClassLoader.of(targetLoader),
                            ClassFileLocator.ForClassLoader.ofBootLoader());
            var type = TypePool.Default.of(locator).describe(className).resolve();
            DynamicType.Builder<?> builder = new ByteBuddy().redefine(type, locator);
            builder = builder
                    .defineField(
                            markerName,
                            boolean.class,
                            markerAccess(source))
                    .value(true);
            for (HookDescriptor descriptor : descriptors) {
                builder =
                        builder.visit(
                                Advice.to(adviceClass(descriptor))
                                        .on(named(descriptor.methodName())
                                                .and(hasDescriptor(
                                                        descriptor.methodDescriptor()))));
            }
            return builder.make().getBytes();
        } catch (RuntimeException | LinkageError error) {
            throw new PreflightFailure("hook-advice-verification-failed");
        }
    }

    private static String markerName(String originalSha256) {
        return "$apollo$native$" + originalSha256.substring(0, 24);
    }

    private static int markerAccess(byte[] source) {
        int[] classAccess = {0};
        new ClassReader(source)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    String signature,
                                    String superName,
                                    String[] interfaces) {
                                classAccess[0] = access;
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        int visibility = (classAccess[0] & Opcodes.ACC_INTERFACE) != 0
                ? Opcodes.ACC_PUBLIC
                : Opcodes.ACC_PRIVATE;
        return visibility | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
    }

    private static void verifyMarkerAbsent(byte[] source, String markerName)
            throws PreflightFailure {
        boolean[] present = {false};
        new ClassReader(source)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public net.bytebuddy.jar.asm.FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    Object value) {
                                if (name.equals(markerName)) {
                                    present[0] = true;
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE
                                | ClassReader.SKIP_DEBUG
                                | ClassReader.SKIP_FRAMES);
        if (present[0]) {
            throw new PreflightFailure("hook-marker-collision");
        }
    }

    private byte[] locateTargetClass(String className) throws PreflightFailure {
        String resource = className.replace('.', '/') + ".class";
        return readResource(targetLoader, resource);
    }

    private static Class<?> adviceClass(HookDescriptor descriptor) {
        boolean valueReturn = descriptor.returnPolicy()
                != HookDescriptor.ReturnPolicy.VOID_SUCCESS
                && descriptor.returnPolicy()
                        != HookDescriptor.ReturnPolicy.PREVALIDATED_VOID;
        return switch (descriptor.point()) {
            case PLAYER_STATE -> valueReturn
                    ? PlayerStateObserver.ObserveValueAdvice.class
                    : PlayerStateObserver.ObserveAdvice.class;
            case ATTACK_OPEN -> valueReturn
                    ? AttackOpenAdvice.class
                    : AttackOpenAdvice.VoidExit.class;
            case HIT_GATE -> HitGateAdvice.class;
            case ZOMBIE_STATE -> valueReturn
                    ? ZombieStateObserver.StateValueAdvice.class
                    : ZombieStateObserver.StateAdvice.class;
            case ZOMBIE_OWNERSHIP -> valueReturn
                    ? ZombieStateObserver.OwnershipValueAdvice.class
                    : ZombieStateObserver.OwnershipAdvice.class;
            case ZOMBIE_KEYFRAME -> valueReturn
                    ? ZombieStateObserver.KeyframeValueAdvice.class
                    : ZombieStateObserver.KeyframeAdvice.class;
            case BRIDGE_PUBLISH -> BridgePublisher.PublishAdvice.class;
        };
    }

    private static void verifyExactMethod(
            byte[] source, HookDescriptor descriptor, boolean requiresReceiver)
            throws PreflightFailure {
        int[] matches = {0};
        boolean[] untransformable = {false};
        boolean[] missingReceiver = {false};
        new ClassReader(source)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String methodDescriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (name.equals(descriptor.methodName())
                                        && methodDescriptor.equals(
                                                descriptor.methodDescriptor())) {
                                    matches[0]++;
                                    untransformable[0] =
                                            (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE))
                                                    != 0;
                                    missingReceiver[0] = requiresReceiver
                                            && (access & Opcodes.ACC_STATIC) != 0;
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE
                                | ClassReader.SKIP_DEBUG
                                | ClassReader.SKIP_FRAMES);
        if (matches[0] != 1) {
            throw new PreflightFailure("hook-method-descriptor-mismatch");
        }
        if (untransformable[0]) {
            throw new PreflightFailure("hook-method-untransformable");
        }
        if (missingReceiver[0]) {
            throw new PreflightFailure("runtime-binding-context-mismatch");
        }
    }

    private static ProductionBindings resolveProductionBindings(
            RuntimeFingerprint fingerprint) throws PreflightFailure {
        try {
            RuntimeAdapterSpec.validateSelection(fingerprint);
        } catch (RuntimeAdapterException error) {
            throw new PreflightFailure(error.reasonCode());
        }
        String selectedKey = null;
        String selectedDescriptor = null;
        for (var entry : fingerprint.methodDescriptors().entrySet()) {
            if (!entry.getKey().startsWith(RUNTIME_BINDINGS_PREFIX)) {
                continue;
            }
            if (selectedKey != null) {
                throw new PreflightFailure("runtime-bindings-duplicate");
            }
            selectedKey = entry.getKey();
            selectedDescriptor = entry.getValue();
        }
        if (selectedKey == null) {
            throw new PreflightFailure("runtime-bindings-missing");
        }
        if (!RUNTIME_BINDINGS_DESCRIPTOR.equals(selectedDescriptor)) {
            throw new PreflightFailure("runtime-bindings-descriptor-mismatch");
        }
        String methodKey = selectedKey.substring(RUNTIME_BINDINGS_PREFIX.length());
        int methodSeparator = methodKey.lastIndexOf('#');
        if (methodSeparator <= 0 || methodSeparator == methodKey.length() - 1) {
            throw new PreflightFailure("runtime-bindings-key-malformed");
        }
        String className = methodKey.substring(0, methodSeparator);
        String methodName = methodKey.substring(methodSeparator + 1);
        if (!RUNTIME_BINDINGS_CLASS.equals(className)
                || !RUNTIME_BINDINGS_METHOD.equals(methodName)) {
            throw new PreflightFailure("runtime-bindings-factory-not-packaged");
        }
        String expectedHash = fingerprint.classHashes().get(className);
        if (expectedHash == null) {
            throw new PreflightFailure("runtime-bindings-class-hash-missing");
        }
        byte[] source = locateAgentClass(className);
        if (source == null) {
            throw new PreflightFailure("runtime-bindings-class-missing");
        }
        if (!expectedHash.equals(sha256(source))) {
            throw new PreflightFailure("runtime-bindings-class-hash-mismatch");
        }
        verifyFactoryMethod(source, methodName, selectedDescriptor);
        try {
            if (RuntimeAdapterSpec.isDeclared(fingerprint)) {
                RuntimeAdapterSpec.requiredFrom(fingerprint);
            } else {
                ExactMemberBinding.requiredFrom(fingerprint);
            }
        } catch (RuntimeAdapterException error) {
            throw new PreflightFailure(error.reasonCode());
        } catch (IllegalArgumentException error) {
            throw new PreflightFailure("runtime-bindings-incomplete");
        }

        try {
            var loader = new VerifiedFactoryLoader(
                    HookInstaller.class.getClassLoader(),
                    className,
                    source,
                    HookInstaller.class.getProtectionDomain());
            Class<?> factory = Class.forName(className, false, loader);
            if (factory.getClassLoader() != loader
                    || factory.getProtectionDomain() != HookInstaller.class.getProtectionDomain()) {
                throw new PreflightFailure("runtime-bindings-factory-identity-mismatch");
            }
            Object resolved = factory.getMethod(methodName, RuntimeFingerprint.class)
                    .invoke(null, fingerprint);
            if (!(resolved instanceof ProductionBindings bindings)) {
                throw new PreflightFailure("runtime-bindings-factory-result-invalid");
            }
            return bindings;
        } catch (PreflightFailure error) {
            throw error;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            throw new PreflightFailure("runtime-bindings-factory-failed");
        }
    }

    private static void verifyFactoryMethod(
            byte[] source, String expectedName, String expectedDescriptor)
            throws PreflightFailure {
        int[] matches = {0};
        boolean[] exactModifiers = {false};
        new ClassReader(source)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                if (name.equals(expectedName)
                                        && descriptor.equals(expectedDescriptor)) {
                                    matches[0]++;
                                    exactModifiers[0] =
                                            (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                                                            == (Opcodes.ACC_PUBLIC
                                                                    | Opcodes.ACC_STATIC)
                                                    && (access
                                                                    & (Opcodes.ACC_ABSTRACT
                                                                            | Opcodes.ACC_NATIVE))
                                                            == 0;
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE
                                | ClassReader.SKIP_DEBUG
                                | ClassReader.SKIP_FRAMES);
        if (matches[0] != 1 || !exactModifiers[0]) {
            throw new PreflightFailure("runtime-bindings-method-mismatch");
        }
    }

    private static byte[] locateAgentClass(String className) throws PreflightFailure {
        String resource = className.replace('.', '/') + ".class";
        byte[] bytes = readResource(HookInstaller.class.getClassLoader(), resource);
        if (bytes == null) {
            throw new PreflightFailure("runtime-bindings-class-missing");
        }
        return bytes;
    }

    private static byte[] readResource(ClassLoader loader, String resource)
            throws PreflightFailure {
        if (loader == null) {
            return null;
        }
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            return stream == null ? null : stream.readAllBytes();
        } catch (IOException error) {
            throw new PreflightFailure("hook-class-read-failed");
        }
    }

    private static String sha256(byte[] bytes) throws PreflightFailure {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new PreflightFailure("sha-256-unavailable");
        }
    }

    private static void rethrowFatalCause(Throwable error) {
        Set<Throwable> observed = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && observed.add(current)) {
            if (current instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (current instanceof Error fatal
                    && "java.lang.ThreadDeath".equals(current.getClass().getName())) {
                throw fatal;
            }
            current = current.getCause();
        }
    }

    private static Set<String> targetClassNames(
            List<HookDescriptor> descriptors,
            List<ExactMemberBinding> bindings,
            RuntimeAdapterSpec runtimeAdapterSpec) {
        var names = new java.util.LinkedHashSet<String>();
        descriptors.forEach(descriptor -> names.add(descriptor.className()));
        bindings.forEach(binding -> names.add(binding.ownerClass()));
        if (runtimeAdapterSpec != null) {
            names.addAll(runtimeAdapterSpec.ownerClasses());
        }
        return Set.copyOf(names);
    }

    private static void rejectAlreadyLoaded(
            Instrumentation instrumentation, Set<String> targetNames)
            throws PreflightFailure {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (targetNames.contains(loaded.getName())) {
                throw new PreflightFailure("hook-class-already-loaded");
            }
        }
    }

    private synchronized void rejectLoadedWithoutTransform(
            Instrumentation instrumentation, Set<String> targetNames)
            throws PreflightFailure {
        Set<String> transformedNames = transformedClassNames();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (targetNames.contains(loaded.getName())
                    && !transformedNames.contains(loaded.getName())) {
                throw new PreflightFailure("hook-class-load-race");
            }
        }
    }

    private synchronized Set<String> transformedClassNames() {
        if (transformer instanceof ExactTransformer exact) {
            return Set.copyOf(exact.transformedClassNames);
        }
        return Set.of();
    }

    private synchronized boolean sameInstallationTuple(
            Instrumentation candidateInstrumentation,
            RuntimeFingerprint candidateFingerprint,
            ClassLoader candidateLoader,
            String candidateBindingsIdentity) {
        return instrumentation == candidateInstrumentation
                && Objects.equals(fingerprint, candidateFingerprint)
                && targetLoader == candidateLoader
                && bindingsIdentity.equals(candidateBindingsIdentity);
    }

    private synchronized void markClaimed(Set<HookDescriptor.HookPoint> points) {
        claimedPoints.addAll(points);
        transformedClassCount++;
    }

    private synchronized void maybeArm() {
        if (!terminal
                && definitionsConfirmed
                && bridgePublished
                && handshakeAccepted
                && transformed.containsAll(expectedHookPoints())) {
            updateStatus(AssistState.READY, "hooks-armed");
        }
    }

    private Set<HookDescriptor.HookPoint> expectedHookPoints() {
        return runtimeAdapterSpec == null
                ? EnumSet.allOf(HookDescriptor.HookPoint.class)
                : EnumSet.copyOf(hookDescriptors.keySet());
    }

    private synchronized void confirmDefinitionsAndPublish(
            Object receiver, Object[] arguments) {
        if (terminal || definitionsConfirmed) {
            if (definitionsConfirmed && !bridgePublished) {
                publisher.publish(receiver, arguments);
            }
            return;
        }
        if (!(transformer instanceof ExactTransformer exact)) {
            incompatible("definition-verification-unavailable");
            return;
        }
        if (runtimeAdapterSpec != null) {
            try {
                for (String owner : runtimeAdapterSpec.ownerClasses()) {
                    Class.forName(owner, false, targetLoader);
                }
            } catch (ClassNotFoundException | LinkageError error) {
                incompatible("runtime-adapter-definition-missing");
                return;
            }
        }
        Map<String, Class<?>> loadedTargets = new LinkedHashMap<>();
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!exact.prepared.containsKey(loaded.getName())) {
                    continue;
                }
                if (loaded.getClassLoader() != targetLoader) {
                    incompatible("definition-loader-mismatch");
                    return;
                }
                if (loadedTargets.putIfAbsent(loaded.getName(), loaded) != null) {
                    incompatible("definition-duplicate-class");
                    return;
                }
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            incompatible("definition-instrumentation-failed");
            return;
        }
        for (var entry : exact.prepared.entrySet()) {
            Class<?> loaded = loadedTargets.get(entry.getKey());
            if (loaded == null) {
                return;
            }
            if (!exact.transformedClassNames.contains(entry.getKey())
                    || loaded.getProtectionDomain() != pinnedTargetDomain
                    || !hasMarker(
                            loaded,
                            entry.getValue().markerName(),
                            entry.getValue().markerAccess())) {
                incompatible("definition-marker-mismatch");
                return;
            }
        }
        try {
            definitionVerifier.verify();
            if (runtimeAdapterSpec != null) {
                runtimeAdapterSpec.verifyDefinitions(
                        targetLoader, pinnedTargetDomain, pinnedTargetCodeSource);
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            rethrowFatalCause(error);
            incompatible("runtime-binding-definition-mismatch");
            return;
        }
        definitionsConfirmed = true;
        transformed.clear();
        transformed.addAll(claimedPoints);
        publisher.publish(receiver, arguments);
        maybeArm();
    }

    private static boolean hasMarker(Class<?> loaded, String markerName, int expectedAccess) {
        try {
            var marker = loaded.getDeclaredField(markerName);
            int modifiers = marker.getModifiers();
            return marker.getType() == boolean.class
                    && marker.isSynthetic()
                    && (modifiers
                                    & (java.lang.reflect.Modifier.PUBLIC
                                            | java.lang.reflect.Modifier.PRIVATE
                                            | java.lang.reflect.Modifier.STATIC
                                            | java.lang.reflect.Modifier.FINAL))
                            == (expectedAccess
                                    & (Opcodes.ACC_PUBLIC
                                            | Opcodes.ACC_PRIVATE
                                            | Opcodes.ACC_STATIC
                                            | Opcodes.ACC_FINAL));
        } catch (NoSuchFieldException | LinkageError error) {
            return false;
        }
    }

    private synchronized void runtimeTransformFailure(String reasonCode) {
        transformFailures++;
        incompatible(reasonCode);
    }

    private synchronized void incompatible(String reasonCode) {
        if (terminal) {
            return;
        }
        terminal = true;
        handshakeAccepted = false;
        adapter.clearConfiguration();
        updateStatus(AssistState.INCOMPATIBLE, reasonCode);
        removeTransformer();
    }

    private synchronized void circuitOpen(String reasonCode) {
        if (terminal) {
            return;
        }
        terminal = true;
        handshakeAccepted = false;
        adapter.clearConfiguration();
        updateStatus(AssistState.CIRCUIT_OPEN, reasonCode);
        removeTransformer();
    }

    private void removeTransformer() {
        if (!transformerRemoved && instrumentation != null && transformer != null) {
            transformerRemoved = true;
            try {
                instrumentation.removeTransformer(transformer);
            } catch (RuntimeException ignored) {
                // Advice remains pass-through because terminal is already visible.
            }
        }
    }

    private BridgeStatus status(AssistState state, String reasonCode) {
        String protocol = fingerprint == null ? "" : fingerprint.bridgeProtocol();
        String identity = fingerprint == null ? "" : fingerprint.fingerprintSha256();
        return new BridgeStatus(state, reasonCode, protocol, identity);
    }

    private void updateStatus(AssistState state, String reasonCode) {
        currentStatus = status(state, reasonCode);
        emitControlMetric();
    }

    private GateResult gateResult() {
        BridgeStatus snapshot = currentStatus;
        return new GateResult(snapshot.state(), snapshot.reasonCode(), "native hook runtime state");
    }

    private final class ExactTransformer implements ClassFileTransformer {
        private final Map<String, PreparedClass> prepared;
        private final Set<String> transformedClassNames = new java.util.LinkedHashSet<>();

        private ExactTransformer(Map<String, PreparedClass> prepared) {
            this.prepared = prepared;
        }

        @Override
        public byte[] transform(
                ClassLoader loader,
                String internalClassName,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer)
                throws IllegalClassFormatException {
            if (internalClassName == null) {
                return null;
            }
            String className = internalClassName.replace('/', '.');
            PreparedClass target = prepared.get(className);
            if (target == null) {
                return null;
            }
            synchronized (HookInstaller.this) {
                if (terminal) {
                    return null;
                }
                if (loader != targetLoader) {
                    runtimeTransformFailure("runtime-loader-mismatch");
                    return null;
                }
                if (!targetDomainPinned) {
                    pinnedTargetDomain = protectionDomain;
                    pinnedTargetCodeSource = protectionDomain == null
                            ? null
                            : protectionDomain.getCodeSource();
                    targetDomainPinned = true;
                } else if (protectionDomain != pinnedTargetDomain
                        || !Objects.equals(
                                protectionDomain == null
                                        ? null
                                        : protectionDomain.getCodeSource(),
                                pinnedTargetCodeSource)) {
                    runtimeTransformFailure("runtime-domain-mismatch");
                    return null;
                }
                if (classBeingRedefined != null || transformedClassNames.contains(className)) {
                    runtimeTransformFailure("runtime-duplicate-transform");
                    return null;
                }
                String observed;
                try {
                    observed = sha256(classfileBuffer);
                } catch (PreflightFailure error) {
                    runtimeTransformFailure(error.reasonCode);
                    return null;
                }
                if (!target.originalSha256().equals(observed)) {
                    runtimeTransformFailure("runtime-class-hash-mismatch");
                    return null;
                }
                transformedClassNames.add(className);
                markClaimed(target.points());
            }
            return target.transformedBytes().clone();
        }
    }

    private enum AdviceCategory {
        PLAYER_STATE("player_state"),
        ATTACK_OPEN("attack_open"),
        HIT_GATE("hit_gate"),
        ZOMBIE_STATE("zombie_state"),
        ZOMBIE_OWNERSHIP("zombie_ownership"),
        ZOMBIE_KEYFRAME("zombie_keyframe"),
        BRIDGE("bridge"),
        THREAD("thread");

        private final String metricSuffix;

        AdviceCategory(String metricSuffix) {
            this.metricSuffix = metricSuffix;
        }
    }

    @FunctionalInterface
    private interface AdviceOperation {
        void run();
    }

    @FunctionalInterface
    interface DefinitionVerifier {
        void verify() throws Throwable;
    }

    @FunctionalInterface
    interface BindingResolver {
        ProductionBindings resolve(RuntimeFingerprint fingerprint) throws Throwable;
    }

    @FunctionalInterface
    interface AdviceRenderer {
        byte[] render(
                String className,
                byte[] source,
                List<HookDescriptor> descriptors,
                String markerName,
                ClassLoader targetLoader)
                throws Throwable;
    }

    private record PreparedClass(
            String originalSha256,
            byte[] transformedBytes,
            String markerName,
            int markerAccess,
            Set<HookDescriptor.HookPoint> points) {}

    public static final class ProductionBindings {
        private final NativeDecisionAdapter.PacketAccess packetAccess;
        private final CollisionProbe collisionProbe;
        private final BridgePublisher.ContextualPublicationTarget publicationTarget;
        private final ClassLoader targetLoader;
        private final List<ExactMemberBinding> memberBindings;
        private final String identity;
        private final RuntimeAdapterSpec runtimeAdapterSpec;
        private final DefinitionVerifier definitionVerifier;
        private final HistoryStore historyStore;

        public ProductionBindings(
                NativeDecisionAdapter.PacketAccess packetAccess,
                CollisionProbe collisionProbe,
                BridgePublisher.PublicationTarget publicationTarget) {
            this(
                    packetAccess,
                    collisionProbe,
                    (receiver, arguments, exports) -> publicationTarget.publishAtomically(exports),
                    Thread.currentThread().getContextClassLoader(),
                    List.of(),
                    "legacy-injected@"
                            + Integer.toHexString(System.identityHashCode(publicationTarget)),
                    null,
                    () -> {},
                    new HistoryStore());
        }

        static ProductionBindings exact(
                NativeDecisionAdapter.PacketAccess packetAccess,
                CollisionProbe collisionProbe,
                BridgePublisher.ContextualPublicationTarget publicationTarget,
                ClassLoader targetLoader,
                List<ExactMemberBinding> memberBindings,
                String identity,
                DefinitionVerifier definitionVerifier) {
            return new ProductionBindings(
                    packetAccess,
                    collisionProbe,
                    publicationTarget,
                    targetLoader,
                    memberBindings,
                    identity,
                    null,
                    definitionVerifier,
                    new HistoryStore());
        }

        public static ProductionBindings exactAdapter(
                NativeDecisionAdapter.PacketAccess packetAccess,
                CollisionProbe collisionProbe,
                BridgePublisher.ContextualPublicationTarget publicationTarget,
                ClassLoader targetLoader,
                RuntimeAdapterSpec runtimeAdapterSpec,
                String identity) {
            return new ProductionBindings(
                    packetAccess,
                    collisionProbe,
                    publicationTarget,
                    targetLoader,
                    List.of(),
                    identity,
                    Objects.requireNonNull(runtimeAdapterSpec, "runtimeAdapterSpec"),
                    () -> {},
                    new HistoryStore());
        }

        public static ProductionBindings exactAdapter(
                NativeDecisionAdapter.PacketAccess packetAccess,
                CollisionProbe collisionProbe,
                BridgePublisher.ContextualPublicationTarget publicationTarget,
                ClassLoader targetLoader,
                RuntimeAdapterSpec runtimeAdapterSpec,
                String identity,
                Runnable definitionVerifier) {
            Objects.requireNonNull(definitionVerifier, "definitionVerifier");
            return new ProductionBindings(
                    packetAccess,
                    collisionProbe,
                    publicationTarget,
                    targetLoader,
                    List.of(),
                    identity,
                    Objects.requireNonNull(runtimeAdapterSpec, "runtimeAdapterSpec"),
                    definitionVerifier::run,
                    new HistoryStore());
        }

        public static ProductionBindings exactAdapter(
                NativeDecisionAdapter.PacketAccess packetAccess,
                CollisionProbe collisionProbe,
                BridgePublisher.ContextualPublicationTarget publicationTarget,
                ClassLoader targetLoader,
                RuntimeAdapterSpec runtimeAdapterSpec,
                String identity,
                Runnable definitionVerifier,
                HistoryStore historyStore) {
            Objects.requireNonNull(definitionVerifier, "definitionVerifier");
            return new ProductionBindings(
                    packetAccess, collisionProbe, publicationTarget, targetLoader, List.of(),
                    identity, Objects.requireNonNull(runtimeAdapterSpec, "runtimeAdapterSpec"),
                    definitionVerifier::run, Objects.requireNonNull(historyStore, "historyStore"));
        }

        private ProductionBindings(
                NativeDecisionAdapter.PacketAccess packetAccess,
                CollisionProbe collisionProbe,
                BridgePublisher.ContextualPublicationTarget publicationTarget,
                ClassLoader targetLoader,
                List<ExactMemberBinding> memberBindings,
                String identity,
                RuntimeAdapterSpec runtimeAdapterSpec,
                DefinitionVerifier definitionVerifier,
                HistoryStore historyStore) {
            this.packetAccess = Objects.requireNonNull(packetAccess, "packetAccess");
            this.collisionProbe = Objects.requireNonNull(collisionProbe, "collisionProbe");
            this.publicationTarget = Objects.requireNonNull(publicationTarget, "publicationTarget");
            this.targetLoader = Objects.requireNonNull(targetLoader, "targetLoader");
            this.memberBindings = List.copyOf(memberBindings);
            this.identity = Objects.requireNonNull(identity, "identity");
            this.runtimeAdapterSpec = runtimeAdapterSpec;
            this.definitionVerifier = Objects.requireNonNull(
                    definitionVerifier, "definitionVerifier");
            this.historyStore = Objects.requireNonNull(historyStore, "historyStore");
        }

        public NativeDecisionAdapter.PacketAccess packetAccess() {
            return packetAccess;
        }

        public CollisionProbe collisionProbe() {
            return collisionProbe;
        }

        BridgePublisher.ContextualPublicationTarget contextualPublicationTarget() {
            return publicationTarget;
        }

        ClassLoader targetLoader() {
            return targetLoader;
        }

        List<ExactMemberBinding> memberBindings() {
            return memberBindings;
        }

        String identity() {
            return identity;
        }

        RuntimeAdapterSpec runtimeAdapterSpec() {
            return runtimeAdapterSpec;
        }

        DefinitionVerifier definitionVerifier() {
            return definitionVerifier;
        }

        HistoryStore historyStore() {
            return historyStore;
        }
    }

    private static final class VerifiedFactoryLoader extends ClassLoader {
        private final String factoryName;
        private final byte[] factoryBytes;
        private final ProtectionDomain factoryDomain;

        private VerifiedFactoryLoader(
                ClassLoader parent,
                String factoryName,
                byte[] factoryBytes,
                ProtectionDomain factoryDomain) {
            super(parent);
            this.factoryName = factoryName;
            this.factoryBytes = factoryBytes.clone();
            this.factoryDomain = factoryDomain;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (factoryName.equals(name)) {
                        loaded = defineClass(
                                name,
                                factoryBytes,
                                0,
                                factoryBytes.length,
                                factoryDomain);
                    } else {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private static final class PreflightFailure extends Exception {
        private final String reasonCode;

        private PreflightFailure(String reasonCode) {
            this.reasonCode = reasonCode;
        }
    }
}
