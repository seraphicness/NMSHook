package dev.velvet.nmshook.transform;

import dev.velvet.nmshook.api.transform.TransformRequest;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Strategy selector that receives a {@link TransformRequest} and delegates
 * to the appropriate Transformer. Serves as the entry point for the Transform domain.
 *
 * <p>In Phase 1, all hook types (BEFORE/AFTER/REPLACE/ModifyArg) are handled
 * by the same Advice mechanism. Since HookDispatcher queries the HookRegistry
 * at runtime to execute the appropriate hooks, bytecode transformation is only
 * needed once per method.
 */
public final class TransformEngine {

    private static final Logger LOGGER = Logger.getLogger(TransformEngine.class.getName());

    private final AdviceTransformer adviceTransformer;
    private final DelegationTransformer delegationTransformer;
    private final ASMTransformer asmTransformer;

    public TransformEngine() {
        this.adviceTransformer = new AdviceTransformer();
        this.delegationTransformer = new DelegationTransformer();
        this.asmTransformer = new ASMTransformer();
    }

    /** Injects HookDispatcher into the bootstrap CL. Called once at server start. */
    public void initialize(Instrumentation instrumentation) {
        adviceTransformer.ensureBootstrapInjected(instrumentation);
    }

    /** Installs Advice on the target method according to the TransformRequest. */
    public void apply(TransformRequest request, Instrumentation instrumentation) {
        switch (request) {
            case TransformRequest.AdviceRequest advice ->
                    adviceTransformer.apply(advice.targetClass(), advice.methodName(), instrumentation);

            case TransformRequest.DelegationRequest delegation ->
                    // REPLACE hooks are also handled via the Advice mechanism.
                    // When HookDispatcher.dispatchBefore() detects a REPLACE hook,
                    // it skips the original and applies the replacement return value.
                    adviceTransformer.apply(delegation.targetClass(), delegation.methodName(), instrumentation);

            case TransformRequest.ModifyArgRequest modifyArg ->
                    // ModifyArg is also handled via the Advice mechanism.
                    // When a BEFORE hook modifies the args array,
                    // @AllArguments(readOnly=false) applies the changes to the actual parameters.
                    adviceTransformer.apply(modifyArg.targetClass(), modifyArg.methodName(), instrumentation);
        }
    }

    /** Removes all installed transformations, restoring original bytecode. */
    public void removeAll(Instrumentation instrumentation) {
        adviceTransformer.removeAll(instrumentation);
    }
}
