package dev.seraphicness.nmshook.resolver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Aggregate Root of the Resolver domain that resolves NMS classes and methods
 * based on Mojang mappings. Caches resolution results to reduce repeated lookup costs.
 */
public final class NMSClassResolver {

    private static final Logger LOGGER = Logger.getLogger(NMSClassResolver.class.getName());

    private final VersionDetector versionDetector;
    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();
    private final Map<String, String> methodCache = new ConcurrentHashMap<>();
    private MappingTable mappingTable;

    public NMSClassResolver(VersionDetector versionDetector) {
        this.versionDetector = versionDetector;
    }

    /** Loads the mapping table. Called once at server startup. */
    public void loadMappings() {
        String version = versionDetector.detect();
        // Paper 1.20.5+ uses Mojang mappings at runtime, so no remapping is needed.
        // For older versions or Spigot servers, version-specific mapping files would be loaded here.
        this.mappingTable = new MappingTable(version, Map.of(), Map.of());
        LOGGER.info("Mapping table loaded: " + version);
    }

    /** Resolves a Mojang class name to a runtime Class<?>. */
    public Class<?> resolveClass(String mojangClassName) {
        return classCache.computeIfAbsent(mojangClassName, name -> {
            String runtimeName = mappingTable != null
                    ? mappingTable.resolveClass(name)
                    : name;
            try {
                return Class.forName(runtimeName);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("NMS class not found: " + runtimeName, e);
            }
        });
    }

    /** Resolves a Mojang method name to a runtime method name. */
    public String resolveMethodName(Class<?> targetClass, String mojangMethodName) {
        String key = targetClass.getName() + "#" + mojangMethodName;
        return methodCache.computeIfAbsent(key, k -> {
            if (mappingTable == null) return mojangMethodName;
            return mappingTable.resolveMethod(targetClass.getName(), mojangMethodName);
        });
    }
}
