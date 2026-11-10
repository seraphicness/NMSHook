package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Completely replaces the target method; the original code is not executed.
 * The target overload is the one whose parameter and return types match the handler method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Overwrite {

    /** Target method name. Defaults to the handler method's name. */
    String method() default "";
}
