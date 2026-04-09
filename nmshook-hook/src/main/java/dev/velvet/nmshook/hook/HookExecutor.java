package dev.velvet.nmshook.hook;

import dev.velvet.nmshook.api.NMSHookDebug;
import dev.velvet.nmshook.api.hook.HookContext;
import dev.velvet.nmshook.api.hook.HookPoint;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hook execution Domain Service.
 * Called from the HookDispatcher handler function to execute
 * registered listeners in priority order.
 *
 * <p>Execution order:
 * <ol>
 *   <li>REPLACE — if present, skips the original and uses a substitute return value</li>
 *   <li>BEFORE — can be cancelled; if cancelled, the original is skipped</li>
 *   <li>(original method execution)</li>
 *   <li>AFTER — can modify the return value</li>
 * </ol>
 */
public final class HookExecutor {

    private static final Logger LOGGER = Logger.getLogger(HookExecutor.class.getName());

    private final HookRegistry registry;

    public HookExecutor(HookRegistry registry) {
        this.registry = registry;
    }

    public record BeforeResult(boolean cancelled, Object returnValue) {
    }

    /**
     * Executes REPLACE + BEFORE hooks in order.
     *
     * @return if cancelled=true, the original method is skipped
     */
    public BeforeResult handleBeforePhase(Class<?> targetClass, String methodName, HookContext ctx) {
        long start = NMSHookDebug.isEnabled() ? System.nanoTime() : 0;

        // 1. REPLACE hooks (highest priority — only the first is executed)
        List<HookDefinition> replaceHooks = registry.findHooks(targetClass, methodName, HookPoint.REPLACE);
        if (!replaceHooks.isEmpty()) {
            HookDefinition replace = replaceHooks.getFirst();
            try {
                replace.getListener().onHook(ctx);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "REPLACE hook exception: " + replace.getId(), e);
            }
            return new BeforeResult(true, ctx.getReturnValue());
        }

        // 2. BEFORE hooks (in priority order)
        List<HookDefinition> beforeHooks = registry.findHooks(targetClass, methodName, HookPoint.BEFORE);
        for (HookDefinition hook : beforeHooks) {
            try {
                hook.getListener().onHook(ctx);
                if (ctx.isCancelled() && hook.isCancellable()) {
                    return new BeforeResult(true, ctx.getReturnValue());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "BEFORE hook exception: " + hook.getId(), e);
            }
        }

        if (NMSHookDebug.isEnabled()) {
            long elapsed = System.nanoTime() - start;
            if (elapsed > NMSHookDebug.getSlowThreshold()) {
                LOGGER.warning("[Debug] Slow BEFORE phase: %s#%s took %dms"
                        .formatted(targetClass.getSimpleName(), methodName, elapsed / 1_000_000));
            }
        }
        return new BeforeResult(false, null);
    }

    /**
     * Executes AFTER hooks and returns the (possibly modified) return value.
     */
    public Object handleAfterPhase(Class<?> targetClass, String methodName, HookContext ctx) {
        long start = NMSHookDebug.isEnabled() ? System.nanoTime() : 0;

        List<HookDefinition> afterHooks = registry.findHooks(targetClass, methodName, HookPoint.AFTER);
        for (HookDefinition hook : afterHooks) {
            try {
                hook.getListener().onHook(ctx);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "AFTER hook exception: " + hook.getId(), e);
            }
        }

        if (NMSHookDebug.isEnabled()) {
            long elapsed = System.nanoTime() - start;
            if (elapsed > NMSHookDebug.getSlowThreshold()) {
                LOGGER.warning("[Debug] Slow AFTER phase: %s#%s took %dms"
                        .formatted(targetClass.getSimpleName(), methodName, elapsed / 1_000_000));
            }
        }
        return ctx.getReturnValue();
    }
}
