package dev.seraphicness.nmshook.transform;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.Arrays;

/**
 * Runtime hook dispatcher. Injected into the <b>bootstrap</b> ClassLoader so that code inlined
 * into NMS classes (loaded by the server ClassLoader) can reach it.
 *
 * <p>This class must only reference JDK types: the plugin side talks to it exclusively through
 * {@link MethodHandle}s installed reflectively with {@link #setHandlers}, so no plugin class ever leaks
 * into the bootstrap CL.
 *
 * <h2>Hot path</h2>
 * Each instrumented method gets a numeric site id baked into its bytecode as a constant.
 * When a site has no active hooks, the inlined code only reads {@link #flags} and returns —
 * no argument array is allocated and no handler is invoked.
 *
 * <h2>Points (INVOKE / Redirect / ModifyVariable / ModifyReturnValue)</h2>
 * Implemented with {@code invokedynamic}: the instruction consumes exactly the operands that are
 * already on the stack and produces the expected type, so the surrounding bytecode and stack map
 * frames are left untouched. See {@link #bootstrap} and {@link #bootstrapRedirect}.
 */
public final class HookDispatcher {

    /** Bumped whenever the shape of this class changes. Checked by the plugin after a reload. */
    public static final int ABI = 3;

    public static final int FLAG_ENTER = 1;
    public static final int FLAG_EXIT = 2;

    public static final int KIND_INJECT = 1;
    public static final int KIND_REDIRECT = 2;
    public static final int KIND_MODIFY_VARIABLE = 3;
    public static final int KIND_MODIFY_RETURN = 4;

    /** Returned by the point handler to request the default behaviour (original call / unchanged value). */
    public static final Object PASS = new Object();

    /** Thrown by a cancelled INVOKE injection; caught by the cancel-catcher advice of the same method. */
    public static final Cancel CANCEL = new Cancel();

    private static final MethodType ENTER_TYPE =
            MethodType.methodType(Object.class, int.class, Object.class, Object[].class);
    private static final MethodType EXIT_TYPE =
            MethodType.methodType(Object.class, int.class, Object.class, Object[].class, Object.class);
    private static final MethodType POINT_TYPE =
            MethodType.methodType(Object.class, int.class, Object[].class);

    /*
     * Handlers live in MutableCallSites reached through static final invokers: the JIT treats the
     * current target as a constant (and deoptimizes when it changes), so the call into the plugin's
     * engine is inlined like a direct call instead of going through a volatile MethodHandle.
     */
    private static final MethodHandle NO_ENTER = MethodHandles.dropArguments(
            MethodHandles.constant(Object.class, null), 0, int.class, Object.class, Object[].class);
    private static final MethodHandle NO_EXIT = MethodHandles.dropArguments(
            MethodHandles.identity(Object.class), 0, int.class, Object.class, Object[].class);
    private static final MutableCallSite ENTER_SITE = new MutableCallSite(NO_ENTER);
    private static final MutableCallSite EXIT_SITE = new MutableCallSite(NO_EXIT);
    private static final MutableCallSite POINT_SITE = new MutableCallSite(POINT_TYPE);
    private static final MethodHandle ENTER = ENTER_SITE.dynamicInvoker();
    private static final MethodHandle EXIT = EXIT_SITE.dynamicInvoker();
    private static final MethodHandle POINT_HANDLER = POINT_SITE.dynamicInvoker();

    private static volatile byte[] flags = new byte[0];

    private static final ThreadLocal<Object> CANCEL_VALUE = new ThreadLocal<>();

    private static final MethodHandle POINT;

    static {
        POINT_SITE.setTarget(MethodHandles.dropArguments(MethodHandles.constant(Object.class, PASS), 0,
                int.class, Object[].class));
        try {
            POINT = MethodHandles.lookup().findStatic(HookDispatcher.class, "point",
                    MethodType.methodType(Object.class, int.class, int.class, MethodHandle.class,
                            boolean.class, Object[].class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private HookDispatcher() {
    }

    // ---------------------------------------------------------------- flags

    public static synchronized void setFlags(int id, int value) {
        byte[] current = flags;
        byte[] next = Arrays.copyOf(current, Math.max(current.length, id + 1));
        next[id] = (byte) value;
        flags = next;
    }

    /**
     * Installs the plugin-side handlers:
     * enter {@code (int id, Object self, Object[] args) -> null | Object[]{returnValue}},
     * exit {@code (int id, Object self, Object[] args, Object returnValue) -> returnValue},
     * point {@code (int id, Object[] operands) -> result | PASS | Object[]{returnValue}}.
     */
    public static synchronized void setHandlers(MethodHandle enter, MethodHandle exit, MethodHandle point) {
        ENTER_SITE.setTarget(enter.asType(ENTER_TYPE));
        EXIT_SITE.setTarget(exit.asType(EXIT_TYPE));
        POINT_SITE.setTarget(point.asType(POINT_TYPE));
        MutableCallSite.syncAll(new MutableCallSite[]{ENTER_SITE, EXIT_SITE, POINT_SITE});
    }

    /** Clears all handlers and flags. Called when the engine shuts down (prevents leaking the plugin CL). */
    public static synchronized void reset() {
        flags = new byte[0];
        ENTER_SITE.setTarget(NO_ENTER);
        EXIT_SITE.setTarget(NO_EXIT);
        POINT_SITE.setTarget(MethodHandles.dropArguments(MethodHandles.constant(Object.class, PASS), 0,
                int.class, Object[].class));
        MutableCallSite.syncAll(new MutableCallSite[]{ENTER_SITE, EXIT_SITE, POINT_SITE});
    }

    public static boolean enterActive(int id) {
        byte[] f = flags;
        return id < f.length && (f[id] & FLAG_ENTER) != 0;
    }

    public static boolean exitActive(int id) {
        byte[] f = flags;
        return id < f.length && (f[id] & FLAG_EXIT) != 0;
    }

    // ---------------------------------------------------------------- method advice

    /** Called from {@code NMSAdvice} on method entry (only when {@link #enterActive}). */
    public static Object enter(int id, Object self, Object[] args) {
        try {
            return (Object) ENTER.invokeExact(id, self, args);
        } catch (Throwable t) {
            report(t);
            return null;
        }
    }

    /** Called from {@code NMSAdvice} on normal method exit (only when {@link #exitActive}). */
    public static Object exit(int id, Object self, Object[] args, Object returnValue) {
        try {
            return (Object) EXIT.invokeExact(id, self, args, returnValue);
        } catch (Throwable t) {
            report(t);
            return returnValue;
        }
    }

    /**
     * Resolves the return value of a skipped (cancelled / replaced) call.
     * A {@code null} replacement keeps the default value (0 / false / null), which is primitive-safe.
     */
    public static Object resolveSkipped(Object enterResult, Object defaultValue) {
        Object value = ((Object[]) enterResult)[0];
        return value != null ? value : defaultValue;
    }

    // ---------------------------------------------------------------- cancel signal (INVOKE inject)

    public static boolean isCancel(Throwable t) {
        return t == CANCEL;
    }

    public static Object takeCancelValue(Object defaultValue) {
        Object value = CANCEL_VALUE.get();
        CANCEL_VALUE.remove();
        return value != null ? value : defaultValue;
    }

    /** Stackless, shared signal. Extends Error so ordinary {@code catch (Exception)} blocks do not swallow it. */
    public static final class Cancel extends Error {
        private static final long serialVersionUID = 1L;

        private Cancel() {
            super("NMSHook cancel signal", null, false, false);
        }
    }

    // ---------------------------------------------------------------- invokedynamic points

    /** Bootstrap for INVOKE inject / ModifyVariable / ModifyReturnValue. */
    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type,
                                     int id, int kind, int hasSelf) {
        return link(type, id, kind, null, hasSelf != 0);
    }

    /** Bootstrap for Redirect; {@code original} is the replaced call, used as fallback. */
    public static CallSite bootstrapRedirect(MethodHandles.Lookup lookup, String name, MethodType type,
                                             int id, int hasSelf, MethodHandle original) {
        return link(type, id, KIND_REDIRECT, original, hasSelf != 0);
    }

    private static CallSite link(MethodType type, int id, int kind, MethodHandle original, boolean hasSelf) {
        MethodHandle target = MethodHandles.insertArguments(POINT, 0, id, kind, original, hasSelf)
                .asCollector(Object[].class, type.parameterCount())
                .asType(type);
        return new ConstantCallSite(target);
    }

    private static Object point(int id, int kind, MethodHandle original, boolean hasSelf,
                                Object[] operands) throws Throwable {
        Object result;
        try {
            result = (Object) POINT_HANDLER.invokeExact(id, operands);
        } catch (Throwable t) {
            report(t);
            result = PASS;
        }
        switch (kind) {
            case KIND_INJECT -> {
                if (result instanceof Object[] cancelled) {
                    CANCEL_VALUE.set(cancelled.length > 0 ? cancelled[0] : null);
                    throw CANCEL;
                }
                return null;
            }
            case KIND_REDIRECT -> {
                if (result != PASS) return result;
                Object[] args = hasSelf ? Arrays.copyOf(operands, operands.length - 1) : operands;
                return original.invokeWithArguments(args);
            }
            default -> {
                // ModifyVariable / ModifyReturnValue: operands[0] is the current value
                return result != PASS ? result : operands[0];
            }
        }
    }

    private static void report(Throwable t) {
        System.err.println("[NMSHook] Hook dispatch failed: " + t);
        t.printStackTrace();
    }
}
