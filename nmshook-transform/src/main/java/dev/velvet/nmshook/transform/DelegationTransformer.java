package dev.velvet.nmshook.transform;

import dev.velvet.nmshook.api.transform.TransformRequest.DelegationRequest;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Transformer that completely replaces a method using ByteBuddy MethodDelegation.
 * Used for {@code @Overwrite} and {@code REPLACE} lambda hooks.
 */
public final class DelegationTransformer {

    private static final Logger LOGGER = Logger.getLogger(DelegationTransformer.class.getName());

    public void apply(DelegationRequest request, Instrumentation instrumentation) {
        LOGGER.info(() -> "Applying Delegation: %s#%s".formatted(
                request.targetClass().getSimpleName(),
                request.methodName()));

        // TODO Phase 1: Implement MethodDelegation
        // new AgentBuilder.Default()
        //     .type(ElementMatchers.is(request.targetClass()))
        //     .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
        //         builder.method(ElementMatchers.named(request.methodName()))
        //             .intercept(MethodDelegation.to(request.delegate()))
        //     )
        //     .installOn(instrumentation);
    }
}
