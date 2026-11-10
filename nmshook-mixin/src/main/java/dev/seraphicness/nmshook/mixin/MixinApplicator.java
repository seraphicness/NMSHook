package dev.seraphicness.nmshook.mixin;

import dev.seraphicness.nmshook.api.transform.TransformRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Domain Service that converts a {@link MixinDefinition} into a list of {@link TransformRequest}s.
 * Acts as a translation layer between the Mixin domain and the Transform domain.
 */
public final class MixinApplicator {

    private static final Logger LOGGER = Logger.getLogger(MixinApplicator.class.getName());

    /** Analyzes a MixinDefinition and generates the required list of TransformRequests. */
    public List<TransformRequest> toTransformRequests(MixinDefinition definition) {
        List<TransformRequest> requests = new ArrayList<>();
        Class<?> target = definition.getTargetClass();

        for (MixinMethod method : definition.getMethods()) {
            TransformRequest request = switch (method.getType()) {
                case INJECT_HEAD -> new TransformRequest.AdviceRequest(
                        target, method.getTargetMethodName(), "HEAD",
                        method.isCancellable(), null
                );
                case INJECT_TAIL -> new TransformRequest.AdviceRequest(
                        target, method.getTargetMethodName(), "TAIL",
                        method.isCancellable(), null
                );
                case OVERWRITE -> new TransformRequest.DelegationRequest(
                        target, method.getTargetMethodName(), method.getHandlerMethod()
                );
                case MODIFY_ARG -> new TransformRequest.ModifyArgRequest(
                        target, method.getTargetMethodName(),
                        method.getArgIndex(), null
                );
                case INJECT_INVOKE -> {
                    LOGGER.warning("INJECT_INVOKE is planned for Phase 3: " +
                            method.getHandlerMethod().getName());
                    yield null;
                }
                case SHADOW -> null;
            };

            if (request != null) {
                requests.add(request);
            }
        }

        return requests;
    }
}
