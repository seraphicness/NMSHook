package dev.seraphicness.nmshook.mixin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Detects Mixins that compete for the same thing on one target class and logs a warning:
 * <ul>
 *   <li>more than one {@code @Overwrite} of the same method — only the first (by priority) runs</li>
 *   <li>more than one {@code @Redirect} of the same call in the same method — only one takes effect</li>
 * </ul>
 */
public final class ConflictDetector {

    private static final Logger LOGGER = Logger.getLogger(ConflictDetector.class.getName());

    public record Conflict(
            Class<?> targetClass,
            String methodName,
            List<MixinDefinition> conflictingMixins,
            String reason
    ) {}

    /** @param definitions all Mixins registered on one target class */
    public List<Conflict> detect(List<MixinDefinition> definitions) {
        Map<String, List<MixinDefinition>> overwrites = new LinkedHashMap<>();
        Map<String, List<MixinDefinition>> redirects = new LinkedHashMap<>();
        for (MixinDefinition d : definitions) {
            for (MixinMethod m : d.getMethods()) {
                switch (m.getType()) {
                    case OVERWRITE -> addOnce(overwrites, m.getTarget().key(), d);
                    case REDIRECT -> addOnce(redirects,
                            m.getTarget().key() + " -> " + m.getInvokeTarget() + " #" + m.getOrdinal(), d);
                    default -> { }
                }
            }
        }
        List<Conflict> conflicts = new ArrayList<>();
        collect(conflicts, overwrites, "Multiple @Overwrite on the same method; only the first by priority runs");
        collect(conflicts, redirects, "Multiple @Redirect of the same call; only one takes effect");
        conflicts.forEach(c -> LOGGER.warning("[NMSHook Conflict] %s#%s — %s (%s)".formatted(
                c.targetClass().getSimpleName(), c.methodName(), c.reason(),
                String.join(", ", c.conflictingMixins().stream().map(x -> x.getMixinClass().getName()).toList()))));
        return conflicts;
    }

    private static void addOnce(Map<String, List<MixinDefinition>> map, String key, MixinDefinition d) {
        List<MixinDefinition> list = map.computeIfAbsent(key, k -> new ArrayList<>());
        if (!list.contains(d)) list.add(d);
    }

    private static void collect(List<Conflict> out, Map<String, List<MixinDefinition>> map, String reason) {
        map.forEach((key, list) -> {
            if (list.size() > 1) out.add(new Conflict(list.getFirst().getTargetClass(), key, List.copyOf(list), reason));
        });
    }
}
