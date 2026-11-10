package dev.seraphicness.nmshook.mixin;

import dev.seraphicness.nmshook.api.annotation.At;
import dev.seraphicness.nmshook.api.annotation.Inject;
import dev.seraphicness.nmshook.api.annotation.ModifyArg;
import dev.seraphicness.nmshook.api.annotation.ModifyReturnValue;
import dev.seraphicness.nmshook.api.annotation.ModifyVariable;
import dev.seraphicness.nmshook.api.annotation.NMSMixin;
import dev.seraphicness.nmshook.api.annotation.Overwrite;
import dev.seraphicness.nmshook.api.annotation.Redirect;
import dev.seraphicness.nmshook.api.callback.CallbackInfo;
import dev.seraphicness.nmshook.api.target.HookTargetException;
import dev.seraphicness.nmshook.api.target.MethodSelector;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses @NMSMixin classes into {@link MixinDefinition}s and checks handler signatures.
 * Target existence is checked later by the engine (it needs the target class and bytecode).
 */
public final class AnnotationParser {

    private static final List<Class<? extends Annotation>> HANDLER_ANNOTATIONS = List.of(
            Inject.class, Overwrite.class, ModifyArg.class, Redirect.class, ModifyVariable.class,
            ModifyReturnValue.class);

    public MixinDefinition parse(Class<?> mixinClass) {
        return parse(mixinClass, null);
    }

    public MixinDefinition parse(Class<?> mixinClass, Object owner) {
        NMSMixin nmsMixin = mixinClass.getAnnotation(NMSMixin.class);
        if (nmsMixin == null) {
            throw new IllegalArgumentException(mixinClass.getName() + " is missing the @NMSMixin annotation.");
        }
        List<MixinMethod> methods = new ArrayList<>();
        for (Method method : mixinClass.getDeclaredMethods()) {
            MixinMethod parsed = parseMethod(method);
            if (parsed != null) methods.add(parsed);
        }
        if (methods.isEmpty()) {
            throw new IllegalArgumentException(mixinClass.getName() + " declares no handler methods "
                    + "(@Inject, @Overwrite, @ModifyArg, @Redirect, @ModifyVariable, @ModifyReturnValue).");
        }
        return new MixinDefinition(owner, mixinClass, nmsMixin.target(), nmsMixin.priority(), methods);
    }

    private MixinMethod parseMethod(Method method) {
        List<Annotation> found = HANDLER_ANNOTATIONS.stream()
                .<Annotation>map(method::getAnnotation)
                .filter(a -> a != null)
                .toList();
        if (found.isEmpty()) return null;
        if (found.size() > 1) {
            throw fail(method, "has more than one handler annotation: " + found.stream()
                    .map(a -> "@" + a.annotationType().getSimpleName()).toList());
        }
        Annotation a = found.getFirst();
        if (a instanceof Inject inject) return parseInject(method, inject);
        if (a instanceof Overwrite overwrite) return parseOverwrite(method, overwrite);
        if (a instanceof ModifyArg modifyArg) return parseModifyArg(method, modifyArg);
        if (a instanceof Redirect redirect) return parseRedirect(method, redirect);
        if (a instanceof ModifyVariable modifyVariable) return parseModifyVariable(method, modifyVariable);
        return parseModifyReturnValue(method, (ModifyReturnValue) a);
    }

    private MixinMethod parseInject(Method method, Inject inject) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0 || !CallbackInfo.class.isAssignableFrom(params[params.length - 1])) {
            throw fail(method, "@Inject handlers must take CallbackInfo (or CallbackInfoReturnable) as the last parameter");
        }
        MethodSelector selector = MethodSelector.parse(inject.method());
        if (!selector.hasDescriptor() && params.length > 1) {
            // handler declares the target's parameters: use them to pick the overload
            selector = MethodSelector.of(selector.name(), Arrays.copyOf(params, params.length - 1));
        }
        At at = inject.at();
        return switch (at.value()) {
            case At.HEAD -> MixinMethod.method(method, MixinMethod.Type.INJECT_HEAD, selector, inject.cancellable(), -1);
            case At.TAIL, At.RETURN -> MixinMethod.method(method, MixinMethod.Type.INJECT_TAIL, selector, false, -1);
            case At.INVOKE -> {
                requireTarget(method, at);
                yield MixinMethod.invoke(method, MixinMethod.Type.INJECT_INVOKE, selector, inject.cancellable(),
                        at.target(), at.shift() == At.Shift.AFTER, at.ordinal());
            }
            default -> throw fail(method, "@Inject does not support @At(\"" + at.value()
                    + "\"); use HEAD, TAIL, RETURN or INVOKE");
        };
    }

    private MixinMethod parseOverwrite(Method method, Overwrite overwrite) {
        String name = overwrite.method().isEmpty() ? method.getName() : overwrite.method();
        return MixinMethod.method(method, MixinMethod.Type.OVERWRITE,
                new MethodSelector(name, MethodSelector.descriptorOf(method)), false, -1);
    }

    private MixinMethod parseModifyArg(Method method, ModifyArg modifyArg) {
        requireUnary(method, "@ModifyArg");
        if (modifyArg.index() < 0) throw fail(method, "@ModifyArg index must be >= 0");
        return MixinMethod.method(method, MixinMethod.Type.MODIFY_ARG, MethodSelector.parse(modifyArg.method()),
                false, modifyArg.index());
    }

    private MixinMethod parseRedirect(Method method, Redirect redirect) {
        At at = redirect.at();
        if (!At.INVOKE.equals(at.value())) throw fail(method, "@Redirect requires @At(value = \"INVOKE\", target = ...)");
        requireTarget(method, at);
        return MixinMethod.invoke(method, MixinMethod.Type.REDIRECT, MethodSelector.parse(redirect.method()),
                false, at.target(), false, at.ordinal());
    }

    private MixinMethod parseModifyVariable(Method method, ModifyVariable modifyVariable) {
        requireUnary(method, "@ModifyVariable");
        At at = modifyVariable.at();
        boolean load = switch (at.value()) {
            case At.LOAD -> true;
            case At.STORE -> false;
            default -> throw fail(method, "@ModifyVariable supports @At(\"LOAD\") or @At(\"STORE\")");
        };
        if (modifyVariable.index() < 0) throw fail(method, "@ModifyVariable index must be >= 0");
        return MixinMethod.variable(method, MethodSelector.parse(modifyVariable.method()), modifyVariable.index(),
                load, at.ordinal());
    }

    private MixinMethod parseModifyReturnValue(Method method, ModifyReturnValue modifyReturnValue) {
        requireUnary(method, "@ModifyReturnValue");
        if (!At.RETURN.equals(modifyReturnValue.at().value())) {
            throw fail(method, "@ModifyReturnValue supports only @At(\"RETURN\")");
        }
        return MixinMethod.returnValue(method, MethodSelector.parse(modifyReturnValue.method()),
                modifyReturnValue.at().ordinal());
    }

    private void requireUnary(Method method, String annotation) {
        if (method.getParameterCount() != 1 || method.getReturnType() == void.class
                || method.getReturnType() != method.getParameterTypes()[0]) {
            throw fail(method, annotation + " handlers must look like 'T handler(T value)'");
        }
    }

    private void requireTarget(Method method, At at) {
        if (at.target().isBlank()) {
            throw fail(method, "@At(\"INVOKE\") requires target = \"Lowner/Class;method(desc)ret\"");
        }
    }

    private static HookTargetException fail(Method method, String message) {
        return new HookTargetException(method.getDeclaringClass().getName() + "#" + method.getName() + ": " + message);
    }
}
