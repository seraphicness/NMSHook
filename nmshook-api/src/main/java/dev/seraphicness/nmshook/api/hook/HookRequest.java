package dev.seraphicness.nmshook.api.hook;

import dev.seraphicness.nmshook.api.target.MethodSelector;

/**
 * DTO for a lambda hook registration request.
 * Created by {@link dev.seraphicness.nmshook.api.HookBuilder} and
 * consumed by {@link dev.seraphicness.nmshook.api.NMSHookEngine}.
 */
public record HookRequest(
        Object owner,
        Class<?> target,
        MethodSelector method,
        HookPoint at,
        boolean cancellable,
        int priority,
        HookListener listener
) {
}
