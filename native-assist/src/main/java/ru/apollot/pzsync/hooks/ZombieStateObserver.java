package ru.apollot.pzsync.hooks;

import java.util.Objects;
import java.util.Optional;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import ru.apollot.pzsync.history.EntityKey;
import ru.apollot.pzsync.history.EntityKind;
import ru.apollot.pzsync.history.HistoryStore;
import ru.apollot.pzsync.history.StateSample;
import ru.apollot.pzsync.zombie.ZombieAuthorityEpochs;
import ru.apollot.pzsync.zombie.CombatBubble;
import ru.apollot.pzsync.zombie.CombatBubbleIndex;
import ru.apollot.pzsync.zombie.ZombieHandoffPolicy;
import ru.apollot.pzsync.zombie.ZombieKeyframePolicy;

public final class ZombieStateObserver {
    private final HistoryStore history;
    private final ZombieAuthorityEpochs epochs;
    private final ZombieHandoffPolicy handoffPolicy;
    private final ZombieKeyframePolicy keyframePolicy;
    private final CombatBubbleIndex bubbleIndex;

    public ZombieStateObserver(
            HistoryStore history,
            ZombieAuthorityEpochs epochs,
            ZombieHandoffPolicy handoffPolicy,
            ZombieKeyframePolicy keyframePolicy) {
        this(history, epochs, handoffPolicy, keyframePolicy, new CombatBubbleIndex());
    }

    public ZombieStateObserver(
            HistoryStore history,
            ZombieAuthorityEpochs epochs,
            ZombieHandoffPolicy handoffPolicy,
            ZombieKeyframePolicy keyframePolicy,
            CombatBubbleIndex bubbleIndex) {
        this.history = Objects.requireNonNull(history, "history");
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        this.handoffPolicy = Objects.requireNonNull(handoffPolicy, "handoffPolicy");
        this.keyframePolicy = Objects.requireNonNull(keyframePolicy, "keyframePolicy");
        this.bubbleIndex = Objects.requireNonNull(bubbleIndex, "bubbleIndex");
    }

    public boolean observeState(StateObservation observation) {
        if (observation == null || observation.identity().kind() != EntityKind.ZOMBIE) {
            return false;
        }
        long zombieId = observation.identity().id();
        var existingEpoch = epochs.epoch(zombieId);
        long epoch = existingEpoch.isPresent()
                ? existingEpoch.getAsLong()
                : epochs.register(zombieId).orElse(-1);
        if (epoch < 0 || epoch != observation.sample().ownerEpoch()) {
            return false;
        }
        if (!epochs.noteHistorySample(zombieId, epoch, observation.sample().serverTimeMs())) {
            return false;
        }
        boolean added = history.add(observation.identity(), observation.sample());
        if (added && observation.hostilePeer().isPresent()) {
            var sample = observation.sample();
            bubbleIndex.observeHostileInteraction(
                    sample.serverTimeMs(),
                    new CombatBubble.Entity(
                            EntityKind.ZOMBIE,
                            observation.identity().id(),
                            sample.x(),
                            sample.y(),
                            true),
                    observation.hostilePeer().orElseThrow());
        }
        return added;
    }

    public ZombieHandoffPolicy.Decision observeOwnership(OwnershipObservation observation) {
        if (observation == null || observation.identity().kind() != EntityKind.ZOMBIE) {
            return handoffPolicy.observe(null);
        }
        var decision = handoffPolicy.observe(observation.observation());
        if (decision.invalidateHistory()) {
            history.invalidate(observation.identity());
        }
        return decision;
    }

    public boolean observeKeyframe(KeyframeObservation observation) {
        return observation != null && keyframePolicy.keyframeEligible(observation.event());
    }

    public record StateObservation(
            EntityKey identity,
            StateSample sample,
            Optional<CombatBubble.Entity> hostilePeer) {
        public StateObservation {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(sample, "sample");
            Objects.requireNonNull(hostilePeer, "hostilePeer");
        }

        public StateObservation(EntityKey identity, StateSample sample) {
            this(identity, sample, Optional.empty());
        }
    }

    public record OwnershipObservation(
            EntityKey identity, ZombieHandoffPolicy.Observation observation) {
        public OwnershipObservation {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(observation, "observation");
        }
    }

    public record KeyframeObservation(ZombieKeyframePolicy.ZombieKeyframeEvent event) {
        public KeyframeObservation {
            Objects.requireNonNull(event, "event");
        }
    }

    public static final class StateAdvice {
        private StateAdvice() {}

        @Advice.OnMethodEnter
        public static Object enter(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments) {
            return HookInstaller.zombieStateEnter(receiver, arguments);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.Enter Object capture,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.zombieStateExit(capture, null, thrown);
        }
    }

    public static final class StateValueAdvice {
        private StateValueAdvice() {}

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments,
                @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object returned,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.zombieStateExit(receiver, arguments, returned, thrown);
        }
    }

    public static final class OwnershipAdvice {
        private OwnershipAdvice() {}

        @Advice.OnMethodEnter
        public static Object enter(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments) {
            return HookInstaller.zombieOwnershipEnter(receiver, arguments);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.Enter Object capture,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.zombieOwnershipExit(capture, null, thrown);
        }
    }

    public static final class OwnershipValueAdvice {
        private OwnershipValueAdvice() {}

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments,
                @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object returned,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.zombieOwnershipExit(receiver, arguments, returned, thrown);
        }
    }

    public static final class KeyframeAdvice {
        private KeyframeAdvice() {}

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(
                                readOnly = true,
                                typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.zombieKeyframeExit(receiver, arguments, null, thrown);
        }
    }

    public static final class KeyframeValueAdvice {
        private KeyframeValueAdvice() {}

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments,
                @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object returned,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.zombieKeyframeExit(receiver, arguments, returned, thrown);
        }
    }
}
