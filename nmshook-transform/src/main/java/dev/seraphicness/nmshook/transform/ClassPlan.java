package dev.seraphicness.nmshook.transform;

import dev.seraphicness.nmshook.api.target.MethodSelector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable description of every instrumentation applied to one class.
 * The class is always re-transformed from its original bytes with the complete plan,
 * so the order of transformations is deterministic regardless of registration order.
 */
public final class ClassPlan {

    /** Method-level advice (BEFORE / AFTER / REPLACE / ModifyArg / HEAD / TAIL / Overwrite). */
    public record AdviceEntry(int id, MethodSelector method) {}

    /** Instruction-level point (INVOKE inject / Redirect / ModifyVariable / ModifyReturnValue). */
    public record PointEntry(int id, MethodSelector method, PointSpec spec) {}

    public static final ClassPlan EMPTY = new ClassPlan(List.of(), List.of());

    private final List<AdviceEntry> advice;
    private final List<PointEntry> points;

    private ClassPlan(List<AdviceEntry> advice, List<PointEntry> points) {
        this.advice = List.copyOf(advice);
        this.points = List.copyOf(points);
    }

    public List<AdviceEntry> advice() {
        return advice;
    }

    public List<PointEntry> points() {
        return points;
    }

    /** Methods that need the cancel-catcher advice (one per distinct selector). */
    public List<MethodSelector> cancelCatchers() {
        Map<String, MethodSelector> byKey = new LinkedHashMap<>();
        for (PointEntry p : points) {
            if (p.spec().cancellable()) byKey.putIfAbsent(p.method().key(), p.method());
        }
        return List.copyOf(byKey.values());
    }

    public boolean isEmpty() {
        return advice.isEmpty() && points.isEmpty();
    }

    public ClassPlan withAdvice(AdviceEntry entry) {
        var list = new ArrayList<>(advice);
        list.add(entry);
        return new ClassPlan(list, points);
    }

    public ClassPlan withPoint(PointEntry entry) {
        var list = new ArrayList<>(points);
        list.add(entry);
        return new ClassPlan(advice, list);
    }

    public ClassPlan without(int id) {
        return new ClassPlan(
                advice.stream().filter(a -> a.id() != id).toList(),
                points.stream().filter(p -> p.id() != id).toList());
    }
}
