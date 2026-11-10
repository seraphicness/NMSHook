package dev.seraphicness.nmshook.api;

import dev.seraphicness.nmshook.api.hook.HookListener;
import dev.seraphicness.nmshook.api.hook.HookPoint;
import dev.seraphicness.nmshook.api.hook.HookRequest;
import dev.seraphicness.nmshook.api.target.MethodSelector;

/**
 * Fluent builder for configuring lambda hooks.
 * <pre>{@code
 * HookHandle handle = NMSHookLib.hook()
 *     .owner(this)
 *     .target(ServerPlayer.class)
 *     .method("hurtServer")
 *     .params(ServerLevel.class, DamageSource.class, float.class) // optional: pick one overload
 *     .at(HookPoint.BEFORE)
 *     .cancellable(true)
 *     .listener(ctx -> { ... })
 *     .register();
 * }</pre>
 */
public final class HookBuilder {

    private Object owner;
    private Class<?> target;
    private String methodName;
    private String descriptor;
    private HookPoint at;
    private boolean cancellable;
    private int priority = 1000;
    private HookListener listener;

    HookBuilder() {
    }

    public HookBuilder owner(Object owner) {
        this.owner = owner;
        return this;
    }

    public HookBuilder target(Class<?> target) {
        this.target = target;
        return this;
    }

    /** Method name, optionally with a JVM descriptor: {@code "hurtServer"} or {@code "hurtServer(...)Z"}. */
    public HookBuilder method(String method) {
        MethodSelector parsed = MethodSelector.parse(method);
        this.methodName = parsed.name();
        if (parsed.descriptor() != null) this.descriptor = parsed.descriptor();
        return this;
    }

    /** JVM descriptor of the target overload, {@code "(params)"} or {@code "(params)ret"}. */
    public HookBuilder descriptor(String descriptor) {
        this.descriptor = descriptor;
        return this;
    }

    /** Selects the overload taking exactly these parameter types. */
    public HookBuilder params(Class<?>... parameterTypes) {
        this.descriptor = MethodSelector.parameterDescriptor(parameterTypes);
        return this;
    }

    public HookBuilder at(HookPoint at) {
        this.at = at;
        return this;
    }

    public HookBuilder cancellable(boolean cancellable) {
        this.cancellable = cancellable;
        return this;
    }

    /** Lower values run first. Default 1000. */
    public HookBuilder priority(int priority) {
        this.priority = priority;
        return this;
    }

    public HookBuilder listener(HookListener listener) {
        this.listener = listener;
        return this;
    }

    /**
     * Validates the target and installs the hook.
     *
     * @throws dev.seraphicness.nmshook.api.target.HookTargetException if the target method cannot be found or instrumented
     */
    public HookHandle register() {
        if (target == null) throw new IllegalStateException("target is required");
        if (methodName == null) throw new IllegalStateException("method is required");
        if (at == null) throw new IllegalStateException("at (HookPoint) is required");
        if (listener == null) throw new IllegalStateException("listener is required");
        var request = new HookRequest(owner, target, new MethodSelector(methodName, descriptor),
                at, cancellable, priority, listener);
        return NMSHookLib.engine().registerHook(request);
    }
}
