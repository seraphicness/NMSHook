package dev.seraphicness.nmshook.plugin;

import dev.seraphicness.nmshook.agent.AttachStrategy;
import dev.seraphicness.nmshook.agent.NMSAgent;
import dev.seraphicness.nmshook.api.NMSHookDebug;
import dev.seraphicness.nmshook.api.NMSHookLib;
import dev.seraphicness.nmshook.core.DefaultNMSHookEngine;
import dev.seraphicness.nmshook.resolver.NMSClassResolver;
import dev.seraphicness.nmshook.resolver.VersionDetector;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;

/**
 * NMSHook Paper plugin entry point: acquires Instrumentation, starts the engine, publishes it through
 * {@link NMSHookLib}, and wires the Bukkit side (plugin-disable cleanup, {@code /nmshook}, config).
 */
public final class NMSHookPlugin extends JavaPlugin {

    private DefaultNMSHookEngine engine;

    @Override
    public void onEnable() {
        // Lets ByteBuddy read class files of JVMs newer than it officially supports.
        System.setProperty("net.bytebuddy.experimental", "true");
        saveDefaultConfig();

        // 1. Instrumentation (-javaagent if present, otherwise dynamic attach once per JVM)
        NMSAgent agent = new NMSAgent(new AttachStrategy.DynamicAttach());

        // 2. Version check (warns outside 26.1 – 26.3)
        new NMSClassResolver(new VersionDetector()).loadMappings();

        // 3. Engine
        engine = new DefaultNMSHookEngine(agent.getInstrumentation(), getLogger());
        applyConfig();

        // 4. Bukkit wiring
        getServer().getPluginManager().registerEvents(new PluginLifecycleListener(engine, this), this);
        NMSHookCommand command = new NMSHookCommand(this, engine);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("nmshook", "NMSHook hook diagnostics", List.of(), command));

        // 5. Publish — other plugins can now use NMSHookLib
        NMSHookLib.bootstrap(engine);
        getLogger().info("NMSHook v" + getPluginMeta().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        NMSHookLib.bootstrap(null);
        if (engine != null) {
            // removes every hook, restores all instrumented classes and disconnects the dispatcher
            engine.shutdown();
            engine = null;
        }
        getLogger().info("NMSHook disabled");
    }

    /** (Re)applies config.yml. */
    void applyConfig() {
        reloadConfig();
        var config = getConfig();
        engine.executor().setFailureThreshold(config.getInt("failure-threshold", 10));
        NMSHookDebug.setProfiling(config.getBoolean("profiling", false));
        NMSHookDebug.setSlowThreshold((long) (config.getDouble("slow-threshold-ms", 1.0) * 1_000_000));
        if (config.getBoolean("debug", false) || Boolean.getBoolean("nmshook.debug")) {
            enableDebug();
        } else {
            NMSHookDebug.disable();
        }
    }

    Path enableDebug() {
        Path dumpDir = getDataFolder().toPath().resolve("debug");
        dumpDir.toFile().mkdirs();
        NMSHookDebug.enable(dumpDir);
        getLogger().info("[Debug] Debug mode enabled. Bytecode dump dir: " + dumpDir);
        return dumpDir;
    }
}
