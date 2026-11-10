package dev.seraphicness.nmshook.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects code at a specific point in the target method.
 * Serves the same role as Fabric Mixin's {@code @Inject}.
 *
 * <p>Handler signature: {@code (target parameters..., CallbackInfo)} — or {@code CallbackInfoReturnable<R>}
 * to read/override the return value. The target parameters may be omitted entirely.
 * <ul>
 *   <li>{@code @At("HEAD")} — before the method body; {@code ci.cancel()} skips the original.</li>
 *   <li>{@code @At("TAIL")} / {@code @At("RETURN")} — after the method returns.</li>
 *   <li>{@code @At(value = "INVOKE", target = ..., shift = ...)} — before/after a call inside the method;
 *       {@code ci.cancel()} (with {@code cancellable = true}) returns from the target method immediately.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Inject {

    /**
     * Target method name, optionally with a JVM descriptor ({@code "name(params)ret"}).
     * For @Inject without a descriptor, the parameter types are inferred from the handler
     * when it declares the target's parameters before the CallbackInfo.
     */
    String method();

    /** Injection point. */
    At at();

    /** If true, the original execution can be cancelled via CallbackInfo.cancel(). */
    boolean cancellable() default false;
}
