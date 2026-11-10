package dev.seraphicness.nmshook.hook;

/**
 * Handler for an instruction-level point (INVOKE inject / Redirect / ModifyVariable / ModifyReturnValue).
 * Receives the operands produced by the rewritten instruction.
 */
@FunctionalInterface
public interface PointHandler {

    /**
     * @param operands point-specific operands (see the engine for the layout of each kind)
     * @return the point result; the executor's {@code pass} sentinel means "keep default behaviour"
     */
    Object handle(Object[] operands) throws Throwable;
}
