package ru.apollot.pzsync.bridge;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class BridgePublisher {
    private final ApolloNativeBridge bridge;
    private final Control control;
    private final ContextualPublicationTarget target;
    private final AtomicBoolean attempted = new AtomicBoolean();

    public BridgePublisher(Thread mainThread, Control control, PublicationTarget target) {
        this(mainThread, control, (receiver, arguments, exports) -> target.publishAtomically(exports));
    }

    public BridgePublisher(
            Thread mainThread, Control control, ContextualPublicationTarget target) {
        this.bridge = new ApolloNativeBridge(mainThread, control);
        this.control = Objects.requireNonNull(control, "control");
        this.target = Objects.requireNonNull(target, "target");
    }

    public void publish(Object receiver, Object[] arguments) {
        if (!attempted.compareAndSet(false, true)) {
            return;
        }
        try {
            target.publishAtomically(
                    receiver,
                    arguments == null ? new Object[0] : arguments.clone(),
                    new Exports(
                            bridge::status,
                            bridge::metrics,
                            bridge::handshake,
                            control::bridgeFailure));
            control.bridgePublished();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            if (error instanceof Error fatal
                    && "java.lang.ThreadDeath".equals(error.getClass().getName())) {
                throw fatal;
            }
            control.bridgeFailure("bridge-publication-failed");
        }
    }

    @FunctionalInterface
    public interface PublicationTarget {
        void publishAtomically(Exports exports) throws Exception;
    }

    @FunctionalInterface
    public interface ContextualPublicationTarget {
        void publishAtomically(Object receiver, Object[] arguments, Exports exports)
                throws Exception;
    }

    public interface Control {
        BridgeStatus status();

        Map<String, Long> metrics();

        BridgeStatus handshake(ApolloNativeBridge.Handshake handshake);

        void bridgePublished();

        void bridgeFailure(String reasonCode);
    }

    public static final class PublishAdvice {
        private PublishAdvice() {}

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(
                                readOnly = true,
                                typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            ru.apollot.pzsync.hooks.HookInstaller.bridgeExit(receiver, arguments, thrown);
        }
    }

    public record Exports(
            StatusCall status,
            MetricsCall metrics,
            HandshakeCall handshake,
            FailureCall failClosed) {
        public Exports {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(handshake, "handshake");
            Objects.requireNonNull(failClosed, "failClosed");
        }
    }

    @FunctionalInterface
    public interface StatusCall {
        BridgeStatus invoke();
    }

    @FunctionalInterface
    public interface MetricsCall {
        Map<String, Long> invoke();
    }

    @FunctionalInterface
    public interface HandshakeCall {
        BridgeStatus invoke(ApolloNativeBridge.Handshake handshake);
    }

    @FunctionalInterface
    public interface FailureCall {
        void invoke(String reasonCode);
    }
}
