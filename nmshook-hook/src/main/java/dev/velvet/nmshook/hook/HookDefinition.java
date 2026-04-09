package dev.velvet.nmshook.hook;

import dev.velvet.nmshook.api.hook.HookListener;
import dev.velvet.nmshook.api.hook.HookPoint;

import java.util.UUID;

/**
 * Entity representing a single registered lambda hook.
 * Identified by a unique ID, and holds a priority and active state.
 */
public final class HookDefinition {

    private final UUID id;
    private final Object owner;
    private final Class<?> targetClass;
    private final String methodName;
    private final HookPoint hookPoint;
    private final boolean cancellable;
    private final HookPriority priority;
    private final HookListener listener;
    private boolean active;

    public HookDefinition(Object owner,
                          Class<?> targetClass,
                          String methodName,
                          HookPoint hookPoint,
                          boolean cancellable,
                          HookPriority priority,
                          HookListener listener) {
        this.id = UUID.randomUUID();
        this.owner = owner;
        this.targetClass = targetClass;
        this.methodName = methodName;
        this.hookPoint = hookPoint;
        this.cancellable = cancellable;
        this.priority = priority;
        this.listener = listener;
        this.active = true;
    }

    public UUID getId() {
        return id;
    }

    public Object getOwner() {
        return owner;
    }

    public Class<?> getTargetClass() {
        return targetClass;
    }

    public String getMethodName() {
        return methodName;
    }

    public HookPoint getHookPoint() {
        return hookPoint;
    }

    public boolean isCancellable() {
        return cancellable;
    }

    public HookPriority getPriority() {
        return priority;
    }

    public HookListener getListener() {
        return listener;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }
}
