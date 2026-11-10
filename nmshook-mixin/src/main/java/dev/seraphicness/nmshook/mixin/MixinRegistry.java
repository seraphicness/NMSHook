package dev.seraphicness.nmshook.mixin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Repository that manages registered Mixin definitions.
 * Categorizes Mixins by target class and sorts them by priority.
 */
public final class MixinRegistry {

    private final Map<UUID, MixinDefinition> byId = new ConcurrentHashMap<>();
    private final Map<String, List<MixinDefinition>> byTarget = new ConcurrentHashMap<>();

    public void register(MixinDefinition definition) {
        byId.put(definition.getId(), definition);
        byTarget.compute(definition.getTargetClass().getName(), (key, existing) -> {
            var merged = new java.util.ArrayList<>(existing != null ? existing : List.<MixinDefinition>of());
            merged.add(definition);
            merged.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));
            return List.copyOf(merged);
        });
    }

    public MixinDefinition findById(UUID id) {
        return byId.get(id);
    }

    /** Returns the list of Mixins registered for a specific NMS class, sorted by priority. */
    public List<MixinDefinition> findByTarget(Class<?> targetClass) {
        return byTarget.getOrDefault(targetClass.getName(), List.of());
    }

    public void unregister(UUID id) {
        MixinDefinition removed = byId.remove(id);
        if (removed != null) {
            byTarget.computeIfPresent(removed.getTargetClass().getName(), (key, list) ->
                    list.stream().filter(d -> !d.getId().equals(id)).toList()
            );
        }
    }

    /** Unregisters all Mixin definitions belonging to the specified owner. */
    public void unregisterByOwner(Object owner) {
        if (owner == null) return;
        byId.values().removeIf(d -> owner.equals(d.getOwner()));
        byTarget.replaceAll((key, list) ->
                list.stream().filter(d -> !owner.equals(d.getOwner())).toList());
    }

    public void clear() {
        byId.clear();
        byTarget.clear();
    }
}
