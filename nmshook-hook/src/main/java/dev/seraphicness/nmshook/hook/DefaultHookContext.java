package dev.seraphicness.nmshook.hook;

import dev.seraphicness.nmshook.api.hook.HookContext;

/**
 * Default implementation of {@link HookContext}.
 * Created along the Advice inline code -> HookDispatcher -> HookExecutor path.
 *
 * <p>The {@code args} array shares the same reference as the ByteBuddy Advice
 * {@code @AllArguments(readOnly=false)} array, so modifying it via {@link #setArg}
 * changes the actual method parameters.
 */
public final class DefaultHookContext implements HookContext {

    private final Object instance;
    private final Object[] args;
    private final String className;
    private final String methodName;
    private final Class<?> targetClass;
    private boolean cancelled;
    private Object returnValue;

    public DefaultHookContext(Object instance, Object[] args,
                              String className, String methodName) {
        this.instance = instance;
        this.args = args;
        this.className = className;
        this.methodName = methodName;
        Class<?> resolved;
        try {
            resolved = Class.forName(className);
        } catch (ClassNotFoundException e) {
            resolved = null;
        }
        this.targetClass = resolved;
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
        args[index] = value;
    }

    @Override
    public void cancel() {
        this.cancelled = true;
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
        if (targetClass != null) return targetClass;
        throw new IllegalStateException("Target class not found: " + className);
    }
}
