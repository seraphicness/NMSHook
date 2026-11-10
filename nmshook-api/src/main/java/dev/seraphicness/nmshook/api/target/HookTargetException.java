package dev.seraphicness.nmshook.api.target;

/**
 * Thrown at registration time when a hook target cannot be resolved or instrumented
 * (missing method, wrong descriptor, invoke target not found, bytecode rejected, ...).
 * The message explains what was looked for and lists close candidates.
 */
public class HookTargetException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HookTargetException(String message) {
        super(message);
    }

    public HookTargetException(String message, Throwable cause) {
        super(message, cause);
    }
}
