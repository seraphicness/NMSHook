package dev.seraphicness.nmshook.mixin;

import dev.seraphicness.nmshook.api.callback.CallbackInfo;
import dev.seraphicness.nmshook.api.callback.CallbackInfoReturnable;
import dev.seraphicness.nmshook.api.hook.HookListener;
import dev.seraphicness.nmshook.hook.PointHandler;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns the handler methods of one Mixin registration into hook listeners / point handlers.
 *
 * <ul>
 *   <li>Each thread gets its own Mixin instance (Folia: regions tick on different threads).</li>
 *   <li>{@code @Shadow} fields are copied from the target instance before the handler and written back after.</li>
 *   <li>Abstract {@code @Shadow} methods are implemented by forwarding to the current target instance.</li>
 *   <li>Exceptions thrown by handlers propagate to the executor (failure counting / circuit breaker).</li>
 * </ul>
 */
public final class MixinHandlerAdapter {

    private static final Logger LOGGER = Logger.getLogger(MixinHandlerAdapter.class.getName());

    private final MixinDefinition definition;
    private final List<ShadowBinder.ShadowBinding> shadowBindings;
    private final Map<Method, Method> shadowMethods;
    private final Class<?> concreteClass;
    private final ThreadLocal<Object> mixinInstances;
    private final ThreadLocal<Object> currentTarget = new ThreadLocal<>();

    public MixinHandlerAdapter(MixinDefinition definition) {
        this.definition = definition;
        Class<?> mixinClass = definition.getMixinClass();
        this.shadowBindings = ShadowBinder.analyze(mixinClass, definition.getTargetClass());
        this.shadowMethods = ShadowBinder.analyzeMethods(mixinClass, definition.getTargetClass());
        this.concreteClass = concreteClass(mixinClass);
        Object first = newInstance(); // fail fast at registration if it cannot be instantiated
        this.mixinInstances = ThreadLocal.withInitial(this::newInstance);
        this.mixinInstances.set(first);
    }

    private Class<?> concreteClass(Class<?> mixinClass) {
        if (!Modifier.isAbstract(mixinClass.getModifiers())) return mixinClass;
        InvocationHandler forward = (proxy, method, args) -> {
            Method target = shadowMethods.get(method);
            if (target == null) {
                throw new AbstractMethodError(method + " is abstract and not annotated with @Shadow");
            }
            Object self = Modifier.isStatic(target.getModifiers()) ? null : currentTarget.get();
            if (self == null && !Modifier.isStatic(target.getModifiers())) {
                throw new IllegalStateException("@Shadow method " + method.getName()
                        + " called outside of a hook on an instance method");
            }
            try {
                return target.invoke(self, args == null ? new Object[0] : args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return new ByteBuddy(ClassFileVersion.ofThisVm(ClassFileVersion.JAVA_V21))
                .subclass(mixinClass)
                .name(mixinClass.getName() + "$NMSHookImpl")
                .method(ElementMatchers.isAbstract())
                .intercept(InvocationHandlerAdapter.of(forward))
                .make()
                // same ClassLoader and package as the Mixin, so package-private @Shadow methods can be implemented
                .load(mixinClass.getClassLoader(), sameLoaderStrategy(mixinClass))
                .getLoaded();
    }

    private static ClassLoadingStrategy<ClassLoader> sameLoaderStrategy(Class<?> mixinClass) {
        try {
            return ClassLoadingStrategy.UsingLookup.of(
                    java.lang.invoke.MethodHandles.privateLookupIn(mixinClass, java.lang.invoke.MethodHandles.lookup()));
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot define the implementation of abstract Mixin "
                    + mixinClass.getName() + " (package not open to NMSHook)", e);
        }
    }

    private Object newInstance() {
        try {
            var ctor = concreteClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot instantiate Mixin " + definition.getMixinClass().getName()
                    + ". Mixin classes need a no-arg constructor.", e);
        }
    }

    // ---------------------------------------------------------------- method advice (HEAD/TAIL/Overwrite/ModifyArg)

    public HookListener toListener(MixinMethod mixinMethod) {
        return switch (mixinMethod.getType()) {
            case INJECT_HEAD, INJECT_TAIL -> injectListener(mixinMethod);
            case OVERWRITE -> overwriteListener(mixinMethod);
            case MODIFY_ARG -> modifyArgListener(mixinMethod);
            default -> throw new IllegalArgumentException(mixinMethod.getType() + " is a point, use toPointHandler");
        };
    }

    private HookListener injectListener(MixinMethod mixinMethod) {
        Method handler = accessible(mixinMethod.getHandlerMethod());
        int paramCount = handler.getParameterCount();
        boolean returnable = CallbackInfoReturnable.class.isAssignableFrom(handler.getParameterTypes()[paramCount - 1]);
        return ctx -> {
            Object[] handlerArgs = mapArguments(ctx.getArgs(), paramCount);
            CallbackInfo ci = returnable ? new CallbackInfoReturnable<>(ctx.getReturnValue()) : new CallbackInfo();
            handlerArgs[paramCount - 1] = ci;
            invoke(handler, ctx.getInstance(), handlerArgs);
            if (ci instanceof CallbackInfoReturnable<?> cir) ctx.setReturnValue(cir.getReturnValue());
            if (ci.isCancelled()) ctx.cancel();
        };
    }

    private HookListener overwriteListener(MixinMethod mixinMethod) {
        Method handler = accessible(mixinMethod.getHandlerMethod());
        return ctx -> ctx.setReturnValue(invoke(handler, ctx.getInstance(), ctx.getArgs().clone()));
    }

    private HookListener modifyArgListener(MixinMethod mixinMethod) {
        Method handler = accessible(mixinMethod.getHandlerMethod());
        int argIndex = mixinMethod.getArgIndex();
        return ctx -> ctx.setArg(argIndex, invoke(handler, ctx.getInstance(), new Object[]{ctx.getArgs()[argIndex]}));
    }

    // ---------------------------------------------------------------- instruction points

    /**
     * Operand layouts (the outer method's {@code this} is appended when it is an instance method):
     * <ul>
     *   <li>INVOKE inject: {@code [outer args..., this?]} → {@code null}, or {@code Object[]{ret}} when cancelled</li>
     *   <li>Redirect: {@code [receiver?, call args..., this?]} → call result</li>
     *   <li>ModifyVariable / ModifyReturnValue: {@code [value, this?]} → new value</li>
     * </ul>
     *
     * @param outerStatic whether the hooked (outer) method is static
     */
    public PointHandler toPointHandler(MixinMethod mixinMethod, boolean outerStatic) {
        Method handler = accessible(mixinMethod.getHandlerMethod());
        int selfSlots = outerStatic ? 0 : 1;
        return switch (mixinMethod.getType()) {
            case INJECT_INVOKE -> {
                int paramCount = handler.getParameterCount();
                boolean returnable = CallbackInfoReturnable.class.isAssignableFrom(
                        handler.getParameterTypes()[paramCount - 1]);
                boolean cancellable = mixinMethod.isCancellable();
                yield ops -> {
                    Object self = outerStatic ? null : ops[ops.length - 1];
                    Object[] outerArgs = Arrays.copyOf(ops, ops.length - selfSlots);
                    Object[] handlerArgs = mapArguments(outerArgs, paramCount);
                    CallbackInfo ci = returnable ? new CallbackInfoReturnable<>(null) : new CallbackInfo();
                    handlerArgs[paramCount - 1] = ci;
                    invoke(handler, self, handlerArgs);
                    if (cancellable && ci.isCancelled()) {
                        Object ret = ci instanceof CallbackInfoReturnable<?> cir ? cir.getReturnValue() : null;
                        return new Object[]{ret};
                    }
                    return null;
                };
            }
            case REDIRECT -> ops -> {
                Object self = outerStatic ? null : ops[ops.length - 1];
                return invoke(handler, self, Arrays.copyOf(ops, ops.length - selfSlots));
            };
            case MODIFY_VARIABLE, MODIFY_RETURN_VALUE -> ops -> {
                Object self = outerStatic ? null : ops[ops.length - 1];
                return invoke(handler, self, new Object[]{ops[0]});
            };
            default -> throw new IllegalArgumentException(mixinMethod.getType() + " is not a point, use toListener");
        };
    }

    // ---------------------------------------------------------------- helpers

    private static Object[] mapArguments(Object[] targetArgs, int handlerParamCount) {
        Object[] handlerArgs = new Object[handlerParamCount];
        System.arraycopy(targetArgs, 0, handlerArgs, 0, Math.min(handlerParamCount - 1, targetArgs.length));
        return handlerArgs;
    }

    private static Method accessible(Method m) {
        m.setAccessible(true);
        return m;
    }

    /** Invokes a handler with @Shadow binding; rethrows the handler's own exception unwrapped. */
    private Object invoke(Method handler, Object target, Object[] args) {
        Object instance = Modifier.isStatic(handler.getModifiers()) ? null : mixinInstances.get();
        Object previousTarget = currentTarget.get();
        currentTarget.set(target);
        bindShadows(instance, target);
        try {
            return handler.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw sneaky(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } finally {
            writeBackShadows(instance, target);
            if (previousTarget == null) currentTarget.remove();
            else currentTarget.set(previousTarget);
        }
    }

    private void bindShadows(Object instance, Object target) {
        if (instance == null || target == null || shadowBindings.isEmpty()) return;
        for (var binding : shadowBindings) {
            try {
                binding.mixinField().set(instance, binding.targetField().get(target));
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "@Shadow read failed: " + binding.mixinField().getName(), e);
            }
        }
    }

    private void writeBackShadows(Object instance, Object target) {
        if (instance == null || target == null || shadowBindings.isEmpty()) return;
        for (var binding : shadowBindings) {
            if (Modifier.isFinal(binding.targetField().getModifiers())) continue;
            try {
                binding.targetField().set(target, binding.mixinField().get(instance));
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "@Shadow write-back failed: " + binding.mixinField().getName(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
