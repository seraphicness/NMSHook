package dev.seraphicness.nmshook.transform;

import dev.seraphicness.nmshook.api.NMSHookDebug;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Transformer that inserts hook dispatch code into NMS methods using ByteBuddy Advice.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Inject {@link HookDispatcher} into the bootstrap CL (once)</li>
 *   <li>Inline {@link NMSAdvice} into the target method</li>
 *   <li>At runtime, inlined code dispatches via HookDispatcher -> HookExecutor</li>
 * </ol>
 *
 * <p>No retransformation is needed when hooks are added to the same method —
 * HookDispatcher dynamically queries the HookRegistry, so Advice only needs
 * to be installed once per method.
 */
public final class AdviceTransformer {

    private static final Logger LOGGER = Logger.getLogger(AdviceTransformer.class.getName());

    private final Set<String> instrumentedMethods = ConcurrentHashMap.newKeySet();
    private final Map<String, net.bytebuddy.agent.builder.ResettableClassFileTransformer> installedTransformers = new ConcurrentHashMap<>();
    private volatile boolean bootstrapInjected = false;

    /**
     * Injects {@link HookDispatcher} into the bootstrap ClassLoader.
     * This allows Advice code inlined into NMS classes to reference HookDispatcher.
     */
    public void ensureBootstrapInjected(Instrumentation instrumentation) {
        if (bootstrapInjected) return;
        synchronized (this) {
            if (bootstrapInjected) return;
            // Already injected earlier in this JVM (e.g. plugin reload)? Appending to the bootstrap
            // classpath again would only print another CDS warning and leave another temp jar.
            if (isPresentInBootstrap()) {
                bootstrapInjected = true;
                LOGGER.fine("HookDispatcher already present in bootstrap CL, skipping injection");
                return;
            }
            try {
                File tempFolder = new File(System.getProperty("java.io.tmpdir"), "nmshook");
                tempFolder.mkdirs();

                ClassInjector.UsingInstrumentation.of(
                        tempFolder,
                        ClassInjector.UsingInstrumentation.Target.BOOTSTRAP,
                        instrumentation
                ).inject(Map.of(
                        new TypeDescription.ForLoadedType(HookDispatcher.class),
                        ClassFileLocator.ForClassLoader.read(HookDispatcher.class)
                ));

                bootstrapInjected = true;
                LOGGER.info("HookDispatcher injected into bootstrap CL");
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject HookDispatcher into bootstrap CL", e);
            }
        }
    }

    private static boolean isPresentInBootstrap() {
        try {
            Class.forName(HookDispatcher.class.getName(), false, null);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Installs {@link NMSAdvice} on a specific method of the target class.
     * Already-instrumented methods are skipped (HookDispatcher handles dynamic dispatch).
     */
    public void apply(Class<?> targetClass, String methodName, Instrumentation instrumentation) {
        String key = targetClass.getName() + "#" + methodName;
        if (!instrumentedMethods.add(key)) {
            LOGGER.fine(() -> "Already instrumented, skipping: " + key);
            return;
        }

        ensureBootstrapInjected(instrumentation);

        ClassFileLocator adviceLocator = ClassFileLocator.ForClassLoader.of(
                NMSAdvice.class.getClassLoader());

        var transformer = new AgentBuilder.Default(new net.bytebuddy.ByteBuddy()
                        .with(net.bytebuddy.ClassFileVersion.ofJavaVersion(21)))
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                // Ignore non-NMS target classes to prevent interference with other plugin classes
                .ignore(ElementMatchers.nameContains("$Proxy")
                        .or(ElementMatchers.nameStartsWith("com.comphenix."))
                        .or(ElementMatchers.nameStartsWith("org.bukkit."))
                        .or(ElementMatchers.nameStartsWith("io.papermc."))
                        .or(ElementMatchers.nameStartsWith("io.netty."))
                        .or(ElementMatchers.nameStartsWith("com.google."))
                        .or(ElementMatchers.nameStartsWith("com.mojang."))
                        .or(ElementMatchers.isSynthetic()))
                .with(new AgentBuilder.Listener.Adapter() {
                    @Override
                    public void onError(String typeName, ClassLoader classLoader,
                                        JavaModule module, boolean loaded, Throwable throwable) {
                        LOGGER.fine(() -> "Transform skipped: " + typeName + " — " + throwable.getMessage());
                    }

                    @Override
                    public void onTransformation(TypeDescription typeDescription,
                                                 ClassLoader classLoader,
                                                 JavaModule module,
                                                 boolean loaded,
                                                 DynamicType dynamicType) {
                        if (NMSHookDebug.isEnabled() && NMSHookDebug.getDumpDir() != null) {
                            try {
                                dynamicType.saveIn(NMSHookDebug.getDumpDir().toFile());
                                LOGGER.info(() -> "[Debug] Bytecode dumped: " + typeDescription.getName());
                            } catch (Exception e) {
                                LOGGER.warning(() -> "[Debug] Bytecode dump failed: " + e.getMessage());
                            }
                        }
                    }
                })
                .type(ElementMatchers.is(targetClass))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(
                                Advice.to(NMSAdvice.class, adviceLocator)
                                        .on(ElementMatchers.named(methodName))
                        )
                )
                .installOn(instrumentation);
        installedTransformers.put(key, transformer);

        LOGGER.info(() -> "Advice instrumentation installed: " + key);
    }

    /** Removes the Advice from a previously instrumented method, restoring original bytecode. */
    public void remove(Class<?> targetClass, String methodName, Instrumentation instrumentation) {
        String key = targetClass.getName() + "#" + methodName;
        var transformer = installedTransformers.remove(key);
        if (transformer != null) {
            transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            instrumentedMethods.remove(key);
            LOGGER.info(() -> "Advice removed: " + key);
        }
    }

    /** Removes all installed Advice transformations. */
    public void removeAll(Instrumentation instrumentation) {
        installedTransformers.forEach((key, transformer) -> {
            transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            LOGGER.fine(() -> "Advice removed: " + key);
        });
        installedTransformers.clear();
        instrumentedMethods.clear();
    }
}
