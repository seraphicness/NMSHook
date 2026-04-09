package dev.velvet.nmshook.api;

import dev.velvet.nmshook.api.hook.HookRequest;

/**
 * Internal engine port for NMSHook.
 * Implemented in the Plugin module (Composition Root) and injected into {@link NMSHookLib}.
 * Composes the domain modules to complete the hook/Mixin registration flow.
 */
public interface NMSHookEngine {

    /** Registers a lambda hook. */
    void registerHook(HookRequest request);

    /** Registers a Mixin class annotated with @NMSMixin. */
    default void registerMixin(Class<?> mixinClass) {
        registerMixin(mixinClass, null);
    }

    /** Registers a Mixin class annotated with @NMSMixin, associated with the specified owner. */
    void registerMixin(Class<?> mixinClass, Object owner);

    /** Unregisters all hooks/Mixins registered by a specific owner (plugin). */
    void unregisterAll(Object owner);
}
