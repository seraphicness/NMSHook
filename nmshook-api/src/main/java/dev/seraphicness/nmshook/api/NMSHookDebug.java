package dev.seraphicness.nmshook.api;

import java.nio.file.Path;

/**
 * Debug / diagnostics control for NMSHook.
 * <ul>
 *   <li>debug: dumps transformed bytecode and logs slow hook phases</li>
 *   <li>profiling: measures per-hook execution time (shown by {@code /nmshook list})</li>
 * </ul>
 */
public final class NMSHookDebug {
    private static volatile boolean enabled = false;
    private static volatile boolean profiling = false;
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

    public static void setProfiling(boolean on) { profiling = on; }
    /** True if hook timing should be recorded (profiling or debug mode). */
    public static boolean isProfiling() { return profiling || enabled; }

    public static void setSlowThreshold(long nanos) { slowThresholdNanos = nanos; }
    public static long getSlowThreshold() { return slowThresholdNanos; }
}
