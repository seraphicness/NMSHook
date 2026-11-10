package dev.seraphicness.nmshook.resolver;

import java.util.Map;

/**
 * Value Object holding Mojang-to-runtime mapping data for a specific version.
 *
 * @param version        Minecraft version (e.g. "1.21.4")
 * @param classMappings  Mojang class name -> runtime class name
 * @param methodMappings Mojang "class#method" -> runtime method name
 */
public record MappingTable(
        String version,
        Map<String, String> classMappings,
        Map<String, String> methodMappings
) {
    public MappingTable {
        classMappings = Map.copyOf(classMappings);
        methodMappings = Map.copyOf(methodMappings);
    }

    public String resolveClass(String mojangClassName) {
        return classMappings.getOrDefault(mojangClassName, mojangClassName);
    }

    public String resolveMethod(String mojangClassName, String mojangMethodName) {
        String key = mojangClassName + "#" + mojangMethodName;
        return methodMappings.getOrDefault(key, mojangMethodName);
    }
}
