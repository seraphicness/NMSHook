package dev.seraphicness.nmshook.api.hook;

/**
 * Injection point for lambda hooks.
 */
public enum HookPoint {

    /** Just before method entry. */
    BEFORE,

    /** Immediately after method return. */
    AFTER,

    /** Completely replaces the original method. */
    REPLACE
}
