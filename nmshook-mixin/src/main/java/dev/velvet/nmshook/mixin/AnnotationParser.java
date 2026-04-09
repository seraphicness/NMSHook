package dev.velvet.nmshook.mixin;

import dev.velvet.nmshook.api.annotation.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain Service that parses @NMSMixin classes via reflection to create {@link MixinDefinition}s.
 */
public final class AnnotationParser {

    /** Parses a Mixin class and returns a MixinDefinition. */
    public MixinDefinition parse(Class<?> mixinClass) {
        return parse(mixinClass, null);
    }

    /** Parses a Mixin class with the specified owner and returns a MixinDefinition. */
    public MixinDefinition parse(Class<?> mixinClass, Object owner) {
        NMSMixin nmsMixin = mixinClass.getAnnotation(NMSMixin.class);
        if (nmsMixin == null) {
            throw new IllegalArgumentException(
                    mixinClass.getName() + " is missing the @NMSMixin annotation.");
        }

        Class<?> target = nmsMixin.target();
        int priority = nmsMixin.priority();
        List<MixinMethod> methods = parseMethods(mixinClass);

        return new MixinDefinition(owner, mixinClass, target, priority, methods);
    }

    private List<MixinMethod> parseMethods(Class<?> mixinClass) {
        List<MixinMethod> result = new ArrayList<>();

        for (Method method : mixinClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Inject.class)) {
                result.add(parseInject(method));
            } else if (method.isAnnotationPresent(Overwrite.class)) {
                result.add(parseOverwrite(method));
            } else if (method.isAnnotationPresent(ModifyArg.class)) {
                result.add(parseModifyArg(method));
            }
        }

        return result;
    }

    private MixinMethod parseInject(Method method) {
        Inject inject = method.getAnnotation(Inject.class);
        At at = inject.at();

        MixinMethod.Type type = switch (at.value()) {
            case At.HEAD -> MixinMethod.Type.INJECT_HEAD;
            case At.TAIL -> MixinMethod.Type.INJECT_TAIL;
            case At.INVOKE -> MixinMethod.Type.INJECT_INVOKE;
            case At.RETURN -> MixinMethod.Type.INJECT_TAIL; // RETURN treated as TAIL
            default -> throw new IllegalArgumentException("Unsupported @At value: " + at.value());
        };

        return new MixinMethod(method, type, inject.method(), inject.cancellable(), -1);
    }

    private MixinMethod parseOverwrite(Method method) {
        return new MixinMethod(method, MixinMethod.Type.OVERWRITE, method.getName(), false, -1);
    }

    private MixinMethod parseModifyArg(Method method) {
        ModifyArg modifyArg = method.getAnnotation(ModifyArg.class);
        return new MixinMethod(method, MixinMethod.Type.MODIFY_ARG,
                modifyArg.method(), false, modifyArg.index());
    }
}
