package dev.seraphicness.nmshook.plugin;

import dev.seraphicness.nmshook.core.DefaultNMSHookEngine;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;

/**
 * Removes every hook a plugin registered when that plugin is disabled — including hooks registered
 * without {@code owner(...)} — and restores the original bytecode of methods left without hooks.
 * Without this, a disabled plugin's listeners would keep running and pin its ClassLoader in memory.
 */
final class PluginLifecycleListener implements Listener {

    private final DefaultNMSHookEngine engine;
    private final Plugin self;

    PluginLifecycleListener(DefaultNMSHookEngine engine, Plugin self) {
        this.engine = engine;
        this.self = self;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        Plugin plugin = event.getPlugin();
        if (plugin == self) return;
        int removed = engine.unregisterByClassLoader(plugin.getClass().getClassLoader());
        if (removed > 0) {
            self.getLogger().info("Removed " + removed + " hook(s) of disabled plugin " + plugin.getName());
        }
    }
}
