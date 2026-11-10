package dev.seraphicness.nmshook.transform;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Advanced Transformer that uses ASM MethodVisitor directly.
 * To be implemented in Phase 3. Covers {@code @Inject(at = @At("INVOKE"))}, {@code @Redirect}, {@code @ModifyVariable}, etc.
 */
public final class ASMTransformer {

    private static final Logger LOGGER = Logger.getLogger(ASMTransformer.class.getName());

    public void applyInvokeInject(Class<?> targetClass, String methodName,
                                  String invokeTarget, int ordinal,
                                  Instrumentation instrumentation) {
        LOGGER.info(() -> "Applying ASM INVOKE Inject: %s#%s -> %s".formatted(
                targetClass.getSimpleName(), methodName, invokeTarget));

        // TODO Phase 3: Integrate ASM MethodVisitor on top of ByteBuddy
    }

    public void applyRedirect(Class<?> targetClass, String methodName,
                              String redirectTarget,
                              Instrumentation instrumentation) {
        LOGGER.info(() -> "Applying ASM Redirect: %s#%s -> %s".formatted(
                targetClass.getSimpleName(), methodName, redirectTarget));

        // TODO Phase 3: Redirect specific method calls within a method
    }

    public void applyModifyVariable(Class<?> targetClass, String methodName,
                                    int localVarIndex,
                                    Instrumentation instrumentation) {
        LOGGER.info(() -> "Applying ASM ModifyVariable: %s#%s var[%d]".formatted(
                targetClass.getSimpleName(), methodName, localVarIndex));

        // TODO Phase 3: Modify local variables
    }
}
