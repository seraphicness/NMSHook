package dev.velvet.nmshook.api;

import dev.velvet.nmshook.api.hook.HookListener;
import dev.velvet.nmshook.api.hook.HookPoint;
import dev.velvet.nmshook.api.hook.HookRequest;

/**
 * Fluent builder for configuring lambda hooks.
 * <pre>{@code
 * NMSHookLib.hook()
 *     .target(ServerPlayer.class)
 *     .method("hurt")
 *     .at(HookPoint.BEFORE)
 *     .cancellable(true)
 *     .priority(10)
 *     .listener(ctx -> { ... })
 *     .register();
 * }</pre>
 */
public final class HookBuilder {

    private Object owner;
    private Class<?> target;
    private String method;
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

    public HookBuilder method(String method) {
        this.method = method;
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

    public HookBuilder priority(int priority) {
        this.priority = priority;
        return this;
    }

    public HookBuilder listener(HookListener listener) {
        this.listener = listener;
        return this;
    }

    public void register() {
        if (target == null) throw new IllegalStateException("target is required");
        if (method == null) throw new IllegalStateException("method is required");
        if (at == null) throw new IllegalStateException("at (HookPoint) is required");
        if (listener == null) throw new IllegalStateException("listener is required");
        var request = new HookRequest(owner, target, method, at, cancellable, priority, listener);
        NMSHookLib.engine().registerHook(request);
    }
}
