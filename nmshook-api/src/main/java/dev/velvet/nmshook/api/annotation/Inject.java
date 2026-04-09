package dev.velvet.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects code at a specific point in the target method.
 * Serves the same role as Fabric Mixin's {@code @Inject}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Inject {

    /** Target method name. */
    String method();

    /** Injection point. */
    At at();

    /** If true, the original execution can be cancelled via CallbackInfo.cancel(). */
    boolean cancellable() default false;
}
