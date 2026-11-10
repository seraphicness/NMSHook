package dev.seraphicness.nmshook.resolver;

import java.util.logging.Logger;

/**
 * Detects the Minecraft version of the currently running Paper server.
 */
public final class VersionDetector {

    private static final Logger LOGGER = Logger.getLogger(VersionDetector.class.getName());

    /** Supported Minecraft versions (inclusive): 26.1 – 26.3. */
    public static final String SUPPORTED_RANGE = "26.1 – 26.3";
    private static final int SUPPORTED_YEAR = 26;
    private static final int MIN_DROP = 1;
    private static final int MAX_DROP = 3;

    private static final String DEFAULT_VERSION = "26.1";

    /** Detects the Paper server version at runtime. */
    public String detect() {
        // Paper API: Bukkit.getMinecraftVersion()
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            var method = bukkit.getMethod("getMinecraftVersion");
            String version = (String) method.invoke(null);
            LOGGER.info("Detected Minecraft version: " + version);
            return version;
        } catch (ReflectiveOperationException e) {
            LOGGER.warning("Version detection failed, using default: " + e.getMessage());
            return DEFAULT_VERSION;
        }
    }

    /**
     * Returns whether the given version (e.g. "26.1", "26.1.2", "26.3") is within {@link #SUPPORTED_RANGE}.
     * Patch / hotfix numbers are ignored.
     */
    public boolean isSupported(String version) {
        if (version == null) return false;
        String[] parts = version.trim().split("[.\\-]");
        if (parts.length < 2) return false;
        try {
            int year = Integer.parseInt(parts[0]);
            int drop = Integer.parseInt(parts[1]);
            return year == SUPPORTED_YEAR && drop >= MIN_DROP && drop <= MAX_DROP;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
