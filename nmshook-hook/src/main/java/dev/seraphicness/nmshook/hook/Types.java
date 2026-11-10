package dev.seraphicness.nmshook.hook;

import java.util.Map;

/** Small helpers for primitive/boxed type checks. */
public final class Types {

    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            boolean.class, Boolean.class, byte.class, Byte.class, char.class, Character.class,
            short.class, Short.class, int.class, Integer.class, long.class, Long.class,
            float.class, Float.class, double.class, Double.class, void.class, Void.class);

    private Types() {
    }

    public static Class<?> box(Class<?> type) {
        return type.isPrimitive() ? BOXES.get(type) : type;
    }

    /** True if {@code value} can be assigned to {@code type} (null only for reference types). */
    public static boolean isAssignable(Class<?> type, Object value) {
        if (type == void.class) return true;
        if (value == null) return !type.isPrimitive();
        return box(type).isInstance(value);
    }
}
