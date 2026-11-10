package dev.seraphicness.nmshook.api;

/**
 * Single entry point (Facade) for the NMSHook library.
 * Plugin developers register hooks/Mixins using only this class.
 *
 * <p>Hooks are removed automatically when the plugin that registered them is disabled
 * (matched by owner, or by the class loader of the listener / Mixin class).
 */
public final class NMSHookLib {

    private static volatile NMSHookEngine engine;

    private NMSHookLib() {
    }

    /** Called by the Plugin module. Injects the engine implementation ({@code null} on shutdown). */
    public static void bootstrap(NMSHookEngine engine) {
        NMSHookLib.engine = engine;
    }

    public static boolean isAvailable() {
        return engine != null;
    }

    /** Starts a lambda hook builder. */
    public static HookBuilder hook() {
        return new HookBuilder();
    }

    /** Registers an @NMSMixin class. */
    public static HookHandle registerMixin(Class<?> mixinClass) {
        return registerMixin(mixinClass, null);
    }

    /** Registers an @NMSMixin class, associated with the specified owner. */
    public static HookHandle registerMixin(Class<?> mixinClass, Object owner) {
        return engine().registerMixin(mixinClass, owner);
    }

    /** Unregisters all hooks/Mixins for a specific owner. */
    public static void unregisterAll(Object owner) {
        engine().unregisterAll(owner);
    }

    static NMSHookEngine engine() {
        NMSHookEngine e = engine;
        if (e == null) {
            throw new IllegalStateException(
                    "NMSHook is not yet initialized. Use after NMSHookPlugin is enabled.");
        }
        return e;
    }
}
