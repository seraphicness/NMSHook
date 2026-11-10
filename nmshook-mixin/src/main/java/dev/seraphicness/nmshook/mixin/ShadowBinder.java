package dev.seraphicness.nmshook.mixin;

import dev.seraphicness.nmshook.api.annotation.Shadow;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Binds {@code @Shadow} fields of a Mixin class to the actual fields of the target NMS class.
 * At runtime, copies values from the target instance to the Mixin instance so that
 * handler methods can access target fields via {@code this.field}.
 */
public final class ShadowBinder {

    private static final Logger LOGGER = Logger.getLogger(ShadowBinder.class.getName());

    public record ShadowBinding(Field mixinField, Field targetField) {
    }

    /** Finds @Shadow fields in the Mixin class and maps them to same-named fields in the target class. */
    public static List<ShadowBinding> analyze(Class<?> mixinClass, Class<?> targetClass) {
        List<ShadowBinding> bindings = new ArrayList<>();

        for (Field mixinField : mixinClass.getDeclaredFields()) {
            if (!mixinField.isAnnotationPresent(Shadow.class)) continue;

            try {
                Field targetField = findField(targetClass, mixinField.getName());
                mixinField.setAccessible(true);
                targetField.setAccessible(true);
                bindings.add(new ShadowBinding(mixinField, targetField));
                LOGGER.fine(() -> "@Shadow binding: %s.%s -> %s.%s".formatted(
                        mixinClass.getSimpleName(), mixinField.getName(),
                        targetClass.getSimpleName(), targetField.getName()));
            } catch (NoSuchFieldException e) {
                LOGGER.warning("@Shadow target field not found: %s.%s -> %s".formatted(
                        mixinClass.getSimpleName(), mixinField.getName(),
                        targetClass.getSimpleName()));
            }
        }

        return bindings;
    }

    /** Searches for a field in the target class and its superclasses. */
    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " in " + clazz.getName());
    }
}
