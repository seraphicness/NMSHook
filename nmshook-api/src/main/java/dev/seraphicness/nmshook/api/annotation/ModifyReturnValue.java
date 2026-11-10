package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Modifies the value returned by the target method.
 *
 * <p>Handler signature: {@code R handler(R original)} where {@code R} is the target's return type.
 * Applies to every return instruction unless {@code at.ordinal} selects one.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModifyReturnValue {

    /** Target method, optionally with descriptor. */
    String method();

    At at() default @At(At.RETURN);
}
