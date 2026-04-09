package dev.velvet.nmshook.api.hook;

/**
 * DTO for a lambda hook registration request.
 * Created by {@link dev.velvet.nmshook.api.HookBuilder} and
 * consumed by {@link dev.velvet.nmshook.api.NMSHookEngine}.
 */
public record HookRequest(
        Object owner,
        Class<?> target,
        String method,
        HookPoint at,
        boolean cancellable,
        int priority,
        HookListener listener
) {
}
