package dev.velvet.nmshook.api;

import java.nio.file.Path;

/**
 * Debug mode control for NMSHook.
 * When enabled, dumps transformed bytecode and logs hook execution timing.
 */
public final class NMSHookDebug {
    private static volatile boolean enabled = false;
    private static volatile Path dumpDir;
    private static volatile long slowThresholdNanos = 1_000_000; // 1ms

    private NMSHookDebug() {}

    public static void enable(Path dumpDirectory) {
        enabled = true;
        dumpDir = dumpDirectory;
    }

    public static void disable() {
        enabled = false;
    }

    public static boolean isEnabled() { return enabled; }
    public static Path getDumpDir() { return dumpDir; }

    public static void setSlowThreshold(long nanos) { slowThresholdNanos = nanos; }
    public static long getSlowThreshold() { return slowThresholdNanos; }
}
