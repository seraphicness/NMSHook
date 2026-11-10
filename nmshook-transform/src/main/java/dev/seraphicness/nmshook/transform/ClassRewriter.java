package dev.seraphicness.nmshook.transform;

import dev.seraphicness.nmshook.api.NMSHookDebug;
import dev.seraphicness.nmshook.api.target.HookTargetException;
import dev.seraphicness.nmshook.api.target.MethodSelector;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;
import net.bytebuddy.utility.OpenedClassReader;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the single {@link ClassFileTransformer} NMSHook registers and the {@link ClassPlan} of every
 * instrumented class.
 *
 * <ul>
 *   <li>Class loads that are not retransformations of a planned class return immediately
 *       (no parsing, no matching).</li>
 *   <li>Each change re-transforms the class once, from its original bytes, with its full plan.</li>
 *   <li>If the JVM or ByteBuddy rejects the result, the previous plan is restored and a
 *       {@link HookTargetException} explains why — nothing is silently skipped.</li>
 * </ul>
 */
public final class ClassRewriter {

    private static final Logger LOGGER = Logger.getLogger(ClassRewriter.class.getName());
    private static final String DISPATCHER_NAME = "dev.seraphicness.nmshook.transform.HookDispatcher";
    private static final int EXPECTED_ABI = 3;

    private final Instrumentation instrumentation;
    private final Map<Class<?>, ClassPlan> plans = new ConcurrentHashMap<>();
    private final ThreadLocal<ClassPlan> currentPlan = new ThreadLocal<>();
    private final ThreadLocal<Throwable> lastError = new ThreadLocal<>();
    private final ClassFileLocator adviceLocator;
    private final TypeDescription nmsAdvice;
    private final TypeDescription cancelCatchAdvice;
    private final ClassFileTransformer delegate;
    private final ClassFileTransformer transformer;
    private final DispatcherBridge bridge;
    private volatile boolean closed;

    public ClassRewriter(Instrumentation instrumentation) {
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new UnsupportedOperationException("This JVM does not support class retransformation.");
        }
        this.instrumentation = instrumentation;
        ClassLoader own = ClassRewriter.class.getClassLoader();
        this.adviceLocator = ClassFileLocator.ForClassLoader.of(own);
        // Described from bytes, never loaded: loading them in this CL is not needed for inlining.
        TypePool pool = TypePool.Default.of(adviceLocator);
        this.nmsAdvice = pool.describe(NMSAdvice.class.getName()).resolve();
        this.cancelCatchAdvice = pool.describe(CancelCatchAdvice.class.getName()).resolve();
        this.bridge = injectDispatcher();

        this.delegate = new AgentBuilder.Default(new ByteBuddy())
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .ignore(ElementMatchers.none())
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                        boolean loaded, Throwable throwable) {
                        lastError.set(throwable);
                    }

                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                 JavaModule module, boolean loaded, DynamicType dynamicType) {
                        dumpIfDebug(typeDescription, dynamicType);
                    }
                })
                .type(ElementMatchers.any())
                .transform((builder, typeDescription, classLoader, module, protectionDomain) -> {
                    ClassPlan plan = currentPlan.get();
                    return plan == null ? builder : apply(builder, plan);
                })
                .makeRaw();

        this.transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String className,
                                    Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                if (classBeingRedefined == null) return null;
                ClassPlan plan = plans.get(classBeingRedefined);
                if (plan == null) return null;
                currentPlan.set(plan);
                try {
                    return delegate.transform(module, loader, className, classBeingRedefined,
                            protectionDomain, classfileBuffer);
                } catch (Throwable t) {
                    lastError.set(t);
                    return null;
                } finally {
                    currentPlan.remove();
                }
            }
        };
        instrumentation.addTransformer(transformer, true);
    }

    public DispatcherBridge dispatcher() {
        return bridge;
    }

    public ClassPlan planFor(Class<?> cls) {
        return plans.getOrDefault(cls, ClassPlan.EMPTY);
    }

    public Map<Class<?>, ClassPlan> plans() {
        return Map.copyOf(plans);
    }

    /**
     * Applies new plans and re-transforms each affected class once.
     * On failure, every class in the batch is restored to its previous plan.
     */
    public synchronized void commit(Map<Class<?>, ClassPlan> changes) {
        if (closed) throw new IllegalStateException("NMSHook transformer is shut down");
        Map<Class<?>, ClassPlan> previous = new HashMap<>();
        for (var e : changes.entrySet()) {
            previous.put(e.getKey(), plans.get(e.getKey()));
            putPlan(e.getKey(), e.getValue());
        }
        for (Class<?> cls : changes.keySet()) {
            Throwable error = retransform(cls);
            if (error != null) {
                previous.forEach(this::putPlan);
                for (Class<?> c : changes.keySet()) {
                    Throwable restoreError = retransform(c);
                    if (restoreError != null) {
                        LOGGER.log(Level.SEVERE, "Failed to restore " + c.getName(), restoreError);
                    }
                }
                throw new HookTargetException("Failed to instrument " + cls.getName() + ": " + describe(error), error);
            }
        }
    }

    public void commit(Class<?> cls, ClassPlan plan) {
        commit(Map.of(cls, plan));
    }

    private void putPlan(Class<?> cls, ClassPlan plan) {
        if (plan == null || plan.isEmpty()) plans.remove(cls);
        else plans.put(cls, plan);
    }

    /** Removes the transformer, restores every instrumented class and clears the dispatcher. */
    public synchronized void close() {
        if (closed) return;
        closed = true;
        List<Class<?>> classes = new ArrayList<>(plans.keySet());
        plans.clear();
        instrumentation.removeTransformer(transformer);
        for (Class<?> cls : classes) {
            Throwable error = retransform(cls);
            if (error != null) LOGGER.log(Level.WARNING, "Failed to restore " + cls.getName(), error);
        }
        bridge.reset();
    }

    private Throwable retransform(Class<?> cls) {
        lastError.remove();
        try {
            instrumentation.retransformClasses(cls);
        } catch (Throwable t) {
            return t;
        }
        Throwable error = lastError.get();
        lastError.remove();
        return error;
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    // ------------------------------------------------------------------ plan -> ByteBuddy

    private DynamicType.Builder<?> apply(DynamicType.Builder<?> builder, ClassPlan plan) {
        // Wrappers registered later see the class file first. Advice is registered first so that it
        // processes the output of the point visitors, which in turn see the original bytecode
        // (so local variable slots and ordinals refer to the unmodified method).
        for (ClassPlan.AdviceEntry entry : plan.advice()) {
            builder = builder.visit(Advice.withCustomMapping()
                    .bind(HookId.class, entry.id())
                    .to(nmsAdvice, adviceLocator)
                    .on(matcher(entry.method())));
        }
        for (MethodSelector selector : plan.cancelCatchers()) {
            builder = builder.visit(Advice.to(cancelCatchAdvice, adviceLocator).on(matcher(selector)));
        }
        for (ClassPlan.PointEntry entry : plan.points()) {
            builder = builder.visit(new AsmVisitorWrapper.ForDeclaredMethods()
                    .writerFlags(ClassWriter.COMPUTE_MAXS)
                    .method(matcher(entry.method()),
                            (instrumentedType, instrumentedMethod, methodVisitor, context, typePool,
                             writerFlags, readerFlags) ->
                                    new PointMethodVisitor(methodVisitor, entry.spec(), entry.id(), true,
                                            instrumentedType.getInternalName(), instrumentedMethod.isStatic(),
                                            instrumentedMethod.getDescriptor(), null)));
        }
        return builder;
    }

    static ElementMatcher.Junction<MethodDescription> matcher(MethodSelector selector) {
        ElementMatcher.Junction<MethodDescription> m = ElementMatchers.<MethodDescription>isMethod()
                .and(ElementMatchers.named(selector.name()))
                .and(ElementMatchers.not(ElementMatchers.isAbstract()))
                .and(ElementMatchers.not(ElementMatchers.isNative()))
                .and(ElementMatchers.not(ElementMatchers.isBridge()));
        if (selector.hasDescriptor()) {
            String desc = selector.descriptor();
            if (selector.isFullDescriptor()) {
                m = m.and(ElementMatchers.hasDescriptor(desc));
            } else {
                m = m.and(new ElementMatcher.Junction.AbstractBase<MethodDescription>() {
                    @Override
                    public boolean matches(MethodDescription target) {
                        return target.getDescriptor().startsWith(desc);
                    }
                });
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ validation

    /**
     * Scans the original bytecode of the selected method(s) for a point.
     *
     * @return the inspection result, or {@code null} if the class file is not available as a resource
     */
    public static PointInspection inspect(Class<?> cls, MethodSelector selector, PointSpec spec) {
        byte[] bytes;
        try {
            bytes = ClassFileLocator.ForClassLoader.read(cls);
        } catch (Exception | LinkageError e) {
            return null;
        }
        List<String> seen = new ArrayList<>();
        List<PointMethodVisitor> visitors = new ArrayList<>();
        String internalName = cls.getName().replace('.', '/');
        new ClassReader(bytes).accept(new ClassVisitor(OpenedClassReader.ASM_API) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if (!selector.matches(name, descriptor) || (access & Opcodes_ACC_SKIP) != 0) return null;
                var v = new PointMethodVisitor(null, spec, -1, false, internalName,
                        Modifier.isStatic(access), descriptor, seen);
                visitors.add(v);
                return v;
            }
        }, ClassReader.SKIP_FRAMES);
        int candidates = visitors.stream().mapToInt(PointMethodVisitor::occurrences).sum();
        int matches = visitors.stream().mapToInt(PointMethodVisitor::applied).sum();
        return new PointInspection(visitors.size(), candidates, matches, List.copyOf(seen));
    }

    private static final int Opcodes_ACC_SKIP = net.bytebuddy.jar.asm.Opcodes.ACC_ABSTRACT
            | net.bytebuddy.jar.asm.Opcodes.ACC_NATIVE | net.bytebuddy.jar.asm.Opcodes.ACC_BRIDGE;

    // ------------------------------------------------------------------ bootstrap injection

    private DispatcherBridge injectDispatcher() {
        Class<?> existing = findBootstrapDispatcher();
        if (existing == null) {
            try {
                Map<String, byte[]> classes = new HashMap<>();
                for (String name : List.of(DISPATCHER_NAME, DISPATCHER_NAME + "$Cancel")) {
                    classes.put(name, adviceLocator.locate(name).resolve());
                }
                // Preferred: define the classes directly in the bootstrap loader with jdk.internal.misc.Unsafe,
                // exported to NMSHook through Instrumentation. Unlike appending a jar to the bootstrap class
                // path this keeps CDS enabled (no "Sharing is only supported..." warning), and unlike
                // sun.misc.Unsafe it prints no "terminally deprecated method" warning on Java 24+.
                if (defineInBootstrap(classes)) {
                    LOGGER.info("HookDispatcher defined in bootstrap CL");
                } else {
                    File folder = Files.createTempDirectory("nmshook").toFile();
                    folder.deleteOnExit();
                    ClassInjector.UsingInstrumentation
                            .of(folder, ClassInjector.UsingInstrumentation.Target.BOOTSTRAP, instrumentation)
                            .injectRaw(classes);
                    LOGGER.info("HookDispatcher injected into bootstrap CL (bootstrap class path append)");
                }
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Failed to inject HookDispatcher into bootstrap CL", e);
            }
            existing = findBootstrapDispatcher();
            if (existing == null) throw new IllegalStateException("HookDispatcher injection had no effect");
        } else {
            LOGGER.fine("HookDispatcher already present in bootstrap CL (plugin reload)");
        }
        try {
            int abi = existing.getField("ABI").getInt(null);
            if (abi != EXPECTED_ABI) {
                throw new IllegalStateException("A different NMSHook version (dispatcher ABI " + abi
                        + ", expected " + EXPECTED_ABI + ") is still loaded in this JVM. Restart the server.");
            }
            return new DispatcherBridge(existing);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Incompatible HookDispatcher in bootstrap CL. Restart the server.", e);
        }
    }

    /** @return false if the internal API is not available (the caller falls back to a class path append) */
    private boolean defineInBootstrap(Map<String, byte[]> classes) {
        try {
            Module base = Object.class.getModule();
            Module self = ClassRewriter.class.getModule();
            instrumentation.redefineModule(base, java.util.Set.of(),
                    Map.of("jdk.internal.misc", java.util.Set.of(self)), Map.of(),
                    java.util.Set.of(), Map.of());
            Class<?> unsafeClass = Class.forName("jdk.internal.misc.Unsafe");
            Object unsafe = unsafeClass.getMethod("getUnsafe").invoke(null);
            java.lang.reflect.Method define = unsafeClass.getMethod("defineClass", String.class, byte[].class,
                    int.class, int.class, ClassLoader.class, ProtectionDomain.class);
            // outer class first, nested classes after
            for (String name : classes.keySet().stream().sorted().toList()) {
                byte[] bytes = classes.get(name);
                define.invoke(unsafe, name, bytes, 0, bytes.length, null, null);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.log(Level.FINE, "Direct bootstrap definition unavailable", e);
            return false;
        }
    }

    private static Class<?> findBootstrapDispatcher() {
        try {
            return Class.forName(DISPATCHER_NAME, true, null);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static void dumpIfDebug(TypeDescription type, DynamicType dynamicType) {
        if (!NMSHookDebug.isEnabled() || NMSHookDebug.getDumpDir() == null) return;
        try {
            dynamicType.saveIn(NMSHookDebug.getDumpDir().toFile());
            LOGGER.info(() -> "[Debug] Bytecode dumped: " + type.getName());
        } catch (Exception e) {
            LOGGER.warning(() -> "[Debug] Bytecode dump failed: " + e.getMessage());
        }
    }
}
