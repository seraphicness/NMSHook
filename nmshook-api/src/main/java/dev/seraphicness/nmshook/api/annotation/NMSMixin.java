package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a class as a Mixin.
 * Allows intercepting or modifying the behavior of the NMS class specified by {@code target}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface NMSMixin {

    /** Target NMS class to hook. Can be referenced directly as Class<?> thanks to paperweight. */
    Class<?> target();

    /** Priority. Lower values are applied first. Default is 1000. */
    int priority() default 1000;
}
