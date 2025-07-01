package io.invokegs.betterregions.economy;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.invokegs.betterregions.config.Configuration;
import io.invokegs.betterregions.config.Messages;
import io.invokegs.betterregions.integration.VaultIntegration;
import io.invokegs.betterregions.integration.WorldGuardIntegration;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class EconomyService {

    private final VaultIntegration vault;
    private final Configuration config;
    private final Messages messages;
    private final Plugin plugin;
    private final WorldGuardIntegration worldGuard;
    private final Map<UUID, PendingAction> pendingActions;

    public EconomyService(VaultIntegration vault, Configuration config, Messages messages, Plugin plugin) {
        this.vault = vault;
        this.config = config;
        this.messages = messages;
        this.plugin = plugin;
        this.worldGuard = new WorldGuardIntegration();
        this.pendingActions = new ConcurrentHashMap<>();
    }

    public void setup() {
        worldGuard.setup();
    }

    public record RegionBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public BigInteger getVolume() {
            return BigInteger.valueOf(maxX - minX + 1)
                    .multiply(BigInteger.valueOf(maxY - minY + 1))
                    .multiply(BigInteger.valueOf(maxZ - minZ + 1));
        }

        public BigInteger getHorizontalArea() {
            return BigInteger.valueOf(maxX - minX + 1)
                    .multiply(BigInteger.valueOf(maxZ - minZ + 1));
        }

        public @Nullable RegionBounds intersect(RegionBounds other) {
            int newMinX = Math.max(this.minX, other.minX);
            int newMinY = Math.max(this.minY, other.minY);
            int newMinZ = Math.max(this.minZ, other.minZ);
            int newMaxX = Math.min(this.maxX, other.maxX);
            int newMaxY = Math.min(this.maxY, other.maxY);
            int newMaxZ = Math.min(this.maxZ, other.maxZ);

            return (newMinX <= newMaxX && newMinY <= newMaxY && newMinZ <= newMaxZ)
                    ? new RegionBounds(newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ)
                    : null;
        }
    }

    public record CostInfo(
            double totalCost,
            double horizontalCost,
            double verticalCost,
            BigInteger horizontalBlocks,
            BigInteger verticalBlocks,
            BigInteger totalVolume
    ) {
    }

    public record PendingAction(
            String command,
            String[] args,
            CostInfo costInfo,
            String regionName,
            Region originalSelection,
            BukkitTask timeoutTask,
            boolean paymentProcessed
    ) {
    }

    public sealed interface ProcessResult permits ProcessResult.Allow, ProcessResult.Deny, ProcessResult.AwaitingConfirmation {
        record Allow() implements ProcessResult {
        }

        record Deny(Component reason) implements ProcessResult {
        }

        record AwaitingConfirmation() implements ProcessResult {
        }
    }

    public ProcessResult processCommand(Player player, String command, String[] args) {
        if (!config.isEconomyEnabled() || !vault.isEconomyAvailable()) {
            return vault.isEconomyAvailable() ? new ProcessResult.Allow() :
                    new ProcessResult.Deny(messages.economyNotAvailable());
        }

        if (player.hasPermission("betterregions.economy.bypass")) {
            return new ProcessResult.Allow();
        }

        cancelPendingAction(player);

        var costInfo = calculateCost(player, command, args);
        if (costInfo == null || costInfo.totalCost() <= 0) {
            return new ProcessResult.Allow();
        }

        if (!vault.hasBalance(player, costInfo.totalCost())) {
            return new ProcessResult.Deny(messages.insufficientFundsDetailed(
                    vault.formatCurrency(costInfo.totalCost()),
                    vault.formatCurrency(costInfo.horizontalCost()),
                    vault.formatCurrency(costInfo.verticalCost()),
                    costInfo.horizontalBlocks(),
                    costInfo.verticalBlocks(),
                    vault.formatCurrency(vault.getBalance(player))
            ));
        }

        try {
            var selection = getPlayerSelection(player);
            if (selection != null) {
                createPendingAction(player, command, args, costInfo, selection);
                return new ProcessResult.AwaitingConfirmation();
            }
        } catch (Exception ignored) {
        }

        return new ProcessResult.Allow();
    }

    public ProcessResult handleConfirmation(Player player, boolean confirm) {
        var action = pendingActions.get(player.getUniqueId());
        if (action == null) {
            return new ProcessResult.Deny(messages.noPendingAction());
        }

        if (!confirm) {
            removePendingAction(player);
            return new ProcessResult.Deny(messages.actionCancelled());
        }

        try {
            var currentSelection = getPlayerSelection(player);
            if (currentSelection == null || !selectionsMatch(action.originalSelection(), currentSelection)) {
                removePendingAction(player);
                return new ProcessResult.Deny(messages.selectionChanged());
            }
        } catch (Exception e) {
            removePendingAction(player);
            return new ProcessResult.Deny(messages.selectionLost());
        }

        var freshCostInfo = calculateCost(player, action.command(), action.args());
        if (freshCostInfo == null || freshCostInfo.totalCost() <= 0) {
            removePendingAction(player);
            return new ProcessResult.Allow();
        }

        if (!vault.hasBalance(player, freshCostInfo.totalCost())) {
            removePendingAction(player);
            return new ProcessResult.Deny(messages.insufficientFunds(
                    vault.formatCurrency(freshCostInfo.totalCost()),
                    vault.formatCurrency(vault.getBalance(player))
            ));
        }

        var updatedAction = new PendingAction(
                action.command(), action.args(), freshCostInfo, action.regionName(),
                action.originalSelection(), action.timeoutTask(), false
        );
        pendingActions.put(player.getUniqueId(), updatedAction);

        return new ProcessResult.Allow();
    }

    public boolean processPaymentAfterSuccess(Player player) {
        var action = pendingActions.remove(player.getUniqueId());
        if (action == null || action.paymentProcessed()) {
            return true;
        }

        action.timeoutTask().cancel();
        var totalCost = action.costInfo().totalCost();

        if (totalCost <= 0) {
            return true;
        }

        if (!vault.withdraw(player, totalCost)) {
            player.sendMessage(messages.paymentFailedAfterCommand(
                    vault.formatCurrency(totalCost),
                    vault.formatCurrency(vault.getBalance(player))
            ));
            return false;
        }

        var costInfo = action.costInfo();
        player.sendMessage(messages.paymentProcessed(
                vault.formatCurrency(totalCost),
                vault.formatCurrency(costInfo.horizontalCost()),
                vault.formatCurrency(costInfo.verticalCost()),
                costInfo.horizontalBlocks(),
                costInfo.verticalBlocks()
        ));

        return true;
    }

    public void cancelPendingAction(Player player) {
        var action = pendingActions.remove(player.getUniqueId());
        if (action != null) {
            action.timeoutTask().cancel();
        }
    }

    public @Nullable PendingAction getPendingAction(Player player) {
        return pendingActions.get(player.getUniqueId());
    }

    public void reload() {
        cleanup();
    }

    public void cleanup() {
        pendingActions.values().forEach(action -> action.timeoutTask().cancel());
        pendingActions.clear();
    }

    private void removePendingAction(Player player) {
        var action = pendingActions.remove(player.getUniqueId());
        if (action != null) {
            action.timeoutTask().cancel();
        }
    }

    private @Nullable CostInfo calculateCost(Player player, String command, String[] args) {
        if (args.length < 2) return null;

        var regionName = args[1];
        var pricing = getPricingForPlayer(player);

        if (pricing.horizontal() <= 0 && pricing.vertical() <= 0) {
            return new CostInfo(0, 0, 0, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        }

        return switch (command.toLowerCase()) {
            case "claim" -> calculateNewRegionCost(player, pricing);
            case "redefine" -> calculateRedefineCost(player, regionName, pricing);
            default -> null;
        };
    }

    private Configuration.PricingTier getPricingForPlayer(Player player) {
        var pricePermissions = config.getPricePermissions();
        double bestHorizontal = config.getDefaultHorizontalPricePerBlock();
        double bestVertical = config.getDefaultVerticalPricePerBlock();

        for (var entry : pricePermissions.entrySet()) {
            if (player.hasPermission("betterregions.pricing." + entry.getKey())) {
                var tier = entry.getValue();
                bestHorizontal = Math.min(bestHorizontal, tier.horizontal());
                bestVertical = Math.min(bestVertical, tier.vertical());
            }
        }

        return new Configuration.PricingTier(bestHorizontal, bestVertical);
    }

    private @Nullable CostInfo calculateNewRegionCost(Player player, Configuration.PricingTier pricing) {
        try {
            var selection = getPlayerSelection(player);
            if (selection == null) return null;

            var newBounds = getRegionBounds(selection);
            var manager = worldGuard.getRegionManager(player.getWorld());

            if (manager == null) {
                return calculateFullCost(newBounds, pricing);
            }

            var existingRegions = getOverlappingRegions(manager, newBounds);
            if (existingRegions.isEmpty()) {
                return calculateFullCost(newBounds, pricing);
            }

            var newBlocks = calculateNewBlocks(newBounds, existingRegions);
            if (newBlocks.compareTo(BigInteger.ZERO) <= 0) {
                return new CostInfo(0, 0, 0, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
            }

            var horizontalArea = newBounds.getHorizontalArea();
            var coveredHorizontalArea = calculateCoveredHorizontalArea(newBounds, existingRegions);
            var newHorizontalArea = horizontalArea.subtract(coveredHorizontalArea).max(BigInteger.ZERO);
            var newVerticalBlocks = newBlocks.subtract(newHorizontalArea).max(BigInteger.ZERO);

            var horizontalCost = calculateSafeCost(newHorizontalArea, pricing.horizontal());
            var verticalCost = calculateSafeCost(newVerticalBlocks, pricing.vertical());

            return new CostInfo(
                    horizontalCost + verticalCost,
                    horizontalCost,
                    verticalCost,
                    newHorizontalArea,
                    newVerticalBlocks,
                    newBlocks
            );

        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable CostInfo calculateRedefineCost(Player player, String regionName, Configuration.PricingTier pricing) {
        try {
            var selection = getPlayerSelection(player);
            if (selection == null) return null;

            var existingRegion = worldGuard.getRegion(player.getWorld(), regionName);
            if (existingRegion == null) {
                return calculateNewRegionCost(player, pricing);
            }

            var newBounds = getRegionBounds(selection);
            var oldBounds = getRegionBounds(existingRegion);
            var manager = worldGuard.getRegionManager(player.getWorld());
            var otherRegions = new ArrayList<ProtectedRegion>();

            if (manager != null) {
                for (var region : manager.getRegions().values()) {
                    if (!(region instanceof GlobalProtectedRegion) &&
                            !region.getId().equals(regionName) &&
                            newBounds.intersect(getRegionBounds(region)) != null) {
                        otherRegions.add(region);
                    }
                }
            }

            var totalNewBlocks = calculateNewBlocks(newBounds, otherRegions);
            var oldBlocks = oldBounds.getVolume();
            var oldIntersectionWithOthers = calculateCoveredVolume(oldBounds,
                    otherRegions.stream().map(this::getRegionBounds).toList());
            var actualOldBlocks = oldBlocks.subtract(oldIntersectionWithOthers);
            var additionalBlocks = totalNewBlocks.subtract(actualOldBlocks);

            if (additionalBlocks.compareTo(BigInteger.ZERO) <= 0) {
                return new CostInfo(0, 0, 0, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
            }

            var additionalHorizontal = calculateAdditionalHorizontalArea(newBounds, oldBounds, otherRegions);
            var additionalVertical = additionalBlocks.subtract(additionalHorizontal).max(BigInteger.ZERO);

            var horizontalCost = calculateSafeCost(additionalHorizontal, pricing.horizontal());
            var verticalCost = calculateSafeCost(additionalVertical, pricing.vertical());

            return new CostInfo(
                    horizontalCost + verticalCost,
                    horizontalCost,
                    verticalCost,
                    additionalHorizontal,
                    additionalVertical,
                    additionalBlocks
            );

        } catch (Exception e) {
            return null;
        }
    }

    private BigInteger calculateAdditionalHorizontalArea(RegionBounds newBounds, RegionBounds oldBounds,
                                                         List<ProtectedRegion> otherRegions) {
        var newHorizontalArea = newBounds.getHorizontalArea();
        var oldHorizontalArea = oldBounds.getHorizontalArea();
        var coveredOldHorizontal = calculateCoveredHorizontalArea(oldBounds, otherRegions);
        var actualOldHorizontal = oldHorizontalArea.subtract(coveredOldHorizontal);
        var coveredNewHorizontal = calculateCoveredHorizontalArea(newBounds, otherRegions);
        var actualNewHorizontal = newHorizontalArea.subtract(coveredNewHorizontal);

        return actualNewHorizontal.subtract(actualOldHorizontal).max(BigInteger.ZERO);
    }

    private CostInfo calculateFullCost(RegionBounds bounds, Configuration.PricingTier pricing) {
        var horizontalArea = bounds.getHorizontalArea();
        var totalVolume = bounds.getVolume();
        var verticalBlocks = totalVolume.subtract(horizontalArea);

        var horizontalCost = calculateSafeCost(horizontalArea, pricing.horizontal());
        var verticalCost = calculateSafeCost(verticalBlocks, pricing.vertical());

        return new CostInfo(
                horizontalCost + verticalCost,
                horizontalCost,
                verticalCost,
                horizontalArea,
                verticalBlocks,
                totalVolume
        );
    }

    private List<ProtectedRegion> getOverlappingRegions(com.sk89q.worldguard.protection.managers.RegionManager manager,
                                                        RegionBounds newBounds) {
        return manager.getRegions().values().stream()
                .filter(region -> !(region instanceof GlobalProtectedRegion))
                .filter(region -> newBounds.intersect(getRegionBounds(region)) != null)
                .toList();
    }

    private BigInteger calculateNewBlocks(RegionBounds newBounds, List<ProtectedRegion> existingRegions) {
        if (existingRegions.isEmpty()) {
            return newBounds.getVolume();
        }

        var existingBounds = existingRegions.stream().map(this::getRegionBounds).toList();
        var coveredVolume = calculateCoveredVolume(newBounds, existingBounds);
        return newBounds.getVolume().subtract(coveredVolume);
    }

    private BigInteger calculateCoveredVolume(RegionBounds newBounds, List<RegionBounds> existingBounds) {
        if (existingBounds.isEmpty()) return BigInteger.ZERO;

        var intersections = existingBounds.stream()
                .map(newBounds::intersect)
                .filter(Objects::nonNull)
                .toList();

        return intersections.isEmpty() ? BigInteger.ZERO : calculateUnionVolume(intersections);
    }

    private BigInteger calculateCoveredHorizontalArea(RegionBounds newBounds, List<ProtectedRegion> existingRegions) {
        if (existingRegions.isEmpty()) return BigInteger.ZERO;

        var horizontalIntersections = existingRegions.stream()
                .map(region -> {
                    var regionBounds = getRegionBounds(region);
                    var newHorizontal = new RegionBounds(newBounds.minX, newBounds.minY, newBounds.minZ,
                            newBounds.maxX, newBounds.minY, newBounds.maxZ);
                    var regionHorizontal = new RegionBounds(regionBounds.minX, newBounds.minY, regionBounds.minZ,
                            regionBounds.maxX, newBounds.minY, regionBounds.maxZ);
                    return newHorizontal.intersect(regionHorizontal);
                })
                .filter(Objects::nonNull)
                .toList();

        return horizontalIntersections.isEmpty() ? BigInteger.ZERO : calculateUnionVolume(horizontalIntersections);
    }

    private BigInteger calculateUnionVolume(List<RegionBounds> regions) {
        if (regions.isEmpty()) return BigInteger.ZERO;
        if (regions.size() == 1) return regions.getFirst().getVolume();
        return calculateUnionVolumeSweep(regions);
    }

    private BigInteger calculateUnionVolumeSweep(List<RegionBounds> regions) {
        var events = new ArrayList<XEvent>();
        for (var region : regions) {
            events.add(new XEvent(region.minX, true, region));
            events.add(new XEvent(region.maxX + 1, false, region));
        }

        events.sort((a, b) -> {
            int result = Integer.compare(a.x, b.x);
            return result != 0 ? result : Boolean.compare(b.isStart, a.isStart);
        });

        var totalVolume = BigInteger.ZERO;
        var activeRegions = new ArrayList<RegionBounds>();
        var lastX = Integer.MIN_VALUE;

        for (var event : events) {
            if (!activeRegions.isEmpty() && event.x > lastX) {
                var sliceWidth = event.x - lastX;
                var sliceArea = calculateYZUnionArea(activeRegions);
                totalVolume = totalVolume.add(BigInteger.valueOf(sliceWidth).multiply(sliceArea));
            }

            if (event.isStart) {
                activeRegions.add(event.region);
            } else {
                activeRegions.remove(event.region);
            }
            lastX = event.x;
        }

        return totalVolume;
    }

    private record XEvent(int x, boolean isStart, RegionBounds region) {
    }

    private BigInteger calculateYZUnionArea(List<RegionBounds> regions) {
        if (regions.isEmpty()) return BigInteger.ZERO;

        var intervals = new ArrayList<YInterval>();
        for (var region : regions) {
            intervals.add(new YInterval(region.minY, region.maxY, region.minZ, region.maxZ));
        }

        return calculateYZUnion(intervals);
    }

    private record YInterval(int minY, int maxY, int minZ, int maxZ) {
    }

    private BigInteger calculateYZUnion(List<YInterval> intervals) {
        if (intervals.isEmpty()) return BigInteger.ZERO;
        var sortedY = intervals.stream()
                .sorted(Comparator.comparingInt(a -> a.minY))
                .toList();

        var totalArea = BigInteger.ZERO;
        for (var interval : sortedY) {
            var area = BigInteger.valueOf((long) (interval.maxY - interval.minY + 1) *
                    (interval.maxZ - interval.minZ + 1));
            totalArea = totalArea.add(area);
        }

        return totalArea;
    }

    private RegionBounds getRegionBounds(Region region) {
        var min = region.getMinimumPoint();
        var max = region.getMaximumPoint();
        return new RegionBounds(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }

    private RegionBounds getRegionBounds(ProtectedRegion region) {
        var min = region.getMinimumPoint();
        var max = region.getMaximumPoint();
        return new RegionBounds(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
    }

    private double calculateSafeCost(BigInteger blocks, double pricePerBlock) {
        if (blocks.equals(BigInteger.ZERO) || pricePerBlock <= 0) return 0.0;
        if (blocks.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) return Double.MAX_VALUE;
        return blocks.doubleValue() * pricePerBlock;
    }

    private @Nullable Region getPlayerSelection(Player player) throws IncompleteRegionException {
        var worldEdit = WorldEditPlugin.getPlugin(WorldEditPlugin.class);
        var session = worldEdit.getSession(player);
        var world = BukkitAdapter.adapt(player.getWorld());
        var selector = session.getRegionSelector(world);
        selector.learnChanges();
        return session.getSelection(world);
    }

    private boolean selectionsMatch(Region original, Region current) {
        return original.getMinimumPoint().equals(current.getMinimumPoint()) &&
                original.getMaximumPoint().equals(current.getMaximumPoint());
    }

    private void createPendingAction(Player player, String command, String[] args, CostInfo costInfo, Region selection) {
        cancelPendingAction(player);

        var timeout = config.getConfirmationTimeoutSeconds() * 20L;
        var timeoutTask = Bukkit.getScheduler()
                .runTaskLater(plugin, () -> pendingActions.remove(player.getUniqueId()), timeout);

        var regionName = args.length > 1 ? args[1] : "unknown";
        var action = new PendingAction(command, args, costInfo, regionName, selection, timeoutTask, false);
        pendingActions.put(player.getUniqueId(), action);

        sendConfirmationMessage(player, costInfo);
    }

    private void sendConfirmationMessage(Player player, CostInfo costInfo) {
        String totalCostFormatted = vault.formatCurrency(costInfo.totalCost());
        String horizontalCostFormatted = vault.formatCurrency(costInfo.horizontalCost());
        String verticalCostFormatted = vault.formatCurrency(costInfo.verticalCost());
        String balance = vault.formatCurrency(vault.getBalance(player));
        String timeout = String.valueOf(config.getConfirmationTimeoutSeconds());

        player.sendMessage(messages.confirmationRequired(
                totalCostFormatted, horizontalCostFormatted, verticalCostFormatted,
                costInfo.horizontalBlocks(), costInfo.verticalBlocks(), balance, timeout
        ));
    }
}