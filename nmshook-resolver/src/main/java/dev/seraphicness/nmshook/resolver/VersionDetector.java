package dev.seraphicness.nmshook.resolver;

import java.util.logging.Logger;

/**
 * Detects the Minecraft version of the currently running Paper server.
 */
public final class VersionDetector {

    private static final Logger LOGGER = Logger.getLogger(VersionDetector.class.getName());

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
            return "1.21.4";
        }
    }
}
