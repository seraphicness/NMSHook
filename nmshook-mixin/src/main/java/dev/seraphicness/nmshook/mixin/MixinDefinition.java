package dev.seraphicness.nmshook.mixin;

import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root representing a single parsed Mixin class.
 * Owns the target NMS class, priority, and the list of contained {@link MixinMethod}s.
 */
public final class MixinDefinition {

    private final UUID id;
    private final Object owner;
    private final Class<?> mixinClass;
    private final Class<?> targetClass;
    private final int priority;
    private final List<MixinMethod> methods;

    public MixinDefinition(Object owner,
                           Class<?> mixinClass,
                           Class<?> targetClass,
                           int priority,
                           List<MixinMethod> methods) {
        this.id = UUID.randomUUID();
        this.owner = owner;
        this.mixinClass = mixinClass;
        this.targetClass = targetClass;
        this.priority = priority;
        this.methods = List.copyOf(methods);
    }

    public UUID getId() {
        return id;
    }

    public Object getOwner() {
        return owner;
    }

    public Class<?> getMixinClass() {
        return mixinClass;
    }

    public Class<?> getTargetClass() {
        return targetClass;
    }

    public int getPriority() {
        return priority;
    }

    public List<MixinMethod> getMethods() {
        return methods;
    }

    /** Filters handler methods by the specified type. */
    public List<MixinMethod> getMethodsByType(MixinMethod.Type type) {
        return methods.stream()
                .filter(m -> m.getType() == type)
                .toList();
    }
}
