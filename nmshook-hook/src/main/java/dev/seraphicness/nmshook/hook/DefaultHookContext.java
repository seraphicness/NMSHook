package dev.seraphicness.nmshook.hook;

import dev.seraphicness.nmshook.api.hook.HookContext;

/**
 * Default implementation of {@link HookContext}.
 *
 * <p>The {@code args} array is written back to the method parameters after the BEFORE phase,
 * so {@link #setArg} changes the arguments the original method receives.
 */
public final class DefaultHookContext implements HookContext {

    private final Class<?> targetClass;
    private final String methodName;
    private final Object instance;
    private final Object[] args;
    private final Class<?>[] parameterTypes;
    private boolean cancelled;
    private Object returnValue;

    /**
     * @param parameterTypes parameter types of the hooked method when known unambiguously (used to
     *                       validate {@link #setArg}), or {@code null}
     */
    public DefaultHookContext(Class<?> targetClass, String methodName, Object instance, Object[] args,
                              Class<?>[] parameterTypes) {
        this.targetClass = targetClass;
        this.methodName = methodName;
        this.instance = instance;
        this.args = args;
        this.parameterTypes = parameterTypes;
    }

    @Override
    public Object getInstance() {
        return instance;
    }

    @Override
    public Object[] getArgs() {
        return args;
    }

    @Override
    public void setArg(int index, Object value) {
        if (index < 0 || index >= args.length) {
            throw new IndexOutOfBoundsException("Argument index " + index + " out of range for "
                    + methodName + " (" + args.length + " arguments)");
        }
        if (parameterTypes != null && index < parameterTypes.length) {
            Class<?> type = parameterTypes[index];
            if (value == null ? type.isPrimitive() : !Types.box(type).isInstance(value)) {
                throw new IllegalArgumentException("Argument " + index + " of " + methodName + " is "
                        + type.getName() + ", got " + (value == null ? "null" : value.getClass().getName()));
            }
        }
        args[index] = value;
    }

    @Override
    public void cancel() {
        this.cancelled = true;
    }

    /** Used by the executor to drop a cancel request that is not allowed. */
    public void clearCancelled() {
        this.cancelled = false;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setReturnValue(Object value) {
        this.returnValue = value;
    }

    @Override
    public Object getReturnValue() {
        return returnValue;
    }

    @Override
    public String getMethodName() {
        return methodName;
    }

    @Override
    public Class<?> getTargetClass() {
        return targetClass;
    }
}
