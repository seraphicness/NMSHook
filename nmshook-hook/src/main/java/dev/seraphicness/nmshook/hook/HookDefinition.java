package dev.seraphicness.nmshook.hook;

import dev.seraphicness.nmshook.api.hook.HookListener;
import dev.seraphicness.nmshook.api.target.MethodSelector;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * A single registered hook: what it targets, who owns it, its handler, and its runtime state
 * (enabled flag, call/timing statistics, failure counters for the circuit breaker).
 */
public final class HookDefinition {

    public enum Kind {
        BEFORE, AFTER, REPLACE,
        INVOKE, REDIRECT, MODIFY_VARIABLE, MODIFY_RETURN;

        public boolean isPoint() {
            return ordinal() >= INVOKE.ordinal();
        }
    }

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private final int id;
    private final Object owner;
    private final ClassLoader sourceLoader;
    private final Class<?> targetClass;
    private final MethodSelector selector;
    private final Kind kind;
    private final boolean cancellable;
    private final HookPriority priority;
    private final HookListener listener;
    private final PointHandler pointHandler;
    private final String source;
    private final String detail;
    private volatile int groupId;

    private volatile boolean enabled = true;
    private volatile String disabledReason;
    /*
     * Plain fields on purpose: this is updated on every hooked call, and an atomic increment would cost
     * more than the dispatch itself. Under heavy multi-threading (Folia) a few increments may be lost;
     * the numbers are diagnostics, not accounting.
     */
    private long calls;
    private final LongAdder nanos = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    private HookDefinition(Object owner, ClassLoader sourceLoader, Class<?> targetClass, MethodSelector selector,
                           Kind kind, boolean cancellable, HookPriority priority, HookListener listener,
                           PointHandler pointHandler, String source, String detail) {
        this.id = NEXT_ID.getAndIncrement();
        this.groupId = id;
        this.owner = owner;
        this.sourceLoader = sourceLoader;
        this.targetClass = targetClass;
        this.selector = selector;
        this.kind = kind;
        this.cancellable = cancellable;
        this.priority = priority;
        this.listener = listener;
        this.pointHandler = pointHandler;
        this.source = source;
        this.detail = detail;
    }

    /** BEFORE / AFTER / REPLACE hook backed by a {@link HookListener}. */
    public static HookDefinition method(Object owner, ClassLoader sourceLoader, Class<?> targetClass,
                                        MethodSelector selector, Kind kind, boolean cancellable,
                                        HookPriority priority, HookListener listener, String source) {
        if (kind.isPoint()) throw new IllegalArgumentException("Not a method hook kind: " + kind);
        return new HookDefinition(owner, sourceLoader, targetClass, selector, kind, cancellable, priority,
                listener, null, source, null);
    }

    /** Instruction-level hook backed by a {@link PointHandler}. */
    public static HookDefinition point(Object owner, ClassLoader sourceLoader, Class<?> targetClass,
                                       MethodSelector selector, Kind kind, boolean cancellable,
                                       HookPriority priority, PointHandler handler, String source, String detail) {
        if (!kind.isPoint()) throw new IllegalArgumentException("Not a point kind: " + kind);
        return new HookDefinition(owner, sourceLoader, targetClass, selector, kind, cancellable, priority,
                null, handler, source, detail);
    }

    public int getId() { return id; }
    public Object getOwner() { return owner; }
    /** ClassLoader of the code that registered the hook (listener / Mixin class). */
    public ClassLoader getSourceLoader() { return sourceLoader; }
    public Class<?> getTargetClass() { return targetClass; }
    public MethodSelector getSelector() { return selector; }
    public String getMethodName() { return selector.name(); }
    public Kind getKind() { return kind; }
    public boolean isCancellable() { return cancellable; }
    public HookPriority getPriority() { return priority; }
    public HookListener getListener() { return listener; }
    public PointHandler getPointHandler() { return pointHandler; }
    /** "lambda" or "MixinClass#handler". */
    public String getSource() { return source; }
    /** Point description (e.g. "INVOKE a/B.c()V (before)"), null for method hooks. */
    public String getDetail() { return detail; }
    /** Id of the registration this hook belongs to (a Mixin registers several hooks under one id). */
    public int getGroupId() { return groupId; }
    public void setGroupId(int groupId) { this.groupId = groupId; }

    // ---------------------------------------------------------------- state

    public boolean isEnabled() { return enabled; }
    public String getDisabledReason() { return disabledReason; }

    public void disable(String reason) {
        this.disabledReason = reason;
        this.enabled = false;
    }

    public void enable() {
        this.disabledReason = null;
        this.consecutiveFailures.set(0);
        this.enabled = true;
    }

    public long getCalls() { return calls; }
    public long getTotalNanos() { return nanos.sum(); }
    public long getFailures() { return failures.sum(); }
    public int getConsecutiveFailures() { return consecutiveFailures.get(); }

    public void resetStats() {
        calls = 0;
        nanos.reset();
        failures.reset();
    }

    void recordCall(long elapsedNanos) {
        calls++;
        if (elapsedNanos > 0) nanos.add(elapsedNanos);
    }

    void recordSuccess() {
        if (consecutiveFailures.get() != 0) consecutiveFailures.set(0);
    }

    /** @return the number of consecutive failures including this one */
    int recordFailure() {
        failures.increment();
        return consecutiveFailures.incrementAndGet();
    }

    @Override
    public String toString() {
        return "#" + id + " " + kind + " " + targetClass.getSimpleName() + "#" + selector
                + (detail != null ? " @ " + detail : "") + " (" + source + ")";
    }
}
