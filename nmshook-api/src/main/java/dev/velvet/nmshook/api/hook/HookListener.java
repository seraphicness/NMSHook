package dev.velvet.nmshook.api.hook;

/**
 * Lambda hook handler. Invoked when a hook is triggered.
 */
@FunctionalInterface
public interface HookListener {

    void onHook(HookContext ctx);
}
