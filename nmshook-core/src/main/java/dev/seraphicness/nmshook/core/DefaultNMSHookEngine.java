package dev.seraphicness.nmshook.core;

import dev.seraphicness.nmshook.api.HookHandle;
import dev.seraphicness.nmshook.api.NMSHookEngine;
import dev.seraphicness.nmshook.api.hook.HookRequest;
import dev.seraphicness.nmshook.api.target.HookTargetException;
import dev.seraphicness.nmshook.api.target.MethodSelector;
import dev.seraphicness.nmshook.hook.DefaultHookContext;
import dev.seraphicness.nmshook.hook.HookDefinition;
import dev.seraphicness.nmshook.hook.HookExecutor;
import dev.seraphicness.nmshook.hook.HookPriority;
import dev.seraphicness.nmshook.hook.Types;
import dev.seraphicness.nmshook.mixin.AnnotationParser;
import dev.seraphicness.nmshook.mixin.ConflictDetector;
import dev.seraphicness.nmshook.mixin.MixinDefinition;
import dev.seraphicness.nmshook.mixin.MixinHandlerAdapter;
import dev.seraphicness.nmshook.mixin.MixinMethod;
import dev.seraphicness.nmshook.transform.ClassPlan;
import dev.seraphicness.nmshook.transform.ClassRewriter;
import dev.seraphicness.nmshook.transform.DispatcherBridge;
import dev.seraphicness.nmshook.transform.HookDispatcher;
import dev.seraphicness.nmshook.transform.PointSpec;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Default implementation of {@link NMSHookEngine}.
 *
 * <h2>Sites</h2>
 * A <i>method site</i> is one (class, method selector) pair carrying method-level advice; all BEFORE /
 * AFTER / REPLACE hooks on it share the site. A <i>point site</i> is one instruction-level rewrite
 * (INVOKE inject, Redirect, ModifyVariable, ModifyReturnValue) owned by a single hook. Each site has a
 * numeric id that is baked into the bytecode; the dispatcher calls back into {@link #onEnter},
 * {@link #onExit} and {@link #onPoint} with it.
 *
 * <h2>Hot path</h2>
 * Hook arrays are pre-sorted snapshots rebuilt on (un)registration, and per-site flags let the inlined
 * code skip the dispatcher entirely when a site has no active hooks of a phase.
 *
 * <h2>Lifecycle</h2>
 * When the last hook of a site is removed, the site's instrumentation is removed and the class is
 * re-transformed back to its original bytecode. {@link #unregisterByClassLoader} removes everything a
 * plugin registered, even hooks registered without an owner.
 */
public final class DefaultNMSHookEngine implements NMSHookEngine {

    private final Logger logger;
    private final ClassRewriter rewriter;
    private final DispatcherBridge bridge;
    private final HookExecutor executor;
    private final AnnotationParser annotationParser = new AnnotationParser();
    private final ConflictDetector conflictDetector = new ConflictDetector();
    private final Object pass;

    private final Object lock = new Object();
    private int nextSiteId = 1;
    private volatile Site[] sites = new Site[64];
    private final Map<String, MethodSite> methodSites = new HashMap<>();
    /** hook id -> site */
    private final Map<Integer, Site> siteOfHook = new HashMap<>();
    /** registration (group) id -> hooks */
    private final Map<Integer, List<HookDefinition>> registrations = new LinkedHashMap<>();
    /** registration id -> Mixin definition (for conflict detection) */
    private final Map<Integer, MixinDefinition> mixins = new LinkedHashMap<>();
    private volatile boolean closed;

    public DefaultNMSHookEngine(Instrumentation instrumentation, Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.rewriter = new ClassRewriter(instrumentation);
        this.bridge = rewriter.dispatcher();
        this.pass = bridge.pass();
        this.executor = new HookExecutor(logger);
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MethodHandle enter = lookup.findVirtual(DefaultNMSHookEngine.class, "onEnter",
                    MethodType.methodType(Object.class, int.class, Object.class, Object[].class)).bindTo(this);
            MethodHandle exit = lookup.findVirtual(DefaultNMSHookEngine.class, "onExit",
                    MethodType.methodType(Object.class, int.class, Object.class, Object[].class, Object.class))
                    .bindTo(this);
            MethodHandle point = lookup.findVirtual(DefaultNMSHookEngine.class, "onPoint",
                    MethodType.methodType(Object.class, int.class, Object[].class)).bindTo(this);
            bridge.setHandlers(enter, exit, point);
        } catch (ReflectiveOperationException e) {
            rewriter.close();
            throw new IllegalStateException("Failed to wire HookDispatcher", e);
        }
    }

    public HookExecutor executor() {
        return executor;
    }

    // ==================================================================== registration: lambda hooks

    @Override
    public HookHandle registerHook(HookRequest request) {
        Objects.requireNonNull(request.target(), "target");
        Objects.requireNonNull(request.method(), "method");
        Objects.requireNonNull(request.listener(), "listener");
        HookDefinition.Kind kind = switch (request.at()) {
            case BEFORE -> HookDefinition.Kind.BEFORE;
            case AFTER -> HookDefinition.Kind.AFTER;
            case REPLACE -> HookDefinition.Kind.REPLACE;
        };
        String who = "Hook " + request.target().getSimpleName() + "#" + request.method();
        synchronized (lock) {
            ensureOpen();
            List<Method> methods = TargetValidator.resolve(request.target(), request.method(), logger, who);
            var def = HookDefinition.method(request.owner(), request.listener().getClass().getClassLoader(),
                    request.target(), request.method(), kind, request.cancellable() || kind == HookDefinition.Kind.REPLACE,
                    new HookPriority(request.priority()), request.listener(), "lambda");
            var batch = new Batch();
            MethodSite site = batch.methodSite(request.target(), request.method(), methods);
            batch.commit();
            attach(site, def);
            registrations.put(def.getGroupId(), List.of(def));
            logger.info("[NMSHook] Hook #%d registered: %s#%s @%s (priority=%d)".formatted(def.getId(),
                    request.target().getSimpleName(), request.method(), request.at(), request.priority()));
            return new Handle(def.getGroupId());
        }
    }

    // ==================================================================== registration: Mixins

    @Override
    public HookHandle registerMixin(Class<?> mixinClass, Object owner) {
        MixinDefinition definition = annotationParser.parse(mixinClass, owner);
        MixinHandlerAdapter adapter = new MixinHandlerAdapter(definition);
        Class<?> target = definition.getTargetClass();
        ClassLoader source = mixinClass.getClassLoader();

        synchronized (lock) {
            ensureOpen();
            var batch = new Batch();
            List<Runnable> attachments = new ArrayList<>();
            List<HookDefinition> created = new ArrayList<>();

            for (MixinMethod m : definition.getMethods()) {
                String who = m.describe();
                List<Method> methods = TargetValidator.resolve(target, m.getTarget(), logger, who);
                if (!m.getType().isPoint()) {
                    checkMethodHandler(m, methods, who);
                    HookDefinition.Kind kind = switch (m.getType()) {
                        case INJECT_HEAD, MODIFY_ARG -> HookDefinition.Kind.BEFORE;
                        case INJECT_TAIL -> HookDefinition.Kind.AFTER;
                        case OVERWRITE -> HookDefinition.Kind.REPLACE;
                        default -> throw new IllegalStateException();
                    };
                    int priority = definition.getPriority();
                    if (m.getType() == MixinMethod.Type.MODIFY_ARG) priority = Math.max(0, priority - 100);
                    var def = HookDefinition.method(owner, source, target, m.getTarget(), kind,
                            m.isCancellable() || kind == HookDefinition.Kind.REPLACE, new HookPriority(priority),
                            adapter.toListener(m), who);
                    MethodSite site = batch.methodSite(target, m.getTarget(), methods);
                    created.add(def);
                    attachments.add(() -> attach(site, def));
                } else {
                    boolean outerStatic = staticness(methods, who);
                    PointSpec spec = toPointSpec(m);
                    TargetValidator.checkPoint(target, m.getTarget(), spec,
                            ClassRewriter.inspect(target, m.getTarget(), spec), who);
                    checkPointHandler(m, methods, spec, who);
                    HookDefinition.Kind kind = switch (m.getType()) {
                        case INJECT_INVOKE -> HookDefinition.Kind.INVOKE;
                        case REDIRECT -> HookDefinition.Kind.REDIRECT;
                        case MODIFY_VARIABLE -> HookDefinition.Kind.MODIFY_VARIABLE;
                        default -> HookDefinition.Kind.MODIFY_RETURN;
                    };
                    var def = HookDefinition.point(owner, source, target, m.getTarget(), kind, m.isCancellable(),
                            new HookPriority(definition.getPriority()), adapter.toPointHandler(m, outerStatic),
                            who, spec.describe());
                    PointSite site = batch.pointSite(target, m.getTarget(), methods, spec, def,
                            expectedPointResult(m, methods, spec, target), outerStatic);
                    created.add(def);
                    attachments.add(() -> attachPoint(site));
                }
            }

            batch.commit();
            attachments.forEach(Runnable::run);
            int groupId = created.getFirst().getId();
            created.forEach(d -> d.setGroupId(groupId));
            registrations.put(groupId, List.copyOf(created));
            mixins.put(groupId, definition);
            conflictDetector.detect(mixins.values().stream().filter(d -> d.getTargetClass() == target).toList());
            logger.info("[NMSHook] Mixin #%d registered: %s -> %s (%d handler(s))".formatted(groupId,
                    mixinClass.getSimpleName(), target.getSimpleName(), created.size()));
            return new Handle(groupId);
        }
    }

    private static void checkMethodHandler(MixinMethod m, List<Method> methods, String who) {
        Method handler = m.getHandlerMethod();
        switch (m.getType()) {
            case INJECT_HEAD, INJECT_TAIL -> checkInjectParams(handler, methods, who);
            case MODIFY_ARG -> {
                for (Method target : methods) {
                    if (m.getArgIndex() >= target.getParameterCount()) {
                        throw new HookTargetException(who + ": @ModifyArg index " + m.getArgIndex() + " but "
                                + MethodSelector.pretty(target) + " has " + target.getParameterCount() + " parameter(s)");
                    }
                    Class<?> type = target.getParameterTypes()[m.getArgIndex()];
                    if (Types.box(type) != Types.box(handler.getReturnType())) {
                        throw new HookTargetException(who + ": @ModifyArg handler type " + handler.getReturnType().getName()
                                + " does not match parameter " + m.getArgIndex() + " (" + type.getName() + ") of "
                                + MethodSelector.pretty(target));
                    }
                }
            }
            default -> { }
        }
    }

    private static void checkInjectParams(Method handler, List<Method> methods, String who) {
        Class<?>[] declared = Arrays.copyOf(handler.getParameterTypes(), handler.getParameterCount() - 1);
        if (declared.length == 0) return;
        for (Method target : methods) {
            if (!Arrays.equals(declared, target.getParameterTypes())) {
                throw new HookTargetException(who + ": handler parameters (" + String.join(", ",
                        Arrays.stream(declared).map(Class::getSimpleName).toList()) + ") must match "
                        + MethodSelector.pretty(target) + " followed by CallbackInfo (or declare only CallbackInfo)");
            }
        }
    }

    private static boolean staticness(List<Method> methods, String who) {
        boolean first = Modifier.isStatic(methods.getFirst().getModifiers());
        for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers()) != first) {
                throw new HookTargetException(who + ": the selector matches both static and instance overloads; "
                        + "add a descriptor to pick one");
            }
        }
        return first;
    }

    private static PointSpec toPointSpec(MixinMethod m) {
        return switch (m.getType()) {
            case INJECT_INVOKE, REDIRECT -> {
                String[] t;
                try {
                    t = PointSpec.parseInvokeTarget(m.getInvokeTarget());
                } catch (IllegalArgumentException e) {
                    throw new HookTargetException(m.describe() + ": " + e.getMessage());
                }
                yield m.getType() == MixinMethod.Type.REDIRECT
                        ? PointSpec.redirect(t[0], t[1], t[2], m.getOrdinal())
                        : PointSpec.inject(t[0], t[1], t[2], m.isShiftAfter(), m.getOrdinal(), m.isCancellable());
            }
            case MODIFY_VARIABLE -> PointSpec.modifyVariable(m.getLocalIndex(), m.isLocalLoad(),
                    m.getHandlerMethod().getParameterTypes()[0].descriptorString(), m.getOrdinal());
            case MODIFY_RETURN_VALUE -> PointSpec.modifyReturn(m.getOrdinal());
            default -> throw new IllegalArgumentException(m.getType().toString());
        };
    }

    private static void checkPointHandler(MixinMethod m, List<Method> methods, PointSpec spec, String who) {
        Method handler = m.getHandlerMethod();
        switch (m.getType()) {
            case INJECT_INVOKE -> checkInjectParams(handler, methods, who);
            case MODIFY_RETURN_VALUE -> {
                for (Method target : methods) {
                    if (target.getReturnType() != handler.getReturnType()) {
                        throw new HookTargetException(who + ": @ModifyReturnValue handler type "
                                + handler.getReturnType().getName() + " does not match the return type of "
                                + MethodSelector.pretty(target));
                    }
                }
            }
            case REDIRECT -> {
                if (spec.invokeDesc() == null) return;
                var call = net.bytebuddy.jar.asm.Type.getMethodType(spec.invokeDesc());
                int args = call.getArgumentTypes().length;
                int count = handler.getParameterCount();
                if (count != args && count != args + 1) {
                    throw new HookTargetException(who + ": @Redirect handler for " + spec.invokeName()
                            + spec.invokeDesc() + " must take (receiver, " + args + " argument(s)) for an instance "
                            + "call, or (" + args + " argument(s)) for a static call; it takes " + count);
                }
                var ret = call.getReturnType();
                boolean refReturn = ret.getSort() == net.bytebuddy.jar.asm.Type.OBJECT
                        || ret.getSort() == net.bytebuddy.jar.asm.Type.ARRAY;
                boolean ok = refReturn ? !handler.getReturnType().isPrimitive()
                        : handler.getReturnType().descriptorString().equals(ret.getDescriptor());
                if (!ok) {
                    throw new HookTargetException(who + ": @Redirect handler returns " + handler.getReturnType().getName()
                            + " but " + spec.invokeName() + " returns " + ret.getClassName());
                }
            }
            default -> { }
        }
    }

    /** Type the point handler's result must have (checked at runtime to protect the NMS code). */
    private static Class<?> expectedPointResult(MixinMethod m, List<Method> methods, PointSpec spec, Class<?> owner) {
        return switch (m.getType()) {
            case INJECT_INVOKE -> methods.getFirst().getReturnType(); // value used when cancelled
            case REDIRECT -> {
                if (spec.invokeDesc() != null) {
                    Class<?> resolved = classOf(net.bytebuddy.jar.asm.Type.getMethodType(spec.invokeDesc())
                            .getReturnType(), owner.getClassLoader());
                    yield resolved != null ? resolved : m.getHandlerMethod().getReturnType();
                }
                yield m.getHandlerMethod().getReturnType();
            }
            default -> m.getHandlerMethod().getParameterTypes()[0];
        };
    }

    private static Class<?> classOf(net.bytebuddy.jar.asm.Type type, ClassLoader loader) {
        return switch (type.getSort()) {
            case net.bytebuddy.jar.asm.Type.VOID -> void.class;
            case net.bytebuddy.jar.asm.Type.BOOLEAN -> boolean.class;
            case net.bytebuddy.jar.asm.Type.BYTE -> byte.class;
            case net.bytebuddy.jar.asm.Type.CHAR -> char.class;
            case net.bytebuddy.jar.asm.Type.SHORT -> short.class;
            case net.bytebuddy.jar.asm.Type.INT -> int.class;
            case net.bytebuddy.jar.asm.Type.LONG -> long.class;
            case net.bytebuddy.jar.asm.Type.FLOAT -> float.class;
            case net.bytebuddy.jar.asm.Type.DOUBLE -> double.class;
            default -> {
                try {
                    String name = type.getSort() == net.bytebuddy.jar.asm.Type.ARRAY
                            ? type.getDescriptor().replace('/', '.') : type.getClassName();
                    yield Class.forName(name, false, loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    yield null;
                }
            }
        };
    }

    // ==================================================================== unregistration

    @Override
    public void unregisterAll(Object owner) {
        if (owner == null) {
            unregisterWhere(def -> true, "all");
        } else {
            unregisterWhere(def -> owner.equals(def.getOwner()), String.valueOf(owner));
        }
    }

    /**
     * Removes every hook registered from code loaded by {@code loader} (listener / Mixin class) or owned
     * by an object whose class was loaded by it. Called when a plugin is disabled.
     *
     * @return number of hooks removed
     */
    public int unregisterByClassLoader(ClassLoader loader) {
        if (loader == null) return 0;
        return unregisterWhere(def -> def.getSourceLoader() == loader
                || (def.getOwner() != null && def.getOwner().getClass().getClassLoader() == loader), null);
    }

    private int unregisterWhere(Predicate<HookDefinition> filter, String label) {
        synchronized (lock) {
            if (closed) return 0;
            List<Integer> groups = registrations.entrySet().stream()
                    .filter(e -> e.getValue().stream().anyMatch(filter))
                    .map(Map.Entry::getKey)
                    .toList();
            int removed = removeGroups(groups);
            if (removed > 0 && label != null) logger.info("[NMSHook] " + removed + " hook(s) unregistered for " + label);
            return removed;
        }
    }

    private void unregisterGroup(int groupId) {
        synchronized (lock) {
            if (!closed) removeGroups(List.of(groupId));
        }
    }

    /** Must hold {@link #lock}. */
    private int removeGroups(Collection<Integer> groupIds) {
        Map<Class<?>, ClassPlan> changes = new HashMap<>();
        List<Site> dropped = new ArrayList<>();
        int count = 0;
        for (int groupId : groupIds) {
            List<HookDefinition> defs = registrations.remove(groupId);
            mixins.remove(groupId);
            if (defs == null) continue;
            for (HookDefinition def : defs) {
                count++;
                Site site = siteOfHook.remove(def.getId());
                if (site instanceof MethodSite ms) {
                    ms.hooks.remove(def);
                    ms.rebuild();
                    bridge.setFlags(ms.id, ms.flags());
                    if (ms.hooks.isEmpty()) {
                        methodSites.remove(ms.key);
                        dropped.add(ms);
                    }
                } else if (site instanceof PointSite ps) {
                    dropped.add(ps);
                }
            }
        }
        for (Site site : dropped) {
            ClassPlan plan = changes.getOrDefault(site.owner, rewriter.planFor(site.owner));
            changes.put(site.owner, plan.without(site.id));
        }
        if (!changes.isEmpty()) {
            try {
                rewriter.commit(changes);
            } catch (RuntimeException e) {
                logger.log(java.util.logging.Level.WARNING, "[NMSHook] Failed to restore original bytecode", e);
            }
        }
        // only now stop resolving the ids (in-flight calls in old code fall back to default behaviour)
        Site[] table = sites.clone();
        for (Site site : dropped) table[site.id] = null;
        sites = table;
        return count;
    }

    /** Removes all hooks, restores every class and disconnects the dispatcher. */
    public void shutdown() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            registrations.clear();
            mixins.clear();
            methodSites.clear();
            siteOfHook.clear();
            rewriter.close();
            sites = new Site[0];
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("NMSHook engine is shut down");
    }

    // ==================================================================== management (/nmshook)

    public List<HookDefinition> hooks() {
        synchronized (lock) {
            return registrations.values().stream().flatMap(List::stream)
                    .sorted(Comparator.comparingInt(HookDefinition::getId)).toList();
        }
    }

    public Optional<HookDefinition> hook(int id) {
        return hooks().stream().filter(h -> h.getId() == id).findFirst();
    }

    public boolean setEnabled(int id, boolean enabled) {
        synchronized (lock) {
            Site site = siteOfHook.get(id);
            if (site == null) return false;
            HookDefinition def = hookIn(site, id);
            if (enabled) def.enable();
            else def.disable("disabled by command");
            if (site instanceof MethodSite ms) {
                ms.rebuild();
                bridge.setFlags(ms.id, ms.flags());
            }
            return true;
        }
    }

    public void resetStats() {
        hooks().forEach(HookDefinition::resetStats);
    }

    public int instrumentedClassCount() {
        return rewriter.plans().size();
    }

    private static HookDefinition hookIn(Site site, int id) {
        if (site instanceof PointSite ps) return ps.hook;
        return ((MethodSite) site).hooks.stream().filter(h -> h.getId() == id).findFirst().orElseThrow();
    }

    // ==================================================================== dispatch (hot path)

    @SuppressWarnings("unused") // via MethodHandle
    private Object onEnter(int id, Object self, Object[] args) {
        if (!(site(id) instanceof MethodSite ms)) return null;
        var ctx = new DefaultHookContext(ms.owner, ms.selector.name(), self, args, ms.parameterTypes);
        Object[] skip = executor.runEnter(ms.replace, ms.before, ctx);
        if (skip != null && !ms.acceptsReturn(skip[0], true)) {
            logger.warning("[NMSHook] Replacement return value " + skip[0] + " has the wrong type for "
                    + ms.owner.getSimpleName() + "#" + ms.selector + "; using the default value");
            skip[0] = null;
        }
        return skip;
    }

    @SuppressWarnings("unused") // via MethodHandle
    private Object onExit(int id, Object self, Object[] args, Object returnValue) {
        if (!(site(id) instanceof MethodSite ms)) return returnValue;
        var ctx = new DefaultHookContext(ms.owner, ms.selector.name(), self, args, ms.parameterTypes);
        ctx.setReturnValue(returnValue);
        Object result = executor.runExit(ms.after, ctx);
        if (result != returnValue && !ms.acceptsReturn(result, false)) {
            logger.warning("[NMSHook] AFTER hook set a return value of the wrong type (" + result + ") for "
                    + ms.owner.getSimpleName() + "#" + ms.selector + "; keeping the original");
            return returnValue;
        }
        return result;
    }

    @SuppressWarnings("unused") // via MethodHandle
    private Object onPoint(int id, Object[] operands) {
        if (!(site(id) instanceof PointSite ps)) return pass;
        Object result = executor.runPoint(ps.hook, operands, pass);
        if (result == pass) return pass;
        if (ps.hook.getKind() == HookDefinition.Kind.INVOKE) {
            if (result instanceof Object[] cancel && cancel.length > 0
                    && !Types.isAssignable(ps.expectedResult, cancel[0])
                    && !(cancel[0] == null && ps.expectedResult.isPrimitive())) {
                executor.reportFailure(ps.hook, new ClassCastException("cancel return value " + cancel[0]
                        + " is not a " + ps.expectedResult.getName()));
                return null;
            }
            return result;
        }
        if (!Types.isAssignable(ps.expectedResult, result)) {
            executor.reportFailure(ps.hook, new ClassCastException("handler returned "
                    + (result == null ? "null" : result.getClass().getName()) + ", expected " + ps.expectedResult.getName()));
            return pass;
        }
        return result;
    }

    private Site site(int id) {
        Site[] table = sites;
        return id < table.length ? table[id] : null;
    }

    // ==================================================================== sites

    /** Must hold {@link #lock}. */
    private void attach(MethodSite site, HookDefinition def) {
        site.hooks.add(def);
        site.rebuild();
        siteOfHook.put(def.getId(), site);
        bridge.setFlags(site.id, site.flags());
    }

    /** Must hold {@link #lock}. */
    private void attachPoint(PointSite site) {
        // points are not gated by flags: the executor checks hook.isEnabled()
        siteOfHook.put(site.hook.getId(), site);
    }

    /** Collects new sites of one registration and commits their instrumentation in one retransform per class. */
    private final class Batch {
        private final Map<Class<?>, ClassPlan> plans = new LinkedHashMap<>();
        private final List<Site> newSites = new ArrayList<>();
        private final Map<String, MethodSite> pendingMethodSites = new HashMap<>();

        MethodSite methodSite(Class<?> owner, MethodSelector selector, List<Method> methods) {
            String key = owner.getName() + "@" + System.identityHashCode(owner.getClassLoader()) + "#" + selector.key();
            MethodSite existing = methodSites.get(key);
            if (existing == null) existing = pendingMethodSites.get(key);
            if (existing != null) return existing;
            MethodSite site = new MethodSite(nextSiteId++, owner, selector, methods, key);
            pendingMethodSites.put(key, site);
            newSites.add(site);
            plans.put(owner, plan(owner).withAdvice(new ClassPlan.AdviceEntry(site.id, selector)));
            return site;
        }

        PointSite pointSite(Class<?> owner, MethodSelector selector, List<Method> methods, PointSpec spec,
                            HookDefinition hook, Class<?> expectedResult, boolean outerStatic) {
            PointSite site = new PointSite(nextSiteId++, owner, selector, methods, hook, expectedResult);
            newSites.add(site);
            plans.put(owner, plan(owner).withPoint(new ClassPlan.PointEntry(site.id, selector, spec)));
            return site;
        }

        private ClassPlan plan(Class<?> owner) {
            return plans.getOrDefault(owner, rewriter.planFor(owner));
        }

        void commit() {
            // publish sites before the bytecode can call them (flags stay 0 until hooks are attached)
            Site[] table = sites;
            int max = newSites.stream().mapToInt(s -> s.id).max().orElse(0);
            if (max >= table.length) table = Arrays.copyOf(table, Math.max(table.length * 2, max + 16));
            else table = table.clone();
            for (Site s : newSites) table[s.id] = s;
            sites = table;
            try {
                if (!plans.isEmpty()) rewriter.commit(plans);
            } catch (RuntimeException e) {
                Site[] rollback = sites.clone();
                for (Site s : newSites) rollback[s.id] = null;
                sites = rollback;
                throw e;
            }
            methodSites.putAll(pendingMethodSites);
        }
    }

    private abstract static class Site {
        final int id;
        final Class<?> owner;
        final MethodSelector selector;
        final List<Method> methods;

        Site(int id, Class<?> owner, MethodSelector selector, List<Method> methods) {
            this.id = id;
            this.owner = owner;
            this.selector = selector;
            this.methods = methods;
        }
    }

    private static final HookDefinition[] NONE = new HookDefinition[0];

    private static final class MethodSite extends Site {
        final String key;
        final List<HookDefinition> hooks = new ArrayList<>();
        /** Parameter types if the selector resolves to one overload (for setArg validation). */
        final Class<?>[] parameterTypes;
        final boolean anyPrimitiveReturn;
        volatile HookDefinition[] replace = NONE;
        volatile HookDefinition[] before = NONE;
        volatile HookDefinition[] after = NONE;

        MethodSite(int id, Class<?> owner, MethodSelector selector, List<Method> methods, String key) {
            super(id, owner, selector, methods);
            this.key = key;
            this.parameterTypes = methods.size() == 1 ? methods.getFirst().getParameterTypes() : null;
            this.anyPrimitiveReturn = methods.stream()
                    .anyMatch(m -> m.getReturnType().isPrimitive() && m.getReturnType() != void.class);
        }

        void rebuild() {
            Comparator<HookDefinition> order = Comparator.comparing(HookDefinition::getPriority)
                    .thenComparingInt(HookDefinition::getId);
            replace = select(HookDefinition.Kind.REPLACE, order);
            before = select(HookDefinition.Kind.BEFORE, order);
            after = select(HookDefinition.Kind.AFTER, order);
        }

        private HookDefinition[] select(HookDefinition.Kind kind, Comparator<HookDefinition> order) {
            return hooks.stream().filter(h -> h.getKind() == kind && h.isEnabled()).sorted(order)
                    .toArray(HookDefinition[]::new);
        }

        int flags() {
            int f = 0;
            if (replace.length > 0 || before.length > 0) f |= HookDispatcher.FLAG_ENTER;
            if (after.length > 0) f |= HookDispatcher.FLAG_EXIT;
            return f;
        }

        /** Whether {@code value} may be returned from every overload this site covers. */
        boolean acceptsReturn(Object value, boolean nullMeansDefault) {
            if (value == null) return nullMeansDefault || !anyPrimitiveReturn;
            for (Method m : methods) {
                Class<?> r = m.getReturnType();
                if (r != void.class && !Types.isAssignable(r, value)) return false;
            }
            return true;
        }
    }

    private static final class PointSite extends Site {
        final HookDefinition hook;
        final Class<?> expectedResult;

        PointSite(int id, Class<?> owner, MethodSelector selector, List<Method> methods, HookDefinition hook,
                  Class<?> expectedResult) {
            super(id, owner, selector, methods);
            this.hook = hook;
            this.expectedResult = expectedResult;
        }
    }

    private final class Handle implements HookHandle {
        private final int id;

        Handle(int id) {
            this.id = id;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public boolean isRegistered() {
            synchronized (lock) {
                return registrations.containsKey(id);
            }
        }

        @Override
        public void unregister() {
            unregisterGroup(id);
        }

        @Override
        public String toString() {
            return "HookHandle#" + id;
        }
    }

}
