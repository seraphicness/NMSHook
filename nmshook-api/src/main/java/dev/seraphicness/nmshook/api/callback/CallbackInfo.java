package dev.seraphicness.nmshook.api.callback;

/**
 * Callback information passed to {@code @Inject} handlers.
 * For void methods. The original execution can be cancelled via cancel().
 */
public class CallbackInfo {

    private boolean cancelled;

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
