package ru.apollot.pzsync.hooks;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class HitGateAdvice {
    private HitGateAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(
            @Advice.This(optional = true) Object receiver,
            @Advice.AllArguments(readOnly = true, typing = Assigner.Typing.DYNAMIC)
                    Object[] arguments) {
        return HookInstaller.hitEnter(receiver, arguments);
    }
}
