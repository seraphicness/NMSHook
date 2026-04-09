package dev.velvet.nmshook.plugin;

import dev.velvet.nmshook.agent.NMSAgent;
import dev.velvet.nmshook.api.NMSHookEngine;
import dev.velvet.nmshook.api.hook.HookPoint;
import dev.velvet.nmshook.api.hook.HookRequest;
import dev.velvet.nmshook.api.transform.TransformRequest;
import dev.velvet.nmshook.hook.DefaultHookContext;
import dev.velvet.nmshook.hook.HookDefinition;
import dev.velvet.nmshook.hook.HookExecutor;
import dev.velvet.nmshook.hook.HookPriority;
import dev.velvet.nmshook.hook.HookRegistry;
import dev.velvet.nmshook.mixin.AnnotationParser;
import dev.velvet.nmshook.mixin.ConflictDetector;
import dev.velvet.nmshook.mixin.MixinApplicator;
import dev.velvet.nmshook.mixin.MixinDefinition;
import dev.velvet.nmshook.mixin.MixinHandlerAdapter;
import dev.velvet.nmshook.mixin.MixinMethod;
import dev.velvet.nmshook.mixin.MixinRegistry;
import dev.velvet.nmshook.resolver.NMSClassResolver;
import dev.velvet.nmshook.transform.TransformEngine;

import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default implementation of {@link NMSHookEngine}.
 * Acts as the Composition Root, assembling all domain modules to complete
 * the hook/Mixin registration flow.
 */
final class DefaultNMSHookEngine implements NMSHookEngine {

    private final Logger logger;
    private final HookRegistry hookRegistry;
    private final HookExecutor hookExecutor;
    private final MixinRegistry mixinRegistry;
    private final AnnotationParser annotationParser;
    private final ConflictDetector conflictDetector;
    private final TransformEngine transformEngine;
    private final NMSAgent agent;
    private final NMSClassResolver resolver;

    DefaultNMSHookEngine(Logger logger,
                         HookRegistry hookRegistry,
                         HookExecutor hookExecutor,
                         MixinRegistry mixinRegistry,
                         AnnotationParser annotationParser,
                         ConflictDetector conflictDetector,
                         TransformEngine transformEngine,
                         NMSAgent agent,
                         NMSClassResolver resolver) {
        this.logger = logger;
        this.hookRegistry = hookRegistry;
        this.hookExecutor = hookExecutor;
        this.mixinRegistry = mixinRegistry;
        this.annotationParser = annotationParser;
        this.conflictDetector = conflictDetector;
        this.transformEngine = transformEngine;
        this.agent = agent;
        this.resolver = resolver;
    }

    /**
     * Sets up the handlers for HookDispatcher injected into the bootstrap CL.
     * At runtime, calls flow: NMS method -> HookDispatcher -> these handlers -> HookExecutor.
     */
    void wireDispatcher() {
        try {
            // Retrieve HookDispatcher injected into the bootstrap CL
            Class<?> dispatcherClass = Class.forName(
                    "dev.velvet.nmshook.transform.HookDispatcher", true, null);

            // BEFORE handler: executes REPLACE + BEFORE hooks -> [cancelled, returnValue]
            dispatcherClass.getField("beforeHandler").set(null,
                    (Function<Object[], Object[]>) params -> {
                        try {
                            Object instance = params[0];
                            Object[] args = (Object[]) params[1];
                            String className = (String) params[2];
                            String methodName = (String) params[3];

                            Class<?> targetClass = Class.forName(className);
                            var ctx = new DefaultHookContext(instance, args, className, methodName);
                            var result = hookExecutor.handleBeforePhase(targetClass, methodName, ctx);
                            return new Object[]{result.cancelled(), result.returnValue()};
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "BEFORE dispatch exception", e);
                            return new Object[]{false, null};
                        }
                    });

            // AFTER handler: executes AFTER hooks -> modified return value
            dispatcherClass.getField("afterHandler").set(null,
                    (Function<Object[], Object>) params -> {
                        try {
                            Object instance = params[0];
                            Object[] args = (Object[]) params[1];
                            String className = (String) params[2];
                            String methodName = (String) params[3];
                            Object returnValue = params[4];

                            Class<?> targetClass = Class.forName(className);
                            var ctx = new DefaultHookContext(instance, args, className, methodName);
                            ctx.setReturnValue(returnValue);
                            return hookExecutor.handleAfterPhase(targetClass, methodName, ctx);
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "AFTER dispatch exception", e);
                            return params[4]; // Keep original return value
                        }
                    });

            logger.info("[NMSHook] HookDispatcher handlers wired successfully");
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up HookDispatcher handlers", e);
        }
    }

    @Override
    public void registerHook(HookRequest request) {
        var definition = new HookDefinition(
                request.owner(),
                request.target(),
                request.method(),
                request.at(),
                request.cancellable(),
                new HookPriority(request.priority()),
                request.listener()
        );
        hookRegistry.register(definition);

        // Bytecode transformation (once per method — skipped if already installed)
        TransformRequest transformRequest = toTransformRequest(request);
        transformEngine.apply(transformRequest, agent.getInstrumentation());

        logger.info("[NMSHook] Hook registered: %s#%s @%s (priority=%d)"
                .formatted(request.target().getSimpleName(), request.method(),
                        request.at(), request.priority()));
    }

    @Override
    public void registerMixin(Class<?> mixinClass, Object owner) {
        // 1. Parse annotations
        MixinDefinition definition = annotationParser.parse(mixinClass, owner);
        mixinRegistry.register(definition);

        // 2. Detect conflicts
        List<MixinDefinition> existing = mixinRegistry.findByTarget(definition.getTargetClass());
        conflictDetector.detect(existing);

        // 3. Create concrete instance of the hook class (supports abstract classes)
        Object hookInstance = createHookInstance(mixinClass);

        // 4. Create handler adapter — @Shadow binding + reflection wrapping
        var adapter = new MixinHandlerAdapter(definition, hookInstance);

        // 5. Register each MixinMethod as a HookDefinition + install Advice
        int registered = 0;
        for (MixinMethod method : definition.getMethods()) {
            HookPoint hookPoint = toMixinHookPoint(method.getType());
            if (hookPoint == null) continue;

            // @ModifyArg must execute before other BEFORE hooks
            int priority = definition.getPriority();
            if (method.getType() == MixinMethod.Type.MODIFY_ARG) {
                priority = Math.max(0, priority - 100);
            }

            // Create HookListener + register in HookRegistry
            var listener = adapter.toListener(method);
            var hookDef = new HookDefinition(
                    owner,
                    definition.getTargetClass(),
                    method.getTargetMethodName(),
                    hookPoint,
                    method.isCancellable(),
                    new HookPriority(priority),
                    listener
            );
            hookRegistry.register(hookDef);

            // Install Advice (once per method — skipped if duplicate)
            transformEngine.apply(
                    new TransformRequest.AdviceRequest(
                            definition.getTargetClass(),
                            method.getTargetMethodName(),
                            hookPoint == HookPoint.BEFORE ? "HEAD" : "TAIL",
                            method.isCancellable(), null),
                    agent.getInstrumentation());
            registered++;
        }

        logger.info("[NMSHook] NMS hook registered: %s -> %s (%d/%d methods)"
                .formatted(mixinClass.getSimpleName(),
                        definition.getTargetClass().getSimpleName(),
                        registered, definition.getMethods().size()));
    }

    private static HookPoint toMixinHookPoint(MixinMethod.Type type) {
        return switch (type) {
            case INJECT_HEAD -> HookPoint.BEFORE;
            case INJECT_TAIL -> HookPoint.AFTER;
            case OVERWRITE -> HookPoint.REPLACE;
            case MODIFY_ARG -> HookPoint.BEFORE;
            case INJECT_INVOKE, SHADOW -> null;
        };
    }

    @Override
    public void unregisterAll(Object owner) {
        if (owner == null) {
            hookRegistry.clear();
            mixinRegistry.clear();
            transformEngine.removeAll(agent.getInstrumentation());
        } else {
            hookRegistry.unregisterByOwner(owner);
            mixinRegistry.unregisterByOwner(owner);
        }
        logger.info("[NMSHook] Hooks unregistered" + (owner != null ? " for " + owner : ""));
    }

    /**
     * Creates a concrete subclass of an abstract hook class using ByteBuddy and instantiates it.
     * Concrete classes are instantiated directly.
     */
    private Object createHookInstance(Class<?> hookClass) {
        try {
            if (java.lang.reflect.Modifier.isAbstract(hookClass.getModifiers())) {
                return new net.bytebuddy.ByteBuddy(
                                net.bytebuddy.ClassFileVersion.ofJavaVersion(21))
                        .subclass(hookClass)
                        .make()
                        .load(hookClass.getClassLoader())
                        .getLoaded()
                        .getDeclaredConstructor()
                        .newInstance();
            } else {
                return hookClass.getDeclaredConstructor().newInstance();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create hook instance: " + hookClass.getName(), e);
        }
    }

    private TransformRequest toTransformRequest(HookRequest request) {
        return switch (request.at()) {
            case BEFORE -> new TransformRequest.AdviceRequest(
                    request.target(), request.method(), "HEAD",
                    request.cancellable(), request.listener());
            case AFTER -> new TransformRequest.AdviceRequest(
                    request.target(), request.method(), "TAIL",
                    request.cancellable(), request.listener());
            case REPLACE -> new TransformRequest.DelegationRequest(
                    request.target(), request.method(), request.listener());
        };
    }
}
