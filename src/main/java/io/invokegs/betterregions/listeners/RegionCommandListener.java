package io.invokegs.betterregions.listeners;

import io.invokegs.betterregions.config.Configuration;
import io.invokegs.betterregions.config.Messages;
import io.invokegs.betterregions.economy.EconomyService;
import io.invokegs.betterregions.features.AutoFlagsFeature;
import io.invokegs.betterregions.features.BlockLimitsFeature;
import io.invokegs.betterregions.features.VerticalExpandFeature;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Listener that intercepts WorldGuard region commands and applies BetterRegions features.
 * Handles economy integration, vertical expansion, block limits, and auto flags.
 */
public final class RegionCommandListener implements Listener {

    private static final Pattern REGION_COMMAND_PATTERN = Pattern.compile(
            "^/(rg|region)\\s+(claim|define|redefine|confirm|cancel)\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    private final Plugin plugin;
    private final Configuration config;
    private final Messages messages;
    private final EconomyService economyService;
    private final @Nullable VerticalExpandFeature verticalExpandFeature;
    private final @Nullable BlockLimitsFeature blockLimitsFeature;
    private final @Nullable AutoFlagsFeature autoFlagsFeature;

    public RegionCommandListener(Plugin plugin, Configuration config, Messages messages,
                                 EconomyService economyService, @Nullable VerticalExpandFeature verticalExpandFeature,
                                 @Nullable BlockLimitsFeature blockLimitsFeature, @Nullable AutoFlagsFeature autoFlagsFeature) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.economyService = economyService;
        this.verticalExpandFeature = verticalExpandFeature;
        this.blockLimitsFeature = blockLimitsFeature;
        this.autoFlagsFeature = autoFlagsFeature;
    }

    /**
     * Intercepts region commands to apply BetterRegions features.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        var player = event.getPlayer();
        var command = event.getMessage();

        var matcher = REGION_COMMAND_PATTERN.matcher(command);
        if (!matcher.matches()) {
            return;
        }

        var subCommand = matcher.group(2).toLowerCase(Locale.ROOT);
        var args = parseArgs(matcher.group(3));

        switch (subCommand) {
            case "claim", "define", "redefine" -> {
                if (handleRegionCreationCommand(player, event, subCommand, args)) {
                    event.setCancelled(true);
                }
            }
            case "confirm" -> {
                if (handleConfirmCommand(player, event)) {
                    event.setCancelled(true);
                }
            }
            case "cancel" -> {
                if (handleCancelCommand(player, event)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    /**
     * Handles region creation commands (claim, define, redefine).
     *
     * @return true to cancel command, false to execute
     */
    private boolean handleRegionCreationCommand(Player player, PlayerCommandPreprocessEvent event,
                                                String subCommand, String[] args) {
        if (args.length < 1) return false;
        var regionName = args[0];

        if ("claim".equals(subCommand) && verticalExpandFeature != null) {
            verticalExpandFeature.expandVerticallyWithMessage(player);
        }

        if (blockLimitsFeature != null) {
            var validation = blockLimitsFeature.validateSelection(player);
            if (validation instanceof BlockLimitsFeature.ValidationResult.Deny(Component reason)) {
                player.sendMessage(reason);
                return true;
            }
        }

        var commandArgs = new String[args.length + 1];
        commandArgs[0] = subCommand;
        System.arraycopy(args, 0, commandArgs, 1, args.length);

        var economyResult = economyService.processCommand(player, subCommand, commandArgs);

        return switch (economyResult) {
            case EconomyService.ProcessResult.Allow() -> {
                schedulePostCommandActions(player, subCommand, regionName);
                yield false;
            }
            case EconomyService.ProcessResult.Deny(var reason) -> {
                player.sendMessage(reason);
                yield true;
            }
            case EconomyService.ProcessResult.AwaitingConfirmation() -> true;
        };
    }

    /**
     * Handles confirm commands.
     */
    private boolean handleConfirmCommand(Player player, PlayerCommandPreprocessEvent event) {
        var result = economyService.handleConfirmation(player, true);

        switch (result) {
            case EconomyService.ProcessResult.Allow() -> {
                var pendingAction = economyService.getPendingAction(player);
                if (pendingAction != null) {
                    executeOriginalCommand(player, pendingAction);
                }
            }
            case EconomyService.ProcessResult.Deny(var reason) -> player.sendMessage(reason);
            case EconomyService.ProcessResult.AwaitingConfirmation() -> {}
        }
        return true;
    }

    /**
     * Handles cancel commands.
     */
    private boolean handleCancelCommand(Player player, PlayerCommandPreprocessEvent event) {
        var result = economyService.handleConfirmation(player, false);

        if (result instanceof EconomyService.ProcessResult.Deny(net.kyori.adventure.text.Component reason)) {
            player.sendMessage(reason);
        }

        return true;
    }

    /**
     * Executes the original region command after confirmation.
     */
    private void executeOriginalCommand(Player player, EconomyService.PendingAction action) {
        var command = String.join(" ", action.args());
        var regionName = action.regionName();

        plugin.getServer().dispatchCommand(player, "region " + command);
        schedulePostCommandActions(player, action.command(), regionName);
    }

    /**
     * Schedules actions to run after a region command completes.
     */
    private void schedulePostCommandActions(Player player, String command, String regionName) {
        if (autoFlagsFeature != null && ("claim".equals(command) || "define".equals(command))) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> autoFlagsFeature.applyAutoFlags(player, player.getWorld(), regionName), 0L
            );
        }
    }

    /**
     * Parses command arguments from a string.
     */
    private String[] parseArgs(@Nullable String argsString) {
        if (argsString == null || argsString.trim().isEmpty()) {
            return new String[0];
        }
        return argsString.trim().split("\\s+");
    }
}