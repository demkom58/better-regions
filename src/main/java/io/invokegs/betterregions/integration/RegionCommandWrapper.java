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

/**
 * Wraps the original WorldGuard region command to add BetterRegions features.
 * Handles economy integration, vertical expansion, block limits, and auto flags.
 */
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

        // Copy properties from original command
        setPermission(originalCommand.getPermission());
        setPermissionMessage(originalCommand.getPermissionMessage());
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        // Only intercept for players
        if (!(sender instanceof Player player)) {
            return executeOriginal(sender, commandLabel, args);
        }

        // Need at least one argument for subcommand
        if (args.length == 0) {
            return executeOriginal(sender, commandLabel, args);
        }

        var subCommand = args[0].toLowerCase(Locale.ROOT);

        // Handle our special commands
        switch (subCommand) {
            case "confirm" -> {
                return handleConfirm(player);
            }
            case "cancel" -> {
                return handleCancel(player);
            }
            case "claim" -> {
                return handleClaim(player, args) || executeOriginal(sender, commandLabel, args);
            }
            case "redefine", "update", "move" -> {
                return handleRedefine(player, args) || executeOriginal(sender, commandLabel, args);
            }
            default -> {
                return executeOriginal(sender, commandLabel, args);
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Delegate tab completion to the original command
        if (originalCommand instanceof TabCompleter tabCompleter) {
            return tabCompleter.onTabComplete(sender, command, alias, args);
        }
        return List.of();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, label, args);
    }

    /**
     * Executes the original WorldGuard command.
     */
    private boolean executeOriginal(CommandSender sender, String commandLabel, String[] args) {
        try {
            return originalCommand.execute(sender, commandLabel, args);
        } catch (Exception e) {
            sender.sendMessage(Component.text("Command execution failed: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().warning("Original command execution failed: " + e.getMessage());
            return true;
        }
    }

    /**
     * Handles claim commands with BetterRegions features.
     * @return true if we handled the command (don't execute original), false if original should execute
     */
    private boolean handleClaim(Player player, String[] args) {
        // Validate command arguments
        if (args.length < 2) {
            player.sendMessage(messages.claimUsage());
            return true; // We handled it (showed error)
        }

        var regionId = args[1];
        if (!isValidRegionId(player, regionId)) return true;
        if (!hasClaimPermission(player)) return true;
        if (!regionIsAvailable(player, regionId)) return true;

        // Cancel any pending actions
        economyService.cancelPendingAction(player);

        // Apply vertical expansion if enabled
        if (verticalExpandFeature != null) {
            verticalExpandFeature.expandVerticallyWithMessage(player);
        }

        // Validate block limits
        if (!validateBlockLimits(player)) return true;

        // Create region from selection for validation
        var region = createRegionFromSelection(player, regionId);
        if (region == null) return true;

        // Validate claim-specific restrictions
        if (!validateClaimRegion(player, region)) return true;

        // Process economy
        var economyResult = economyService.processCommand(player, "claim", args);

        return switch (economyResult) {
            case EconomyService.ProcessResult.Allow() -> {
                // Let the original command execute, then apply post-processing
                schedulePostCommand(player, "claim", regionId);
                yield false; // Execute original command
            }
            case EconomyService.ProcessResult.Deny(var reason) -> {
                player.sendMessage(reason);
                yield true; // Don't execute original
            }
            case EconomyService.ProcessResult.AwaitingConfirmation() -> true; // Don't execute original
        };
    }

    /**
     * Handles redefine commands with BetterRegions features.
     */
    private boolean handleRedefine(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(messages.redefineUsage());
            return true;
        }

        var regionId = args[1];
        if (!hasRedefinePermission(player, regionId)) return true;

        economyService.cancelPendingAction(player);
        if (!validateBlockLimits(player)) return true;

        var region = createRegionFromSelection(player, regionId);
        if (region == null) return true;

        var economyResult = economyService.processCommand(player, "redefine", args);

        return switch (economyResult) {
            case EconomyService.ProcessResult.Allow() -> {
                schedulePostCommand(player, "redefine", regionId);
                yield false; // Execute original command
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
    private boolean handleConfirm(Player player) {
        var pendingAction = economyService.getPendingAction(player);
        if (pendingAction == null) {
            player.sendMessage(messages.noPendingAction());
            return true;
        }

        if (!isSelectionValid(player, pendingAction)) {
            economyService.cancelPendingAction(player);
            return true;
        }

        var result = economyService.handleConfirmation(player, true);
        switch (result) {
            case EconomyService.ProcessResult.Allow() -> executeConfirmedAction(player, pendingAction);
            case EconomyService.ProcessResult.Deny(var reason) -> player.sendMessage(reason);
            case EconomyService.ProcessResult.AwaitingConfirmation() -> {}
        }

        return true;
    }

    /**
     * Handles cancel commands.
     */
    private boolean handleCancel(Player player) {
        var result = economyService.handleConfirmation(player, false);
        if (result instanceof EconomyService.ProcessResult.Deny(Component reason)) {
            player.sendMessage(reason);
        }
        return true;
    }

    /**
     * Executes a confirmed action by running the original command.
     */
    private void executeConfirmedAction(Player player, EconomyService.PendingAction action) {
        var command = action.command();
        var regionName = action.regionName();

        // Apply vertical expansion for claim commands
        if ("claim".equals(command) && verticalExpandFeature != null) {
            verticalExpandFeature.expandVerticallyWithMessage(player);
        }

        // Build the command string and execute it
        var commandString = "/rg " + String.join(" ", action.args());

        // Execute the original command
        plugin.getServer().dispatchCommand(player, commandString.substring(1)); // Remove leading slash

        // Schedule post-command processing
        schedulePostCommand(player, command, regionName);
    }

    /**
     * Schedules post-command processing (payment and auto flags).
     */
    private void schedulePostCommand(Player player, String command, String regionName) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // Process payment after successful command
            economyService.processPaymentAfterSuccess(player);

            // Apply auto flags for creation commands
            if (("claim".equals(command) || "define".equals(command)) && autoFlagsFeature != null) {
                autoFlagsFeature.applyAutoFlags(player, player.getWorld(), regionName);
            }
        }, 1L); // 1 tick delay to ensure command completed
    }

    // Validation methods (same as in the simplified version)
    private boolean isValidRegionId(Player player, String regionId) {
        if (!ProtectedRegion.isValidId(regionId) || regionId.startsWith("-")) {
            player.sendMessage(messages.invalidRegionName(regionId));
            return false;
        }
        return true;
    }

    private boolean hasClaimPermission(Player player) {
        var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        var permModel = new RegionPermissionModel(localPlayer);
        if (!permModel.mayClaim()) {
            player.sendMessage(messages.noPermission());
            return false;
        }
        return true;
    }

    private boolean hasDefinePermission(Player player) {
        var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        var permModel = new RegionPermissionModel(localPlayer);
        if (!permModel.mayDefine()) {
            player.sendMessage(messages.noPermission());
            return false;
        }
        return true;
    }

    private boolean hasRedefinePermission(Player player, String regionId) {
        var manager = getRegionManager(player);
        if (manager == null) return false;

        var existing = manager.getRegion(regionId);
        if (existing == null) {
            player.sendMessage(messages.regionNotExists(regionId));
            return false;
        }

        var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        var permModel = new RegionPermissionModel(localPlayer);
        if (!permModel.mayRedefine(existing)) {
            player.sendMessage(messages.noPermission());
            return false;
        }
        return true;
    }

    private boolean regionIsAvailable(Player player, String regionId) {
        var manager = getRegionManager(player);
        if (manager == null) return false;

        if (manager.hasRegion(regionId)) {
            player.sendMessage(messages.regionAlreadyExists(regionId));
            return false;
        }
        return true;
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

    private boolean validateClaimRegion(Player player, ProtectedRegion region) {
        var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        var permModel = new RegionPermissionModel(localPlayer);
        var manager = getRegionManager(player);
        if (manager == null) return false;

        var wcfg = getWorldConfig(player);

        if (!permModel.mayClaimRegionsUnbounded()) {
            int maxRegionCount = wcfg.getMaxRegionCount(localPlayer);
            if (maxRegionCount >= 0 && manager.getRegionCountOfPlayer(localPlayer) >= maxRegionCount) {
                player.sendMessage(messages.tooManyRegions());
                return false;
            }

            if (wcfg.maxClaimVolume == Integer.MAX_VALUE) {
                player.sendMessage(messages.maxVolumeConfig());
                return false;
            }

            if (region.volume() > wcfg.maxClaimVolume) {
                player.sendMessage(messages.regionTooLarge(region.volume(), wcfg.maxClaimVolume));
                return false;
            }
        }

        var regions = manager.getApplicableRegions(region);
        if (regions.size() > 0) {
            if (!regions.isOwnerOfAll(localPlayer)) {
                player.sendMessage(messages.regionOverlaps());
                return false;
            }
        } else if (wcfg.claimOnlyInsideExistingRegions) {
            player.sendMessage(messages.claimOnlyInsideExisting());
            return false;
        }

        return true;
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

    private boolean isSelectionValid(Player player, EconomyService.PendingAction action) {
        try {
            var currentSelection = getPlayerSelection(player);
            if (currentSelection == null || !selectionsMatch(action.originalSelection(), currentSelection)) {
                player.sendMessage(messages.selectionChanged());
                return false;
            }
            return true;
        } catch (Exception e) {
            player.sendMessage(messages.selectionLost());
            return false;
        }
    }

    private @Nullable Region getPlayerSelection(Player player) throws IncompleteRegionException {
        var worldEdit = WorldEditPlugin.getPlugin(WorldEditPlugin.class);
        var session = worldEdit.getSession(player);
        var world = BukkitAdapter.adapt(player.getWorld());
        return session.getSelection(world);
    }

    private boolean selectionsMatch(Region original, Region current) {
        return original.getMinimumPoint().equals(current.getMinimumPoint()) &&
                original.getMaximumPoint().equals(current.getMaximumPoint());
    }

    private BukkitWorldConfiguration getWorldConfig(Player player) {
        return (BukkitWorldConfiguration) WorldGuard.getInstance()
                .getPlatform().getGlobalStateManager().get(BukkitAdapter.adapt(player.getWorld()));
    }
}