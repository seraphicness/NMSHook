package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies the injection point for {@link Inject}.
 * <p>Usage example: {@code @Inject(method = "hurt", at = @At("HEAD"))}
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface At {

    String HEAD = "HEAD";
    String TAIL = "TAIL";
    String INVOKE = "INVOKE";
    String RETURN = "RETURN";

    /** Injection point identifier. HEAD, TAIL, INVOKE, RETURN, etc. */
    String value();

    /** Target method descriptor when using INVOKE. */
    String target() default "";

    /** Ordinal for selecting among multiple points of the same type. -1 means all. */
    int ordinal() default -1;
}
