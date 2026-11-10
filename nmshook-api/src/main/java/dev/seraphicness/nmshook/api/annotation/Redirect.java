package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replaces a method call inside the target method with a call to the handler.
 *
 * <p>Handler signature: {@code (receiver if the call is non-static, call arguments...) -> call return type}.
 * <pre>{@code
 * @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;discard()V"))
 * private void keepAlive(Entity entity) { // skip discard() }
 * }</pre>
 * If the handler throws (or is disabled), the original call is executed instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Redirect {

    /** Target (outer) method, optionally with descriptor. */
    String method();

    /** Must be {@code @At(value = "INVOKE", target = ...)}. */
    At at();
}
