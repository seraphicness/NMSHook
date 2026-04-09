package dev.velvet.nmshook.api.hook;

/**
 * Context passed during lambda hook execution.
 * Provides access to the target instance, arguments, return value, and cancellation control.
 */
public interface HookContext {

    /** The {@code this} instance of the hooked method. Null if static. */
    Object getInstance();

    /** The argument array of the original method. */
    Object[] getArgs();

    /** Returns the argument at the specified index, cast to the desired type. */
    @SuppressWarnings("unchecked")
    default <T> T getArg(int index) {
        return (T) getArgs()[index];
    }

    /** Replaces the argument at the specified index. */
    void setArg(int index, Object value);

    /** Cancels the original method execution. Only effective for cancellable hooks. */
    void cancel();

    boolean isCancelled();

    /** Overrides the return value. Used in AFTER or REPLACE hooks. */
    void setReturnValue(Object value);

    Object getReturnValue();

    String getMethodName();

    Class<?> getTargetClass();
}
