package dev.seraphicness.nmshook.mixin;

import dev.seraphicness.nmshook.api.target.MethodSelector;

import java.lang.reflect.Method;

/**
 * One handler method of a Mixin class, with its target method and injection point.
 */
public final class MixinMethod {

    public enum Type {
        INJECT_HEAD,
        INJECT_TAIL,
        INJECT_INVOKE,
        OVERWRITE,
        MODIFY_ARG,
        REDIRECT,
        MODIFY_VARIABLE,
        MODIFY_RETURN_VALUE;

        /** Implemented by rewriting instructions inside the method (vs. method entry/exit advice). */
        public boolean isPoint() {
            return this == INJECT_INVOKE || this == REDIRECT || this == MODIFY_VARIABLE || this == MODIFY_RETURN_VALUE;
        }
    }

    private final Method handlerMethod;
    private final Type type;
    private final MethodSelector target;
    private final boolean cancellable;
    private final int argIndex;
    private final String invokeTarget;
    private final boolean shiftAfter;
    private final int ordinal;
    private final int localIndex;
    private final boolean localLoad;

    private MixinMethod(Method handlerMethod, Type type, MethodSelector target, boolean cancellable, int argIndex,
                        String invokeTarget, boolean shiftAfter, int ordinal, int localIndex, boolean localLoad) {
        this.handlerMethod = handlerMethod;
        this.type = type;
        this.target = target;
        this.cancellable = cancellable;
        this.argIndex = argIndex;
        this.invokeTarget = invokeTarget;
        this.shiftAfter = shiftAfter;
        this.ordinal = ordinal;
        this.localIndex = localIndex;
        this.localLoad = localLoad;
    }

    public static MixinMethod method(Method handler, Type type, MethodSelector target, boolean cancellable,
                                     int argIndex) {
        return new MixinMethod(handler, type, target, cancellable, argIndex, null, false, -1, -1, false);
    }

    public static MixinMethod invoke(Method handler, Type type, MethodSelector target, boolean cancellable,
                                     String invokeTarget, boolean shiftAfter, int ordinal) {
        return new MixinMethod(handler, type, target, cancellable, -1, invokeTarget, shiftAfter, ordinal, -1, false);
    }

    public static MixinMethod variable(Method handler, MethodSelector target, int localIndex, boolean load,
                                       int ordinal) {
        return new MixinMethod(handler, Type.MODIFY_VARIABLE, target, false, -1, null, false, ordinal, localIndex, load);
    }

    public static MixinMethod returnValue(Method handler, MethodSelector target, int ordinal) {
        return new MixinMethod(handler, Type.MODIFY_RETURN_VALUE, target, false, -1, null, false, ordinal, -1, false);
    }

    public Method getHandlerMethod() { return handlerMethod; }
    public Type getType() { return type; }
    public MethodSelector getTarget() { return target; }
    public String getTargetMethodName() { return target.name(); }
    public boolean isCancellable() { return cancellable; }
    public int getArgIndex() { return argIndex; }
    public String getInvokeTarget() { return invokeTarget; }
    public boolean isShiftAfter() { return shiftAfter; }
    public int getOrdinal() { return ordinal; }
    public int getLocalIndex() { return localIndex; }
    public boolean isLocalLoad() { return localLoad; }

    public String describe() {
        return handlerMethod.getDeclaringClass().getSimpleName() + "#" + handlerMethod.getName();
    }
}
