package dev.seraphicness.nmshook.api;

import dev.seraphicness.nmshook.api.hook.HookRequest;

/**
 * Internal engine port for NMSHook.
 * Implemented in the core module and injected into {@link NMSHookLib}.
 */
public interface NMSHookEngine {

    /** Registers a lambda hook. */
    HookHandle registerHook(HookRequest request);

    /** Registers a Mixin class annotated with @NMSMixin, associated with the specified owner (may be null). */
    HookHandle registerMixin(Class<?> mixinClass, Object owner);

    /** Unregisters all hooks/Mixins registered by a specific owner (plugin). */
    void unregisterAll(Object owner);
}
