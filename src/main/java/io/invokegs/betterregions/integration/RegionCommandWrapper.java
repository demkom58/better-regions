package io.invokegs.betterregions.integration;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitWorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.permission.RegionPermissionModel;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.invokegs.betterregions.config.Configuration;
import io.invokegs.betterregions.config.Messages;
import io.invokegs.betterregions.economy.EconomyService;
import io.invokegs.betterregions.features.AutoFlagsFeature;
import io.invokegs.betterregions.features.BlockLimitsFeature;
import io.invokegs.betterregions.features.VerticalExpandFeature;
import io.invokegs.betterregions.integration.inject.CommandWrapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class RegionCommandWrapper extends CommandWrapper {

    private final Plugin plugin;
    private final Command originalCommand;
    private final Configuration config;
    private final Messages messages;
    private final EconomyService economyService;
    private final WorldGuardIntegration worldGuard;
    private final @Nullable VerticalExpandFeature verticalExpandFeature;
    private final @Nullable BlockLimitsFeature blockLimitsFeature;
    private final @Nullable AutoFlagsFeature autoFlagsFeature;

    public RegionCommandWrapper(Plugin plugin, Command originalCommand, Configuration config, Messages messages,
                                EconomyService economyService, WorldGuardIntegration worldGuard,
                                @Nullable VerticalExpandFeature verticalExpandFeature,
                                @Nullable BlockLimitsFeature blockLimitsFeature,
                                @Nullable AutoFlagsFeature autoFlagsFeature) {
        super(originalCommand.getName(), originalCommand.getDescription(), originalCommand.getUsage(), originalCommand.getAliases());

        this.plugin = plugin;
        this.originalCommand = originalCommand;
        this.config = config;
        this.messages = messages;
        this.economyService = economyService;
        this.worldGuard = worldGuard;
        this.verticalExpandFeature = verticalExpandFeature;
        this.blockLimitsFeature = blockLimitsFeature;
        this.autoFlagsFeature = autoFlagsFeature;

        setPermission(originalCommand.getPermission());
        setPermissionMessage(originalCommand.getPermissionMessage());
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!(sender instanceof Player player)) {
            return executeOriginal(sender, commandLabel, args);
        }

        if (args.length == 0) {
            return executeOriginal(sender, commandLabel, args);
        }

        var subCommand = args[0].toLowerCase(Locale.ROOT);

        switch (subCommand) {
            case "confirm" -> {
                return handleConfirm(player);
            }
            case "cancel" -> {
                return handleCancel(player);
            }
            case "claim" -> {
                return handleClaim(player, args);
            }
            case "redefine", "update", "move" -> {
                return handleRedefine(player, args);
            }
            default -> {
                return executeOriginal(sender, commandLabel, args);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (originalCommand instanceof TabCompleter tabCompleter) {
            return tabCompleter.onTabComplete(sender, command, alias, args);
        }
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, label, args);
    }

    private boolean executeOriginal(CommandSender sender, String commandLabel, String[] args) {
        try {
            return originalCommand.execute(sender, commandLabel, args);
        } catch (Exception e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
            return true;
        }
    }

    private boolean handleClaim(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(messages.claimUsage());
            return true;
        }

        economyService.cancelPendingAction(player);
        if (verticalExpandFeature != null) {
            verticalExpandFeature.expandVerticallyWithMessage(player);
        }

        if (!validateBlockLimits(player)) return true;

        var economyResult = economyService.processCommand(player, "claim", args);

        return switch (economyResult) {
            case EconomyService.ProcessResult.Allow() -> {
                performClaim(player, args);
                yield true;
            }
            case EconomyService.ProcessResult.Deny(var reason) -> {
                player.sendMessage(reason);
                yield true;
            }
            case EconomyService.ProcessResult.AwaitingConfirmation() -> true;
        };
    }

    private boolean handleRedefine(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(messages.redefineUsage());
            return true;
        }

        economyService.cancelPendingAction(player);
        if (!validateBlockLimits(player)) return true;

        var economyResult = economyService.processCommand(player, "redefine", args);

        return switch (economyResult) {
            case EconomyService.ProcessResult.Allow() -> {
                performRedefine(player, args);
                yield true;
            }
            case EconomyService.ProcessResult.Deny(var reason) -> {
                player.sendMessage(reason);
                yield true;
            }
            case EconomyService.ProcessResult.AwaitingConfirmation() -> true;
        };
    }

    private boolean handleConfirm(Player player) {
        var pendingAction = economyService.getPendingAction(player);
        if (pendingAction == null) {
            player.sendMessage(messages.noPendingAction());
            return true;
        }

        var result = economyService.handleConfirmation(player, true);
        switch (result) {
            case EconomyService.ProcessResult.Allow() -> {
                var command = pendingAction.command();
                var originalArgs = pendingAction.args();

                if ("claim".equals(command)) {
                    performClaim(player, originalArgs);
                } else if ("redefine".equals(command)) {
                    performRedefine(player, originalArgs);
                }
            }
            case EconomyService.ProcessResult.Deny(var reason) -> player.sendMessage(reason);
            case EconomyService.ProcessResult.AwaitingConfirmation() -> {}
        }

        return true;
    }

    private boolean handleCancel(Player player) {
        var result = economyService.handleConfirmation(player, false);
        if (result instanceof EconomyService.ProcessResult.Deny(Component reason)) {
            player.sendMessage(reason);
        }
        return true;
    }

    private void performClaim(Player player, String[] args) {
        try {
            var regionId = args[1];
            var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            var permModel = new RegionPermissionModel(localPlayer);

            if (!permModel.mayClaim()) {
                player.sendMessage(messages.noPermission());
                return;
            }

            var manager = getRegionManager(player);
            if (manager == null) return;

            if (!ProtectedRegion.isValidId(regionId) || regionId.startsWith("-")) {
                player.sendMessage(messages.invalidRegionName(regionId));
                return;
            }

            if (manager.hasRegion(regionId)) {
                player.sendMessage(messages.regionAlreadyExists(regionId));
                return;
            }

            var region = createRegionFromSelection(player, regionId);
            if (region == null) return;

            var wcfg = getWorldConfig(player);

            if (!permModel.mayClaimRegionsUnbounded()) {
                int maxRegionCount = wcfg.getMaxRegionCount(localPlayer);
                if (maxRegionCount >= 0 && manager.getRegionCountOfPlayer(localPlayer) >= maxRegionCount) {
                    player.sendMessage(messages.tooManyRegions());
                    return;
                }

                if (wcfg.maxClaimVolume >= Integer.MAX_VALUE) {
                    player.sendMessage(messages.maxVolumeConfig());
                    return;
                }

                if (region.volume() > wcfg.maxClaimVolume) {
                    player.sendMessage(messages.regionTooLarge(region.volume(), wcfg.maxClaimVolume));
                    return;
                }
            }

            var regions = manager.getApplicableRegions(region);

            if (regions.size() > 0) {
                if (!regions.isOwnerOfAll(localPlayer)) {
                    player.sendMessage(messages.regionOverlaps());
                    return;
                }
            } else {
                if (wcfg.claimOnlyInsideExistingRegions) {
                    player.sendMessage(messages.claimOnlyInsideExisting());
                    return;
                }
            }

            if (wcfg.setParentOnClaim != null && !wcfg.setParentOnClaim.isEmpty()) {
                var templateRegion = manager.getRegion(wcfg.setParentOnClaim);
                if (templateRegion != null) {
                    try {
                        region.setParent(templateRegion);
                    } catch (ProtectedRegion.CircularInheritanceException e) {
                        player.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
                        return;
                    }
                }
            }

            region.getOwners().addPlayer(localPlayer);
            manager.addRegion(region);

            try {
                manager.save();
            } catch (Exception saveException) {
                plugin.getLogger().warning("Failed to save region manager: " + saveException.getMessage());
            }

            if (!economyService.processPaymentAfterSuccess(player)) {
                manager.removeRegion(regionId);
                try {
                    manager.save();
                } catch (Exception saveException) {
                    plugin.getLogger().warning("Failed to save region manager after removal: " + saveException.getMessage());
                }
                return;
            }

            player.sendMessage(messages.claimSuccess(regionId));

            if (autoFlagsFeature != null) {
                autoFlagsFeature.applyAutoFlags(player, player.getWorld(), regionId);
            }

        } catch (Exception e) {
            player.sendMessage(Component.text("An internal error occurred while claiming region.", NamedTextColor.RED));
            plugin.getLogger().warning("Failed to claim region: " + e.getMessage());
        }
    }

    private void performRedefine(Player player, String[] args) {
        try {
            var regionId = args[1];
            var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
            var manager = getRegionManager(player);
            if (manager == null) return;

            var existing = manager.getRegion(regionId);
            if (existing == null) {
                player.sendMessage(messages.regionNotExists(regionId));
                return;
            }

            var permModel = new RegionPermissionModel(localPlayer);
            if (!permModel.mayRedefine(existing)) {
                player.sendMessage(messages.noPermission());
                return;
            }

            var newRegion = createRegionFromSelection(player, regionId);
            if (newRegion == null) return;

            newRegion.copyFrom(existing);
            manager.addRegion(newRegion);

            try {
                manager.save();
            } catch (Exception saveException) {
                plugin.getLogger().warning("Failed to save region manager: " + saveException.getMessage());
            }

            if (!economyService.processPaymentAfterSuccess(player)) {
                manager.addRegion(existing);
                try {
                    manager.save();
                } catch (Exception saveException) {
                    plugin.getLogger().warning("Failed to save region manager after rollback: " + saveException.getMessage());
                }
                return;
            }

            player.sendMessage(messages.redefineSuccess(regionId));

        } catch (Exception e) {
            player.sendMessage(Component.text("Failed to redefine region: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().warning("Failed to redefine region: " + e.getMessage());
        }
    }

    private @Nullable ProtectedRegion createRegionFromSelection(Player player, String id) {
        try {
            var worldEdit = WorldEditPlugin.getPlugin(WorldEditPlugin.class);
            var session = worldEdit.getSession(player);
            var world = BukkitAdapter.adapt(player.getWorld());
            var selection = session.getSelection(world);

            if (selection instanceof CuboidRegion) {
                return new ProtectedCuboidRegion(id, selection.getMinimumPoint(), selection.getMaximumPoint());
            } else {
                player.sendMessage(messages.onlyCuboidSelection());
                return null;
            }
        } catch (IncompleteRegionException e) {
            player.sendMessage(messages.noSelection());
            return null;
        } catch (Exception e) {
            player.sendMessage(Component.text("Error creating region: " + e.getMessage(), NamedTextColor.RED));
            return null;
        }
    }

    private @Nullable RegionManager getRegionManager(Player player) {
        var manager = worldGuard.getRegionManager(player.getWorld());
        if (manager == null) {
            player.sendMessage(messages.regionManagementUnavailable());
            return null;
        }
        return manager;
    }

    private boolean validateBlockLimits(Player player) {
        if (blockLimitsFeature != null) {
            var validation = blockLimitsFeature.validateSelection(player);
            if (validation instanceof BlockLimitsFeature.ValidationResult.Deny(Component reason)) {
                player.sendMessage(reason);
                return false;
            }
        }
        return true;
    }

    private BukkitWorldConfiguration getWorldConfig(Player player) {
        return (BukkitWorldConfiguration) WorldGuard.getInstance()
                .getPlatform().getGlobalStateManager().get(BukkitAdapter.adapt(player.getWorld()));
    }
}