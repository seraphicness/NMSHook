package dev.seraphicness.nmshook.api;

/**
 * Single entry point (Facade) for the NMSHook library.
 * Plugin developers register hooks/Mixins using only this class.
 *
 * <p>The internal engine is injected by the Plugin module at bootstrap time
 * via {@link #bootstrap(NMSHookEngine)}.
 */
public final class NMSHookLib {

    private static volatile NMSHookEngine engine;

    private NMSHookLib() {
    }

    /** Called by the Plugin module. Injects the engine implementation. */
    public static void bootstrap(NMSHookEngine engine) {
        NMSHookLib.engine = engine;
    }

    /** Starts a lambda hook builder. */
    public static HookBuilder hook() {
        return new HookBuilder();
    }

    /** Registers an @NMSMixin class. */
    public static void registerMixin(Class<?> mixinClass) {
        registerMixin(mixinClass, null);
    }

    /** Registers an @NMSMixin class, associated with the specified owner. */
    public static void registerMixin(Class<?> mixinClass, Object owner) {
        engine().registerMixin(mixinClass, owner);
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
