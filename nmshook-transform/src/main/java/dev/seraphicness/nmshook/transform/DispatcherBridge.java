package dev.seraphicness.nmshook.transform;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/**
 * Reflective access to the {@link HookDispatcher} copy that lives in the <b>bootstrap</b> ClassLoader.
 *
 * <p>The plugin ClassLoader may contain its own copy of {@code HookDispatcher} (it ships in the jar),
 * but only the bootstrap copy is visible to instrumented NMS code, so every interaction goes through here.
 */
public final class DispatcherBridge {

    private final Class<?> dispatcher;
    private final Method setHandlers;
    private final Method setFlags;
    private final Method reset;
    private final Object pass;

    DispatcherBridge(Class<?> dispatcher) throws ReflectiveOperationException {
        if (dispatcher.getClassLoader() != null) {
            throw new IllegalStateException("HookDispatcher is not loaded by the bootstrap ClassLoader");
        }
        this.dispatcher = dispatcher;
        this.setHandlers = dispatcher.getMethod("setHandlers", MethodHandle.class, MethodHandle.class,
                MethodHandle.class);
        this.setFlags = dispatcher.getMethod("setFlags", int.class, int.class);
        this.reset = dispatcher.getMethod("reset");
        this.pass = dispatcher.getField("PASS").get(null);
    }

    public Class<?> dispatcherClass() {
        return dispatcher;
    }

    /** The bootstrap {@code HookDispatcher.PASS} sentinel. */
    public Object pass() {
        return pass;
    }

    public void setHandlers(MethodHandle enter, MethodHandle exit, MethodHandle point) {
        try {
            setHandlers.invoke(null, enter, exit, point);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public void setFlags(int id, int flags) {
        try {
            setFlags.invoke(null, id, flags);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public void reset() {
        try {
            reset.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
