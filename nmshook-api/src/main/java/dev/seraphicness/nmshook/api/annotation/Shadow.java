package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Makes a field or method of the target class accessible from the Mixin class.
 * <ul>
 *   <li>Fields: copied from the target instance before the handler runs and written back afterwards
 *       (matched by name, searched up the class hierarchy).</li>
 *   <li>Methods: declare them {@code abstract}; calls are forwarded to the target instance's method
 *       with the same name and parameter types.</li>
 * </ul>
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Shadow {
}
