package dev.velvet.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Modifies a specific argument when the target method is invoked.
 * Intercepts and changes the parameter value via Advice before passing it to the original method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModifyArg {

    /** Target method name. */
    String method();

    /** Index of the parameter to modify (0-based). */
    int index() default 0;
}
