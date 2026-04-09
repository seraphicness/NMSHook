package dev.velvet.nmshook.api.transform;

import dev.velvet.nmshook.api.hook.HookListener;
import dev.velvet.nmshook.api.hook.HookPoint;

/**
 * Sealed interface representing a bytecode transformation request.
 * Created by the Hook/Mixin domain and consumed by the Transform domain.
 * A contract that enables communication between the two domains via the API module
 * without direct dependencies.
 */
public sealed interface TransformRequest {

    Class<?> targetClass();

    String methodName();

    /** Advice-based HEAD/TAIL injection request. For @Inject or BEFORE/AFTER lambda hooks. */
    record AdviceRequest(
            Class<?> targetClass,
            String methodName,
            String atValue,
            boolean cancellable,
            HookListener listener
    ) implements TransformRequest {}

    /** MethodDelegation-based method replacement request. For @Overwrite or REPLACE lambda hooks. */
    record DelegationRequest(
            Class<?> targetClass,
            String methodName,
            Object delegate
    ) implements TransformRequest {}

    /** Argument modification request. For @ModifyArg. */
    record ModifyArgRequest(
            Class<?> targetClass,
            String methodName,
            int argIndex,
            HookListener listener
    ) implements TransformRequest {}
}
