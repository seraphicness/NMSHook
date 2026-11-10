package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies an injection point.
 * <ul>
 *   <li>{@code HEAD} / {@code TAIL} (= {@code RETURN}) — for {@link Inject}</li>
 *   <li>{@code INVOKE} — at a method call inside the target method ({@link Inject}, {@link Redirect});
 *       {@link #target()} names the call, e.g. {@code "Lnet/minecraft/world/entity/Entity;discard()V"}</li>
 *   <li>{@code LOAD} / {@code STORE} — reads / writes of a local variable ({@link ModifyVariable})</li>
 *   <li>{@code RETURN} — return instructions ({@link ModifyReturnValue})</li>
 * </ul>
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface At {

    String HEAD = "HEAD";
    String TAIL = "TAIL";
    String INVOKE = "INVOKE";
    String RETURN = "RETURN";
    String LOAD = "LOAD";
    String STORE = "STORE";

    /** Where to inject, relative to the matched instruction (INVOKE only). */
    enum Shift { BEFORE, AFTER }

    /** Injection point identifier. */
    String value();

    /**
     * Call to match when using INVOKE: {@code "Lowner/Class;name(desc)ret"} or {@code "Lowner/Class;name"}
     * (any descriptor). Dotted class names are accepted.
     */
    String target() default "";

    /** Which occurrence to use (0-based). -1 means all. */
    int ordinal() default -1;

    /** INVOKE only: inject before or after the call. */
    Shift shift() default Shift.BEFORE;
}
