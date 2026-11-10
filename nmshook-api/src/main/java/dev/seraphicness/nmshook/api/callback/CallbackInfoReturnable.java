package dev.seraphicness.nmshook.api.callback;

/**
 * Callback information for methods with a return value.
 * The original return value can be overridden via {@link #setReturnValue(Object)}.
 *
 * @param <T> the return type of the original method
 */
public class CallbackInfoReturnable<T> extends CallbackInfo {

    private T returnValue;

    public CallbackInfoReturnable(T originalReturnValue) {
        this.returnValue = originalReturnValue;
    }

    public T getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(T value) {
        this.returnValue = value;
    }
}
