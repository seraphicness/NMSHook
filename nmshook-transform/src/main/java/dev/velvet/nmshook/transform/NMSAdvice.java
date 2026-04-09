package dev.velvet.nmshook.transform;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy Advice template. Inlined into the target NMS method.
 *
 * <p>On Enter, calls {@link HookDispatcher#dispatchBefore}, and if the
 * return value is true (non-default), skips the original method (skipOn).
 * On Exit, calls {@link HookDispatcher#dispatchAfter} to execute
 * AFTER hooks or apply replacement return values.
 *
 * <p>This class is compiled in the plugin CL, but at inline time the bytecode
 * is copied and executed in the target CL (server CL). Since {@code HookDispatcher}
 * is injected into the bootstrap CL, it is accessible from all CLs.
 */
public final class NMSAdvice {

    private NMSAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter(
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args,
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName) {
        return HookDispatcher.dispatchBefore(self, args, className, methodName);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
            @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args,
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Enter boolean skipped,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue) {
        returnValue = HookDispatcher.dispatchAfter(self, args, className, methodName, returnValue, skipped);
    }
}
