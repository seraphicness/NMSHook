package dev.seraphicness.nmshook.transform;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Custom Advice binding: replaced by the numeric site id (an {@code int} constant) when the
 * advice is inlined, so the dispatcher can find the site with an array lookup.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface HookId {
}
