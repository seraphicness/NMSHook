package dev.velvet.nmshook.plugin;

import dev.velvet.nmshook.agent.AttachStrategy;
import dev.velvet.nmshook.agent.NMSAgent;
import dev.velvet.nmshook.api.NMSHookLib;
import dev.velvet.nmshook.hook.HookExecutor;
import dev.velvet.nmshook.hook.HookRegistry;
import dev.velvet.nmshook.mixin.AnnotationParser;
import dev.velvet.nmshook.mixin.ConflictDetector;
import dev.velvet.nmshook.mixin.MixinRegistry;
import dev.velvet.nmshook.resolver.NMSClassResolver;
import dev.velvet.nmshook.resolver.VersionDetector;
import dev.velvet.nmshook.transform.TransformEngine;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * NMSHook Paper plugin entry point.
 * Assembles all domain modules (Composition Root) and injects the engine into {@link NMSHookLib}.
 */
public final class NMSHookPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // 0. Enable experimental mode so ByteBuddy supports the current JVM version
        System.setProperty("net.bytebuddy.experimental", "true");

        // 1. Agent domain — acquire Instrumentation
        NMSAgent agent = new NMSAgent(new AttachStrategy.DynamicAttach());

        // 2. Resolver domain — load mappings
        var versionDetector = new VersionDetector();
        var resolver = new NMSClassResolver(versionDetector);
        resolver.loadMappings();

        // 3. Hook domain
        var hookRegistry = new HookRegistry();
        var hookExecutor = new HookExecutor(hookRegistry);

        // 4. Mixin domain
        var mixinRegistry = new MixinRegistry();
        var annotationParser = new AnnotationParser();
        var conflictDetector = new ConflictDetector();

        // 5. Transform domain — inject HookDispatcher into bootstrap CL
        var transformEngine = new TransformEngine();
        transformEngine.initialize(agent.getInstrumentation());

        // 6. Assemble the engine
        var engine = new DefaultNMSHookEngine(
                getLogger(),
                hookRegistry,
                hookExecutor,
                mixinRegistry,
                annotationParser,
                conflictDetector,
                transformEngine,
                agent,
                resolver
        );

        // 7. Wire bootstrap HookDispatcher <-> HookExecutor
        engine.wireDispatcher();

        // 8. Inject facade — other plugins can now use NMSHookLib
        NMSHookLib.bootstrap(engine);

        // 9. Debug mode — enable via system property or config
        if (Boolean.getBoolean("nmshook.debug")) {
            var dumpDir = getDataFolder().toPath().resolve("debug");
            dumpDir.toFile().mkdirs();
            dev.velvet.nmshook.api.NMSHookDebug.enable(dumpDir);
            getLogger().info("[Debug] Debug mode enabled. Bytecode dump dir: " + dumpDir);
        }

        getLogger().info("NMSHook v" + getPluginMeta().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        try {
            NMSHookLib.unregisterAll(this);
        } catch (IllegalStateException ignored) {
            // Nothing to unregister if initialization failed
        }
        getLogger().info("NMSHook disabled");
    }
}
