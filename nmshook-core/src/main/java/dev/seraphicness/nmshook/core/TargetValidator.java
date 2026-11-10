package dev.seraphicness.nmshook.core;

import dev.seraphicness.nmshook.api.target.HookTargetException;
import dev.seraphicness.nmshook.api.target.MethodSelector;
import dev.seraphicness.nmshook.transform.HookDispatcher;
import dev.seraphicness.nmshook.transform.PointInspection;
import dev.seraphicness.nmshook.transform.PointSpec;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Resolves hook targets at registration time and produces actionable error messages,
 * so a wrong name / descriptor / version mismatch fails immediately instead of silently doing nothing.
 */
final class TargetValidator {

    private TargetValidator() {
    }

    /** Resolves the methods a selector targets on {@code owner}, or throws with suggestions. */
    static List<Method> resolve(Class<?> owner, MethodSelector selector, Logger logger, String who) {
        if (selector.name().equals("<init>") || selector.name().equals("<clinit>")) {
            throw new HookTargetException(who + ": constructors and static initializers cannot be hooked ("
                    + owner.getName() + "#" + selector.name() + ")");
        }
        List<Method> found = selector.findDeclared(owner);
        if (found.isEmpty()) {
            throw new HookTargetException(who + ": " + notFoundMessage(owner, selector));
        }
        for (Method m : found) {
            int mod = m.getModifiers();
            if (Modifier.isAbstract(mod)) {
                throw new HookTargetException(who + ": " + owner.getName() + "#" + MethodSelector.pretty(m)
                        + " is abstract (it has no body); hook the class that implements it");
            }
            if (Modifier.isNative(mod)) {
                throw new HookTargetException(who + ": " + owner.getName() + "#" + MethodSelector.pretty(m)
                        + " is native and cannot be instrumented");
            }
        }
        if (!selector.hasDescriptor() && found.size() > 1 && logger != null) {
            logger.warning(who + ": " + owner.getSimpleName() + "#" + selector.name() + " matches " + found.size()
                    + " overloads; the hook applies to all of them: "
                    + String.join(" | ", found.stream().map(MethodSelector::pretty).toList())
                    + ". Add a descriptor (e.g. method(\"" + selector.name() + MethodSelector.descriptorOf(found.getFirst())
                    + "\") or .params(...)) to pick one.");
        }
        return found;
    }

    private static String notFoundMessage(Class<?> owner, MethodSelector selector) {
        String target = owner.getName() + "#" + selector;
        List<Method> named = Arrays.stream(owner.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isBridge() && m.getName().equals(selector.name()))
                .sorted(Comparator.comparing(MethodSelector::descriptorOf))
                .toList();
        if (!named.isEmpty()) {
            return "no overload of " + owner.getName() + "#" + selector.name() + " matches descriptor "
                    + selector.descriptor() + ". Available: "
                    + String.join(", ", named.stream().map(m -> m.getName() + MethodSelector.descriptorOf(m)).toList());
        }
        for (Class<?> c = owner.getSuperclass(); c != null; c = c.getSuperclass()) {
            if (!selector.findDeclared(c).isEmpty()) {
                return target + " is not declared in " + owner.getSimpleName() + " but inherited from "
                        + c.getName() + ". Hook " + c.getSimpleName() + " instead (only declared methods can be "
                        + "instrumented), and check ctx.getInstance() if you only care about " + owner.getSimpleName() + ".";
            }
        }
        for (Class<?> i : allInterfaces(owner)) {
            if (!selector.findDeclared(i).isEmpty()) {
                return target + " is a default method of " + i.getName() + ". Hook " + i.getSimpleName() + " instead.";
            }
        }
        List<String> suggestions = suggest(owner, selector.name());
        return target + " does not exist."
                + (suggestions.isEmpty() ? "" : " Did you mean: " + String.join(", ", suggestions) + "?")
                + " (Paper 26.x runs with Mojang names; check the name for this Minecraft version.)";
    }

    private static Set<Class<?>> allInterfaces(Class<?> c) {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) collect(i, result);
        }
        return result;
    }

    private static void collect(Class<?> i, Set<Class<?>> out) {
        if (out.add(i)) for (Class<?> s : i.getInterfaces()) collect(s, out);
    }

    private static List<String> suggest(Class<?> owner, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) if (!m.isSynthetic()) names.add(m.getName());
        }
        return names.stream()
                .map(n -> new Object() {
                    final String value = n;
                    final int score = distance(lower, n.toLowerCase(Locale.ROOT));
                })
                .filter(x -> x.score <= Math.max(2, lower.length() / 3)
                        || x.value.toLowerCase(Locale.ROOT).contains(lower)
                        || lower.contains(x.value.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt(x -> x.score))
                .limit(5)
                .map(x -> x.value)
                .toList();
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Checks that a point exists in the original bytecode of the target method(s). */
    static void checkPoint(Class<?> owner, MethodSelector selector, PointSpec spec, PointInspection inspection,
                           String who) {
        if (inspection == null) return; // class file not available as a resource: rely on transform errors
        if (inspection.matches() > 0) return;
        String where = owner.getSimpleName() + "#" + selector;
        if (inspection.candidates() > 0) {
            throw new HookTargetException(who + ": ordinal " + spec.ordinal() + " is out of range; " + where
                    + " has " + inspection.candidates() + " matching instruction(s) for " + spec.describe()
                    + " (ordinal is 0-based)");
        }
        String message = switch (spec.kind()) {
            case HookDispatcher.KIND_INJECT, HookDispatcher.KIND_REDIRECT -> {
                List<String> calls = new ArrayList<>(new LinkedHashSet<>(inspection.seenCalls()));
                String wanted = spec.invokeName().toLowerCase(Locale.ROOT);
                calls.sort(Comparator.comparingInt(c -> c.toLowerCase(Locale.ROOT).contains(wanted) ? 0 : 1));
                yield "no call to " + spec.invokeOwner() + "." + spec.invokeName()
                        + (spec.invokeDesc() != null ? spec.invokeDesc() : "") + " in " + where + "."
                        + (calls.isEmpty() ? " The method makes no calls."
                        : " Calls found: " + String.join(", ", calls.stream().limit(15).toList())
                        + (calls.size() > 15 ? ", ..." : ""))
                        + " Note: the owner must be the class named in the bytecode (the static type at the call site).";
            }
            case HookDispatcher.KIND_MODIFY_VARIABLE -> "no " + (spec.localLoad() ? "load of" : "store to")
                    + " local variable slot " + spec.localIndex() + " in " + where;
            case HookDispatcher.KIND_MODIFY_RETURN -> where + " has no value return instruction";
            default -> "point not found in " + where;
        };
        throw new HookTargetException(who + ": " + message);
    }
}
