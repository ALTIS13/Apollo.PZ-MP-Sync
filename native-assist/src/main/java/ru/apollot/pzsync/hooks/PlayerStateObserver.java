package ru.apollot.pzsync.hooks;

import java.util.Objects;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import ru.apollot.pzsync.history.EntityKey;
import ru.apollot.pzsync.history.EntityKind;
import ru.apollot.pzsync.history.HistoryStore;
import ru.apollot.pzsync.history.StateSample;

public final class PlayerStateObserver {
    private final HistoryStore history;

    public PlayerStateObserver(HistoryStore history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    public boolean observe(Observation observation) {
        return observation != null
                && observation.identity().kind() == EntityKind.PLAYER
                && history.add(observation.identity(), observation.sample());
    }

    public record Observation(EntityKey identity, StateSample sample) {
        public Observation {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(sample, "sample");
        }
    }

    public static final class ObserveAdvice {
        private ObserveAdvice() {}

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(
                                readOnly = true,
                                typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.playerStateExit(receiver, arguments, null, thrown);
        }
    }

    public static final class ObserveValueAdvice {
        private ObserveValueAdvice() {}

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(
                                readOnly = true,
                                typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments,
                @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Object returned,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.playerStateExit(receiver, arguments, returned, thrown);
        }
    }
}
