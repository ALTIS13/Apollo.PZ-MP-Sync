package ru.apollot.pzsync.hooks;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class HitGateAdvice {
    private HitGateAdvice() {}

    @Advice.OnMethodEnter
    public static void enter(
            @Advice.This(optional = true) Object receiver,
            @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                    Object[] arguments) {
        HookInstaller.hitEnter(receiver, arguments);
    }
}
