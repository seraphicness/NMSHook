package dev.seraphicness.nmshook.plugin;

import dev.seraphicness.nmshook.api.NMSHookDebug;
import dev.seraphicness.nmshook.core.DefaultNMSHookEngine;
import dev.seraphicness.nmshook.hook.HookDefinition;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * {@code /nmshook} — inspect and control registered hooks.
 * <pre>
 * /nmshook                       summary
 * /nmshook list [filter]         hooks (filter: plugin, class or method name)
 * /nmshook info &lt;id&gt;             details of one hook
 * /nmshook enable|disable &lt;id&gt;   toggle a hook (enable also resets its failure counter)
 * /nmshook profile on|off|reset  per-hook timing
 * /nmshook debug on|off          bytecode dump + slow-phase logging
 * /nmshook reload                reload config.yml
 * </pre>
 */
final class NMSHookCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS =
            List.of("list", "info", "enable", "disable", "profile", "debug", "reload");

    private final NMSHookPlugin plugin;
    private final DefaultNMSHookEngine engine;

    NMSHookCommand(NMSHookPlugin plugin, DefaultNMSHookEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @Override
    public String permission() {
        return "nmshook.admin";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> summary(sender);
            case "list" -> list(sender, args.length > 1 ? args[1] : null);
            case "info" -> withHook(sender, args, hook -> info(sender, hook));
            case "enable", "disable" -> withHook(sender, args, hook -> {
                boolean enable = sub.equals("enable");
                engine.setEnabled(hook.getId(), enable);
                ok(sender, "Hook #" + hook.getId() + (enable ? " enabled" : " disabled"));
            });
            case "profile" -> profile(sender, args);
            case "debug" -> debug(sender, args);
            case "reload" -> {
                plugin.applyConfig();
                ok(sender, "config.yml reloaded");
            }
            default -> usage(sender);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        String prefix = args[args.length - 1];
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info", "enable", "disable" -> engine.hooks().stream()
                    .map(h -> String.valueOf(h.getId())).filter(s -> s.startsWith(prefix)).toList();
            case "profile" -> Stream.of("on", "off", "reset").filter(s -> s.startsWith(prefix)).toList();
            case "debug" -> Stream.of("on", "off").filter(s -> s.startsWith(prefix)).toList();
            case "list" -> Stream.of(plugin.getServer().getPluginManager().getPlugins()).map(Plugin::getName)
                    .filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
            default -> List.of();
        };
    }

    // ------------------------------------------------------------------ subcommands

    private void summary(CommandSender sender) {
        List<HookDefinition> hooks = engine.hooks();
        long disabled = hooks.stream().filter(h -> !h.isEnabled()).count();
        sender.sendMessage(Component.text("NMSHook " + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));
        line(sender, "Hooks", hooks.size() + (disabled > 0 ? " (" + disabled + " disabled)" : ""));
        line(sender, "Instrumented classes", String.valueOf(engine.instrumentedClassCount()));
        line(sender, "Failure threshold", engine.executor().getFailureThreshold() == 0 ? "off"
                : String.valueOf(engine.executor().getFailureThreshold()));
        line(sender, "Profiling", NMSHookDebug.isProfiling() ? "on" : "off");
        line(sender, "Debug", NMSHookDebug.isEnabled() ? "on" : "off");
        sender.sendMessage(Component.text("/nmshook list | info <id> | enable|disable <id> | profile on|off|reset"
                + " | debug on|off | reload", NamedTextColor.GRAY));
    }

    private void list(CommandSender sender, String filter) {
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        List<HookDefinition> hooks = engine.hooks().stream()
                .filter(h -> f == null || ownerName(h).toLowerCase(Locale.ROOT).contains(f)
                        || h.getTargetClass().getName().toLowerCase(Locale.ROOT).contains(f)
                        || h.getMethodName().toLowerCase(Locale.ROOT).contains(f))
                .toList();
        if (hooks.isEmpty()) {
            sender.sendMessage(Component.text("No hooks" + (filter != null ? " matching '" + filter + "'" : ""),
                    NamedTextColor.GRAY));
            return;
        }
        boolean timing = NMSHookDebug.isProfiling();
        for (HookDefinition h : hooks) {
            Component c = Component.text("#" + h.getId() + " ", h.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)
                    .append(Component.text(h.getKind() + " ", NamedTextColor.YELLOW))
                    .append(Component.text(h.getTargetClass().getSimpleName() + "#" + h.getMethodName(), NamedTextColor.WHITE))
                    .append(Component.text(" [" + ownerName(h) + "] ", NamedTextColor.AQUA))
                    .append(Component.text(stats(h, timing), NamedTextColor.GRAY));
            if (!h.isEnabled()) c = c.append(Component.text(" DISABLED", NamedTextColor.RED));
            sender.sendMessage(c);
        }
        if (!timing) {
            sender.sendMessage(Component.text("Timing is off — /nmshook profile on", NamedTextColor.DARK_GRAY));
        }
    }

    private void info(CommandSender sender, HookDefinition h) {
        sender.sendMessage(Component.text("Hook #" + h.getId(), NamedTextColor.GOLD));
        line(sender, "Kind", h.getKind().toString() + (h.isCancellable() ? " (cancellable)" : ""));
        line(sender, "Target", h.getTargetClass().getName() + "#" + h.getSelector());
        if (h.getDetail() != null) line(sender, "Point", h.getDetail());
        line(sender, "Source", h.getSource());
        line(sender, "Owner", ownerName(h));
        line(sender, "Registration", "#" + h.getGroupId());
        line(sender, "Priority", String.valueOf(h.getPriority().value()));
        line(sender, "State", h.isEnabled() ? "enabled" : "disabled: " + h.getDisabledReason());
        line(sender, "Calls", String.valueOf(h.getCalls()));
        line(sender, "Failures", h.getFailures() + " total, " + h.getConsecutiveFailures() + " in a row");
        if (h.getTotalNanos() > 0 && h.getCalls() > 0) {
            line(sender, "Avg time", "%.3f µs".formatted(h.getTotalNanos() / 1000.0 / h.getCalls()));
        }
    }

    private void profile(CommandSender sender, String[] args) {
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "on" -> {
                NMSHookDebug.setProfiling(true);
                ok(sender, "Profiling on");
            }
            case "off" -> {
                NMSHookDebug.setProfiling(false);
                ok(sender, "Profiling off");
            }
            case "reset" -> {
                engine.resetStats();
                ok(sender, "Statistics reset");
            }
            default -> error(sender, "/nmshook profile on|off|reset");
        }
    }

    private void debug(CommandSender sender, String[] args) {
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "on" -> ok(sender, "Debug on — dumps go to " + plugin.enableDebug()
                    + " (classes instrumented from now on)");
            case "off" -> {
                NMSHookDebug.disable();
                ok(sender, "Debug off");
            }
            default -> error(sender, "/nmshook debug on|off");
        }
    }

    // ------------------------------------------------------------------ helpers

    private interface HookAction {
        void run(HookDefinition hook);
    }

    private void withHook(CommandSender sender, String[] args, HookAction action) {
        if (args.length < 2) {
            error(sender, "/nmshook " + args[0] + " <id>");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[1].replace("#", ""));
        } catch (NumberFormatException e) {
            error(sender, "Not a hook id: " + args[1]);
            return;
        }
        engine.hook(id).ifPresentOrElse(action::run, () -> error(sender, "No hook #" + id));
    }

    private String ownerName(HookDefinition h) {
        if (h.getOwner() instanceof Plugin p) return p.getName();
        for (Plugin p : plugin.getServer().getPluginManager().getPlugins()) {
            if (p.getClass().getClassLoader() == h.getSourceLoader()) return p.getName();
        }
        return h.getOwner() != null ? h.getOwner().toString() : "?";
    }

    private static String stats(HookDefinition h, boolean timing) {
        String s = h.getCalls() + " calls";
        if (timing && h.getCalls() > 0 && h.getTotalNanos() > 0) {
            s += ", avg %.2fµs".formatted(h.getTotalNanos() / 1000.0 / h.getCalls());
        }
        if (h.getFailures() > 0) s += ", " + h.getFailures() + " failures";
        return s;
    }

    private static void line(CommandSender sender, String key, String value) {
        sender.sendMessage(Component.text("  " + key + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE)));
    }

    private static void ok(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GREEN));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private static void usage(CommandSender sender) {
        error(sender, "Usage: /nmshook [list [filter] | info <id> | enable <id> | disable <id> | profile on|off|reset"
                + " | debug on|off | reload]");
    }
}
