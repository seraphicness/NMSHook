package dev.seraphicness.nmshook.transform;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Installed on methods that have a cancellable INVOKE injection. A cancelled injection throws
 * {@link HookDispatcher#CANCEL}; this advice turns it into a normal return with the requested value.
 * Using an exception (instead of an inserted branch + return) keeps the method's stack map frames
 * intact, so no frame recomputation is needed.
 */
public final class CancelCatchAdvice {

    private CancelCatchAdvice() {
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
            @Advice.Thrown(readOnly = false, typing = Assigner.Typing.DYNAMIC) Throwable thrown,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue) {
        if (HookDispatcher.isCancel(thrown)) {
            returnValue = HookDispatcher.takeCancelValue(returnValue);
            thrown = null;
        }
    }
}
