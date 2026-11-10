package dev.seraphicness.nmshook.api.target;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Identifies one method (or a group of overloads) of a target class.
 *
 * <p>Accepted forms:
 * <ul>
 *   <li>{@code "hurtServer"} — every overload with that name</li>
 *   <li>{@code "hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)"}
 *       — overloads with exactly these parameters (any return type)</li>
 *   <li>{@code "hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"}
 *       — the exact JVM descriptor</li>
 * </ul>
 *
 * @param name       method name
 * @param descriptor {@code null} (any overload), a parameter-only descriptor {@code "(...)"},
 *                   or a full JVM descriptor {@code "(...)R"}
 */
public record MethodSelector(String name, String descriptor) {

    public MethodSelector {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) throw new IllegalArgumentException("Method name is empty");
        if (name.indexOf('(') >= 0) throw new IllegalArgumentException("Use MethodSelector.parse for: " + name);
        if (descriptor != null) {
            descriptor = descriptor.replace('.', '/');
            if (!descriptor.startsWith("(") || descriptor.indexOf(')') < 0) {
                throw new IllegalArgumentException("Invalid method descriptor: " + descriptor);
            }
        }
    }

    /** Parses {@code "name"}, {@code "name(params)"} or {@code "name(params)ret"}. */
    public static MethodSelector parse(String spec) {
        Objects.requireNonNull(spec, "spec");
        String s = spec.trim();
        int paren = s.indexOf('(');
        if (paren < 0) return new MethodSelector(s, null);
        return new MethodSelector(s.substring(0, paren), s.substring(paren));
    }

    /** Selects the overload taking exactly the given parameter types (any return type). */
    public static MethodSelector of(String name, Class<?>... parameterTypes) {
        return new MethodSelector(name, parameterDescriptor(parameterTypes));
    }

    public static String parameterDescriptor(Class<?>... parameterTypes) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : parameterTypes) sb.append(p.descriptorString());
        return sb.append(')').toString();
    }

    public static String descriptorOf(Method method) {
        return parameterDescriptor(method.getParameterTypes()) + method.getReturnType().descriptorString();
    }

    public boolean hasDescriptor() {
        return descriptor != null;
    }

    /** True if the descriptor also pins the return type. */
    public boolean isFullDescriptor() {
        return descriptor != null && !descriptor.endsWith(")");
    }

    /** Matches a method given its name and full JVM descriptor. */
    public boolean matches(String methodName, String fullDescriptor) {
        if (!name.equals(methodName)) return false;
        if (descriptor == null) return true;
        return isFullDescriptor() ? descriptor.equals(fullDescriptor) : fullDescriptor.startsWith(descriptor);
    }

    public boolean matches(Method method) {
        return matches(method.getName(), descriptorOf(method));
    }

    /** Methods declared directly on {@code owner} that this selector matches (no bridges/synthetics). */
    public List<Method> findDeclared(Class<?> owner) {
        List<Method> result = new ArrayList<>();
        for (Method m : owner.getDeclaredMethods()) {
            if (m.isBridge() || m.isSynthetic()) continue;
            if (matches(m)) result.add(m);
        }
        result.sort((a, b) -> descriptorOf(a).compareTo(descriptorOf(b)));
        return result;
    }

    /** Stable key for grouping hooks on the same selector. */
    public String key() {
        return descriptor == null ? name : name + descriptor;
    }

    @Override
    public String toString() {
        return key();
    }

    /** Human-readable signature of a method, e.g. {@code hurtServer(ServerLevel, DamageSource, float) -> boolean}. */
    public static String pretty(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        sb.append(String.join(", ", Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).toList()));
        sb.append(") -> ").append(m.getReturnType().getSimpleName());
        if (Modifier.isStatic(m.getModifiers())) sb.append(" [static]");
        return sb.toString();
    }
}
