package dev.seraphicness.nmshook.transform;

import java.util.function.Function;

/**
 * Runtime hook dispatcher. Injected into the bootstrap ClassLoader
 * so it is accessible from all NMS classes.
 *
 * <p>Advice inline code calls the static methods of this class,
 * and dispatches to the plugin CL's HookExecutor via handler functions.
 *
 * <p>All field types are from {@code java.util.function} (bootstrap CL),
 * so lambdas can be assigned directly from the plugin CL.
 */
public final class HookDispatcher {

    /** before -> [cancelled(Boolean), returnValue(Object)] */
    public static volatile Function<Object[], Object[]> beforeHandler;

    /** after -> modifiedReturnValue(Object) */
    public static volatile Function<Object[], Object> afterHandler;

    /** ThreadLocal that passes the return value from before to after on REPLACE/cancel. */
    private static final ThreadLocal<Object[]> CONTEXT = new ThreadLocal<>();

    private HookDispatcher() {
    }

    /**
     * Called from Advice.OnMethodEnter.
     * Returns true on REPLACE or BEFORE(cancel) to skip the original method.
     */
    public static boolean dispatchBefore(Object instance, Object[] args,
                                         String className, String methodName) {
        Function<Object[], Object[]> h = beforeHandler;
        if (h == null) return false;
        try {
            Object[] result = h.apply(new Object[]{instance, args, className, methodName});
            if (result != null && (boolean) result[0]) {
                CONTEXT.set(result);
                return true;
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return false;
    }

    /**
     * Called from Advice.OnMethodExit.
     * If skipped=true, retrieves the replacement return value from CONTEXT.
     * If false, executes AFTER hooks to potentially modify the return value.
     */
    public static Object dispatchAfter(Object instance, Object[] args,
                                       String className, String methodName,
                                       Object returnValue, boolean skipped) {
        try {
            if (skipped) {
                Object[] ctx = CONTEXT.get();
                // If replacement return value is null, keep the original default (primitive-safe: boolean->false, int->0, etc.)
                if (ctx != null && ctx[1] != null) {
                    return ctx[1];
                }
                return returnValue;
            }
            Function<Object[], Object> h = afterHandler;
            if (h == null) return returnValue;
            return h.apply(new Object[]{instance, args, className, methodName, returnValue});
        } catch (Throwable t) {
            t.printStackTrace();
            return returnValue;
        } finally {
            CONTEXT.remove();
        }
    }
}
