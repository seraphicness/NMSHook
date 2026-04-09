package dev.velvet.nmshook.mixin;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Entity representing an individual handler method within a Mixin class.
 * Holds the annotation type (@Inject, @Overwrite, @ModifyArg, etc.) and target method information.
 */
public final class MixinMethod {

    public enum Type {
        INJECT_HEAD,
        INJECT_TAIL,
        INJECT_INVOKE,
        OVERWRITE,
        MODIFY_ARG,
        SHADOW
    }

    private final Method handlerMethod;
    private final Type type;
    private final String targetMethodName;
    private final boolean cancellable;
    private final int argIndex;

    public MixinMethod(Method handlerMethod,
                       Type type,
                       String targetMethodName,
                       boolean cancellable,
                       int argIndex) {
        this.handlerMethod = handlerMethod;
        this.type = type;
        this.targetMethodName = targetMethodName;
        this.cancellable = cancellable;
        this.argIndex = argIndex;
    }

    public Method getHandlerMethod() {
        return handlerMethod;
    }

    public Type getType() {
        return type;
    }

    public String getTargetMethodName() {
        return targetMethodName;
    }

    public boolean isCancellable() {
        return cancellable;
    }

    public int getArgIndex() {
        return argIndex;
    }
}
