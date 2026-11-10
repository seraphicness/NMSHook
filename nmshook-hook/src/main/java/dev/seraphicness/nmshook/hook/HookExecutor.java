package dev.seraphicness.nmshook.hook;

import dev.seraphicness.nmshook.api.NMSHookDebug;

import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes hooks with failure isolation.
 *
 * <ul>
 *   <li>A throwing hook never breaks the hooked NMS method: the failure is logged and the
 *       method continues as if the hook were absent.</li>
 *   <li>Circuit breaker: after {@link #getFailureThreshold()} consecutive failures the hook is
 *       disabled (re-enable with {@code /nmshook enable <id>}). The first failure is logged with its
 *       stack trace, later ones only at FINE level, so a broken hook cannot flood the console.</li>
 *   <li>Statistics: call counts always; execution time when profiling/debug is on.</li>
 * </ul>
 *
 * <p>Execution order for a method: REPLACE (first enabled one) → BEFORE (priority order, may cancel)
 * → original → AFTER (priority order, may change the return value).
 */
public final class HookExecutor {

    private final Logger logger;
    private volatile int failureThreshold = 10;

    public HookExecutor(Logger logger) {
        this.logger = logger;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    /** 0 disables the circuit breaker. */
    public void setFailureThreshold(int threshold) {
        this.failureThreshold = Math.max(0, threshold);
    }

    /**
     * Runs REPLACE + BEFORE hooks.
     *
     * @return {@code null} to run the original method, or {@code Object[]{returnValue}} to skip it
     */
    public Object[] runEnter(HookDefinition[] replace, HookDefinition[] before, DefaultHookContext ctx) {
        long phaseStart = NMSHookDebug.isEnabled() ? System.nanoTime() : 0;
        try {
            for (HookDefinition hook : replace) {
                if (!hook.isEnabled()) continue;
                if (invoke(hook, ctx)) {
                    return new Object[]{ctx.getReturnValue()};
                }
                // a failed REPLACE falls back to the original method
                break;
            }
            for (HookDefinition hook : before) {
                if (!hook.isEnabled()) continue;
                boolean ok = invoke(hook, ctx);
                if (ctx.isCancelled()) {
                    if (ok && hook.isCancellable()) return new Object[]{ctx.getReturnValue()};
                    // cancel() from a non-cancellable (or failed) hook is ignored and must not leak
                    // into the next hook
                    ctx.clearCancelled();
                }
            }
            return null;
        } finally {
            logSlowPhase("BEFORE", ctx, phaseStart);
        }
    }

    /** Runs AFTER hooks; returns the (possibly modified) return value from the context. */
    public Object runExit(HookDefinition[] after, DefaultHookContext ctx) {
        long phaseStart = NMSHookDebug.isEnabled() ? System.nanoTime() : 0;
        for (HookDefinition hook : after) {
            if (hook.isEnabled()) invoke(hook, ctx);
        }
        logSlowPhase("AFTER", ctx, phaseStart);
        return ctx.getReturnValue();
    }

    /**
     * Runs a point handler.
     *
     * @return the handler result, or {@code pass} if the hook is disabled or failed
     */
    public Object runPoint(HookDefinition hook, Object[] operands, Object pass) {
        if (!hook.isEnabled()) return pass;
        boolean timed = NMSHookDebug.isProfiling();
        long start = timed ? System.nanoTime() : 0;
        try {
            Object result = hook.getPointHandler().handle(operands);
            hook.recordSuccess();
            return result;
        } catch (Throwable t) {
            onFailure(hook, t);
            return pass;
        } finally {
            hook.recordCall(timed ? System.nanoTime() - start : 0);
        }
    }

    /** Reports a failure detected outside the handler (e.g. a result of the wrong type). */
    public void reportFailure(HookDefinition hook, Throwable t) {
        onFailure(hook, t);
    }

    /** @return true if the listener completed normally */
    private boolean invoke(HookDefinition hook, DefaultHookContext ctx) {
        boolean timed = NMSHookDebug.isProfiling();
        long start = timed ? System.nanoTime() : 0;
        try {
            hook.getListener().onHook(ctx);
            hook.recordSuccess();
            return true;
        } catch (Throwable t) {
            onFailure(hook, t);
            return false;
        } finally {
            hook.recordCall(timed ? System.nanoTime() - start : 0);
        }
    }

    private void onFailure(HookDefinition hook, Throwable t) {
        Throwable cause = unwrap(t);
        if (cause instanceof VirtualMachineError vme) throw vme;
        int consecutive = hook.recordFailure();
        int threshold = failureThreshold;
        if (consecutive == 1) {
            logger.log(Level.WARNING, "Hook " + hook + " threw an exception", cause);
        } else {
            logger.log(Level.FINE, "Hook " + hook + " failed again (" + consecutive + " in a row)", cause);
        }
        if (threshold > 0 && consecutive >= threshold && hook.isEnabled()) {
            hook.disable(consecutive + " consecutive failures, last: " + cause);
            logger.severe("Hook " + hook + " disabled after " + consecutive + " consecutive failures (last: "
                    + cause + "). Fix it and run /nmshook enable " + hook.getId());
        }
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException ite && ite.getCause() != null) {
            t = ite.getCause();
        }
        return t;
    }

    private void logSlowPhase(String phase, DefaultHookContext ctx, long start) {
        if (start == 0) return;
        long elapsed = System.nanoTime() - start;
        if (elapsed > NMSHookDebug.getSlowThreshold()) {
            logger.warning("[Debug] Slow %s phase: %s#%s took %.2fms".formatted(phase,
                    ctx.getTargetClass().getSimpleName(), ctx.getMethodName(), elapsed / 1_000_000.0));
        }
    }
}
