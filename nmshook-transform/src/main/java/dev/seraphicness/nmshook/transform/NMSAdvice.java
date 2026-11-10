package dev.seraphicness.nmshook.transform;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy Advice template inlined into hooked NMS methods.
 *
 * <p>When the site has no active BEFORE/REPLACE (enter) or AFTER (exit) hooks, the inlined code
 * reads one flag and does nothing else: the argument array is only materialized inside the branch.
 *
 * <p>This class is never loaded for execution; ByteBuddy copies its bytecode into the target method,
 * where {@link HookDispatcher} resolves to the copy in the bootstrap ClassLoader.
 */
public final class NMSAdvice {

    private NMSAdvice() {
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object enter(
            @HookId int id,
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args) {
        if (!HookDispatcher.enterActive(id)) {
            return null;
        }
        Object[] copy = args;
        Object result = HookDispatcher.enter(id, self, copy);
        args = copy; // writes argument modifications (setArg / @ModifyArg) back to the parameters
        return result;
    }

    @Advice.OnMethodExit
    public static void exit(
            @HookId int id,
            @Advice.This(optional = true, typing = Assigner.Typing.DYNAMIC) Object self,
            @Advice.AllArguments(typing = Assigner.Typing.DYNAMIC) Object[] args,
            @Advice.Enter Object skipped,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returnValue) {
        if (skipped != null) {
            returnValue = HookDispatcher.resolveSkipped(skipped, returnValue);
        } else if (HookDispatcher.exitActive(id)) {
            returnValue = HookDispatcher.exit(id, self, args, returnValue);
        }
    }
}
