package dev.seraphicness.nmshook.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Domain Service that detects conflicts when multiple hooks/Mixins target the same method.
 * Logs warnings and, if necessary, automatically adjusts priorities.
 */
public final class ConflictDetector {

    private static final Logger LOGGER = Logger.getLogger(ConflictDetector.class.getName());

    public record Conflict(
            Class<?> targetClass,
            String methodName,
            List<MixinDefinition> conflictingMixins,
            String reason
    ) {}

    /** Detects conflicts among the registered Mixin definitions. */
    public List<Conflict> detect(List<MixinDefinition> definitions) {
        List<Conflict> conflicts = new ArrayList<>();
        var reported = new java.util.HashSet<String>();

        // Conflict if 2 or more @Overwrite annotations target the same class + method
        var grouped = definitions.stream()
                .collect(java.util.stream.Collectors.groupingBy(d -> d.getTargetClass().getName()));

        for (var entry : grouped.entrySet()) {
            List<MixinDefinition> mixins = entry.getValue();

            var overwrites = mixins.stream()
                    .filter(d -> !d.getMethodsByType(MixinMethod.Type.OVERWRITE).isEmpty())
                    .toList();

            if (overwrites.size() > 1) {
                for (MixinDefinition mixin : overwrites) {
                    for (MixinMethod method : mixin.getMethodsByType(MixinMethod.Type.OVERWRITE)) {
                        String conflictKey = entry.getKey() + "#" + method.getTargetMethodName();
                        if (!reported.add(conflictKey)) continue; // skip already-reported pair

                        var all = overwrites.stream()
                                .filter(o -> o.getMethodsByType(MixinMethod.Type.OVERWRITE).stream()
                                        .anyMatch(m -> m.getTargetMethodName().equals(method.getTargetMethodName())))
                                .toList();

                        if (all.size() > 1) {
                            conflicts.add(new Conflict(
                                    mixin.getTargetClass(),
                                    method.getTargetMethodName(),
                                    all,
                                    "Multiple @Overwrite annotations on the same method"
                            ));
                        }
                    }
                }
            }
        }

        conflicts.forEach(c ->
                LOGGER.warning("[NMSHook Conflict] %s#%s — %s (%d conflicting Mixin(s))"
                        .formatted(c.targetClass().getSimpleName(), c.methodName(),
                                c.reason(), c.conflictingMixins().size()))
        );

        return conflicts;
    }
}
