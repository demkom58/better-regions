package io.invokegs.betterregions.commands;

import io.invokegs.betterregions.BetterRegionsPlugin;
import io.invokegs.betterregions.config.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

@SuppressWarnings("UnstableApiUsage")
public final class BetterRegionsCommand implements CommandExecutor, TabCompleter {
    private static final Component HELP_HEADER = text("BetterRegions Help", Style.style(GRAY, TextDecoration.BOLD))
            .appendNewline()
            .append(text("Enhanced region management with economy integration", GRAY));

    private static final Component WEB_RESOURCES = text("Website: ")
            .append(text("github.com/demkom58/better-regions")
                    .color(AQUA)
                    .decorate(TextDecoration.ITALIC, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl("https://github.com/demkom58/better-regions")
                    )).appendNewline()
            .append(text("Modrinth: ").append(text("modrinth.com/project/better-regions")
                    .color(AQUA)
                    .decorate(TextDecoration.ITALIC, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl("https://modrinth.com/project/better-regions")
                    )));

    private final BetterRegionsPlugin plugin;
    private final Messages messages;

    public BetterRegionsCommand(BetterRegionsPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return handleHelpCommand(sender);
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReloadCommand(sender);
            case "help" -> handleHelpCommand(sender);
            default -> handleHelpCommand(sender);
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length <= 1) {
            var partial = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            return Stream.of("reload", "info", "help", "version")
                    .filter(sub -> sub.startsWith(partial))
                    .toList();
        }
        return List.of();
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("betterregions.admin")) {
            sender.sendMessage(messages.noPermission());
            return true;
        }

        try {
            plugin.reload();
            sender.sendMessage(messages.pluginReloaded());
        } catch (Exception e) {
            sender.sendMessage(text("Failed to reload configuration: " + e.getMessage())
                    .color(RED));
            plugin.getLogger().severe("Failed to reload configuration: " + e.getMessage());
        }

        return true;
    }

    private boolean handleHelpCommand(CommandSender sender) {
        sender.sendMessage(HELP_HEADER);

        sender.sendMessage(text("Commands:", YELLOW));
        var commands = List.of(
                createCommandHelp("/betterregions reload", "Reload plugin configuration", "betterregions.admin"),
                createCommandHelp("/betterregions help", "Show this help message"),
                createCommandHelp("/rg claim <region>", "Claim a region with economy integration"),
                createCommandHelp("/rg redefine <region>", "Redefine region boundaries"),
                createCommandHelp("/rg confirm", "Confirm pending payment"),
                createCommandHelp("/rg cancel", "Cancel pending action")
        );

        for (var commandHelp : commands) {
            if (commandHelp.permission() == null || sender.hasPermission(commandHelp.permission())) {
                sender.sendMessage(commandHelp.component());
            }
        }

        sender.sendMessage(WEB_RESOURCES);
        return true;
    }

    private record CommandHelp(Component component, @Nullable String permission) {
    }

    private CommandHelp createCommandHelp(String command, String description) {
        return createCommandHelp(command, description, null);
    }

    private CommandHelp createCommandHelp(String command, String description, @Nullable String permission) {
        var component = text("  " + command)
                .color(YELLOW)
                .clickEvent(ClickEvent.suggestCommand(command))
                .append(text(" - " + description).color(GRAY));

        return new CommandHelp(component, permission);
    }
}