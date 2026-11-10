package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Modifies a local variable of the target method when it is stored ({@code STORE}, default) or loaded ({@code LOAD}).
 *
 * <p>Handler signature: {@code T handler(T value)} where {@code T} is exactly the variable's type.
 * {@code index} is the JVM local variable slot (0 = {@code this} for instance methods; long/double take two slots).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ModifyVariable {

    /** Target method, optionally with descriptor. */
    String method();

    /** Local variable slot. */
    int index();

    /** {@code @At("STORE")} or {@code @At("LOAD")}; {@code ordinal} selects the occurrence. */
    At at() default @At(At.STORE);
}
