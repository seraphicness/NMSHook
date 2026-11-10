package dev.seraphicness.nmshook.mixin;

import dev.seraphicness.nmshook.api.callback.CallbackInfo;
import dev.seraphicness.nmshook.api.callback.CallbackInfoReturnable;
import dev.seraphicness.nmshook.api.hook.HookListener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter that converts a {@link MixinMethod} into a {@link HookListener}.
 *
 * <p>Wraps the Mixin class handler methods via reflection to produce
 * listeners that can be registered in the HookRegistry.
 *
 * <p>Flow: hook triggered -> @Shadow binding -> handler argument assembly -> reflective invocation
 * -> apply CallbackInfo result (cancel / modify return value)
 */
public final class MixinHandlerAdapter {

    private static final Logger LOGGER = Logger.getLogger(MixinHandlerAdapter.class.getName());

    private final MixinDefinition definition;
    private final ThreadLocal<Object> mixinInstances;
    private final List<ShadowBinder.ShadowBinding> shadowBindings;

    /**
     * @param definition the parsed NMSMixin definition
     * @param mixinInstance a concrete instance of the hook class (created externally)
     */
    public MixinHandlerAdapter(MixinDefinition definition, Object mixinInstance) {
        this.definition = definition;
        this.mixinInstances = ThreadLocal.withInitial(() -> cloneInstance(mixinInstance));
        this.shadowBindings = ShadowBinder.analyze(
                definition.getMixinClass(), definition.getTargetClass());
    }

    private static Object cloneInstance(Object template) {
        try {
            return template.getClass().getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to create thread-local hook instance for " + template.getClass().getName()
                    + ". Ensure the concrete subclass has a no-arg constructor.", e);
        }
    }

    /** Creates an appropriate HookListener based on the MixinMethod type. */
    public HookListener toListener(MixinMethod mixinMethod) {
        return switch (mixinMethod.getType()) {
            case INJECT_HEAD -> createInjectListener(mixinMethod, true);
            case INJECT_TAIL -> createInjectListener(mixinMethod, false);
            case OVERWRITE -> createOverwriteListener(mixinMethod);
            case MODIFY_ARG -> createModifyArgListener(mixinMethod);
            default -> ctx -> {
            };
        };
    }

    /**
     * Converts an @Inject HEAD/TAIL handler into a HookListener.
     * Handler signature: (target method args..., CallbackInfo/CallbackInfoReturnable)
     */
    private HookListener createInjectListener(MixinMethod mixinMethod, boolean isHead) {
        Method handler = mixinMethod.getHandlerMethod();
        handler.setAccessible(true);
        Class<?>[] paramTypes = handler.getParameterTypes();
        int paramCount = paramTypes.length;

        boolean returnable = paramCount > 0
                && CallbackInfoReturnable.class.isAssignableFrom(paramTypes[paramCount - 1]);

        return ctx -> {
            try {
                bindShadows(ctx.getInstance());

                Object[] handlerArgs = new Object[paramCount];

                // Map target method args to handler parameters (the last one is CallbackInfo)
                Object[] targetArgs = ctx.getArgs();
                for (int i = 0; i < paramCount - 1 && i < targetArgs.length; i++) {
                    handlerArgs[i] = targetArgs[i];
                }

                if (returnable) {
                    var cir = new CallbackInfoReturnable<>(ctx.getReturnValue());
                    handlerArgs[paramCount - 1] = cir;
                    handler.invoke(mixinInstances.get(), handlerArgs);
                    writeBackShadows(ctx.getInstance());
                    if (cir.isCancelled()) ctx.cancel();
                    ctx.setReturnValue(cir.getReturnValue());
                } else {
                    var ci = new CallbackInfo();
                    handlerArgs[paramCount - 1] = ci;
                    handler.invoke(mixinInstances.get(), handlerArgs);
                    writeBackShadows(ctx.getInstance());
                    if (ci.isCancelled()) ctx.cancel();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "@Inject handler execution failed: " + handler.getName(), e);
            }
        };
    }

    /**
     * Converts an @Overwrite handler into a REPLACE HookListener.
     * Handler signature: same as the target method.
     */
    private HookListener createOverwriteListener(MixinMethod mixinMethod) {
        Method handler = mixinMethod.getHandlerMethod();
        handler.setAccessible(true);

        return ctx -> {
            try {
                bindShadows(ctx.getInstance());
                Object result = handler.invoke(mixinInstances.get(), ctx.getArgs());
                writeBackShadows(ctx.getInstance());
                ctx.setReturnValue(result);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "@Overwrite handler execution failed: " + handler.getName(), e);
            }
        };
    }

    /**
     * Converts a @ModifyArg handler into a BEFORE HookListener.
     * Handler signature: (single argument to modify) -> returns the modified value.
     */
    private HookListener createModifyArgListener(MixinMethod mixinMethod) {
        Method handler = mixinMethod.getHandlerMethod();
        handler.setAccessible(true);
        int argIndex = mixinMethod.getArgIndex();

        return ctx -> {
            try {
                bindShadows(ctx.getInstance());
                Object original = ctx.getArgs()[argIndex];
                Object modified = handler.invoke(mixinInstances.get(), original);
                writeBackShadows(ctx.getInstance());
                ctx.setArg(argIndex, modified);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "@ModifyArg handler execution failed: " + handler.getName(), e);
            }
        };
    }

    /** Copies @Shadow fields from the target instance to the Mixin instance (read). */
    private void bindShadows(Object targetInstance) {
        if (targetInstance == null || shadowBindings.isEmpty()) return;
        for (var binding : shadowBindings) {
            try {
                Object value = binding.targetField().get(targetInstance);
                binding.mixinField().set(mixinInstances.get(), value);
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "@Shadow read failed: " + binding.mixinField().getName(), e);
            }
        }
    }

    /** Copies @Shadow fields from the Mixin instance back to the target instance (write-back). */
    private void writeBackShadows(Object targetInstance) {
        if (targetInstance == null || shadowBindings.isEmpty()) return;
        for (var binding : shadowBindings) {
            try {
                Object value = binding.mixinField().get(mixinInstances.get());
                binding.targetField().set(targetInstance, value);
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "@Shadow write-back failed: " + binding.mixinField().getName(), e);
            }
        }
    }

}
