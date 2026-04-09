package dev.velvet.nmshook.hook;

import dev.velvet.nmshook.api.hook.HookPoint;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Repository that manages registered hooks.
 * Categorizes hooks by target class + method key and sorts them by priority.
 */
public final class HookRegistry {

    /** "className#methodName" to hook list. */
    private final Map<String, CopyOnWriteArrayList<HookDefinition>> hooks = new ConcurrentHashMap<>();

    public void register(HookDefinition definition) {
        String key = toKey(definition.getTargetClass(), definition.getMethodName());
        hooks.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                .add(definition);
    }

    public void unregister(UUID hookId) {
        hooks.values().forEach(list ->
                list.removeIf(d -> d.getId().equals(hookId)));
    }

    /** Returns hooks registered for the specified class + method, sorted by priority. */
    public List<HookDefinition> findHooks(Class<?> targetClass, String methodName) {
        String key = toKey(targetClass, methodName);
        var list = hooks.get(key);
        if (list == null) return List.of();
        return list.stream()
                .filter(HookDefinition::isActive)
                .sorted((a, b) -> a.getPriority().compareTo(b.getPriority()))
                .toList();
    }

    /** Filters hooks by the specified hook point. */
    public List<HookDefinition> findHooks(Class<?> targetClass, String methodName, HookPoint point) {
        return findHooks(targetClass, methodName).stream()
                .filter(d -> d.getHookPoint() == point)
                .toList();
    }

    /** Unregisters all hooks belonging to the specified owner. */
    public void unregisterByOwner(Object owner) {
        if (owner == null) return;
        hooks.values().forEach(list ->
                list.removeIf(d -> owner.equals(d.getOwner())));
    }

    public void clear() {
        hooks.clear();
    }

    private static String toKey(Class<?> clazz, String method) {
        return clazz.getName() + "#" + method;
    }
}
