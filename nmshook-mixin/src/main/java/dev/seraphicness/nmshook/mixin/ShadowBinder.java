package dev.seraphicness.nmshook.mixin;

import dev.seraphicness.nmshook.api.annotation.Shadow;
import dev.seraphicness.nmshook.api.target.HookTargetException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Binds {@code @Shadow} members of a Mixin class to the actual members of the target NMS class.
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
            Field targetField;
            try {
                targetField = findField(targetClass, mixinField.getName());
            } catch (NoSuchFieldException e) {
                throw new HookTargetException("@Shadow field not found: %s.%s (looked in %s and its superclasses)"
                        .formatted(mixinClass.getSimpleName(), mixinField.getName(), targetClass.getName()));
            }
            if (!mixinField.getType().isAssignableFrom(targetField.getType())) {
                throw new HookTargetException("@Shadow field %s.%s has type %s but %s.%s is %s".formatted(
                        mixinClass.getSimpleName(), mixinField.getName(), mixinField.getType().getName(),
                        targetField.getDeclaringClass().getSimpleName(), targetField.getName(),
                        targetField.getType().getName()));
            }
            mixinField.setAccessible(true);
            targetField.setAccessible(true);
            bindings.add(new ShadowBinding(mixinField, targetField));
            LOGGER.fine(() -> "@Shadow binding: %s.%s -> %s.%s".formatted(
                    mixinClass.getSimpleName(), mixinField.getName(),
                    targetClass.getSimpleName(), targetField.getName()));
        }
        return bindings;
    }

    /**
     * Resolves abstract {@code @Shadow} methods of the Mixin class to methods of the target class
     * (same name and parameter types, searched up the hierarchy).
     */
    public static Map<Method, Method> analyzeMethods(Class<?> mixinClass, Class<?> targetClass) {
        Map<Method, Method> result = new HashMap<>();
        for (Method m : mixinClass.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Shadow.class)) continue;
            if (!Modifier.isAbstract(m.getModifiers())) {
                throw new HookTargetException("@Shadow method %s.%s must be abstract".formatted(
                        mixinClass.getSimpleName(), m.getName()));
            }
            Method target = findMethod(targetClass, m.getName(), m.getParameterTypes());
            if (target == null) {
                throw new HookTargetException("@Shadow method not found: %s(%s) in %s".formatted(m.getName(),
                        String.join(", ", Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toList()),
                        targetClass.getName()));
            }
            target.setAccessible(true);
            result.put(m, target);
        }
        return result;
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // continue with superclass
            }
        }
        throw new NoSuchFieldException(name + " in " + clazz.getName());
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>[] params) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // continue with superclass
            }
        }
        return null;
    }
}
