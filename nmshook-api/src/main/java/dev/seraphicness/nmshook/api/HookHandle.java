package dev.seraphicness.nmshook.api;

/**
 * Handle returned when a hook or Mixin is registered.
 * Keep it to unregister that registration individually.
 */
public interface HookHandle {

    /** Numeric id, as shown by {@code /nmshook list}. For a Mixin, the id of the Mixin registration. */
    int id();

    /** True until {@link #unregister()} is called or the owning plugin is disabled. */
    boolean isRegistered();

    /** Removes the hook(s). The original bytecode is restored once no hooks remain on a method. */
    void unregister();
}
