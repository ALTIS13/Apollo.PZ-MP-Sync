package ru.apollot.pzsync.hooks;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class AttackOpenAdvice {
    private AttackOpenAdvice() {}

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
            @Advice.This(optional = true) Object receiver,
            @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                    Object[] arguments,
            @Advice.Return(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                    Object returned,
            @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                    Throwable thrown) {
        HookInstaller.attackOpenExit(receiver, arguments, returned, thrown);
    }

    public static final class VoidExit {
        private VoidExit() {}

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(
                @Advice.This(optional = true) Object receiver,
                @Advice.AllArguments(
                                readOnly = true,
                                typing = Assigner.Typing.DYNAMIC)
                        Object[] arguments,
                @Advice.Thrown(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                        Throwable thrown) {
            HookInstaller.attackOpenExit(receiver, arguments, null, thrown);
        }
    }
}
