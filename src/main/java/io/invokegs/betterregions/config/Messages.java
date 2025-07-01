package io.invokegs.betterregions.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public final class Messages {
    private final Map<String, Component> cachedMessages = new HashMap<>();
    private final Plugin plugin;
    private final Configuration config;
    private final File messagesFile;
    private final MiniMessage miniMessage;
    private YamlConfiguration messages;
    private Component prefix;

    public Messages(Plugin plugin, Configuration config) {
        this.plugin = plugin;
        this.config = config;
        this.messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        this.miniMessage = MiniMessage.miniMessage();

        saveDefaultMessages();
        load();
    }

    /**
     * Reloads messages from disk.
     */
    public void reload() {
        load();
    }

    private void saveDefaultMessages() {
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
    }

    private void load() {
        try {
            this.cachedMessages.clear();
            this.messages = YamlConfiguration.loadConfiguration(messagesFile);
            this.prefix = parseMessage("prefix");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load messages", e);
        }
    }

    /**
     * Gets a message component with the configured prefix.
     */
    public Component getMessage(String key, TagResolver... resolvers) {
        if (resolvers.length == 0) {
            return cachedMessages.computeIfAbsent(key, k -> prefix.append(parseMessage(key)));
        }
        return prefix.append(parseMessage(key, resolvers));
    }

    /**
     * Gets a message component without prefix.
     */
    public Component getMessageWithoutPrefix(String key, TagResolver... resolvers) {
        return parseMessage(key, resolvers);
    }

    private Component parseMessage(String key, TagResolver... resolvers) {
        var messageText = messages.getString(key, "<red>Missing message: " + key);
        return miniMessage.deserialize(messageText, TagResolver.resolver(resolvers));
    }

    public Component noPermission() {
        return getMessage("no-permission");
    }

    public Component pluginReloaded() {
        return getMessage("plugin-reloaded");
    }

    public Component claimUsage() {
        return getMessage("commands.claim-usage");
    }

    public Component redefineUsage() {
        return getMessage("commands.redefine-usage");
    }

    public Component invalidRegionName(String regionName) {
        return getMessage("regions.invalid-name", Placeholder.unparsed("region", regionName));
    }

    public Component regionAlreadyExists(String regionName) {
        return getMessage("regions.already-exists", Placeholder.unparsed("region", regionName));
    }

    public Component regionNotExists(String regionName) {
        return getMessage("regions.not-exists", Placeholder.unparsed("region", regionName));
    }

    public Component tooManyRegions() {
        return getMessage("regions.too-many");
    }

    public Component maxVolumeConfig() {
        return getMessage("regions.max-volume-config");
    }

    public Component regionTooLarge(long currentVolume, long maxVolume) {
        return getMessage("regions.too-large",
                Placeholder.unparsed("current", String.valueOf(currentVolume)),
                Placeholder.unparsed("max", String.valueOf(maxVolume))
        );
    }

    public Component regionOverlaps() {
        return getMessage("regions.overlaps");
    }

    public Component claimOnlyInsideExisting() {
        return getMessage("regions.claim-only-inside-existing");
    }

    public Component onlyCuboidSelection() {
        return getMessage("selection.only-cuboid");
    }

    public Component noSelection() {
        return getMessage("selection.none");
    }

    public Component regionManagementUnavailable() {
        return getMessage("regions.management-unavailable");
    }

    public Component claimSuccess(String regionName) {
        return getMessage("success.claim", Placeholder.unparsed("region", regionName));
    }

    public Component redefineSuccess(String regionName) {
        return getMessage("success.redefine", Placeholder.unparsed("region", regionName));
    }

    public Component verticalExpansionApplied() {
        return getMessage("vertical-expansion-applied");
    }

    public Component regionTooSmall(BigInteger currentX, BigInteger currentY, BigInteger currentZ,
                                    BigInteger minX, BigInteger minY, BigInteger minZ) {
        return getMessage("limits.region-too-small",
                Placeholder.unparsed("current_x", currentX.toString()),
                Placeholder.unparsed("current_y", currentY.toString()),
                Placeholder.unparsed("current_z", currentZ.toString()),
                Placeholder.unparsed("min_x", minX.toString()),
                Placeholder.unparsed("min_y", minY.toString()),
                Placeholder.unparsed("min_z", minZ.toString())
        );
    }

    public Component economyNotAvailable() {
        return getMessage("economy.not-available");
    }

    public Component insufficientFunds(String required, String balance) {
        return getMessage("economy.insufficient-funds",
                Placeholder.unparsed("required", required),
                Placeholder.unparsed("balance", balance)
        );
    }

    public Component insufficientFundsDetailed(String totalCost, String horizontalCost, String verticalCost,
                                               BigInteger horizontalBlocks, BigInteger verticalBlocks, String balance) {
        return getMessage("economy.insufficient-funds-detailed",
                Placeholder.unparsed("total_cost", totalCost),
                Placeholder.unparsed("horizontal_cost", horizontalCost),
                Placeholder.unparsed("vertical_cost", verticalCost),
                Placeholder.unparsed("horizontal_blocks", horizontalBlocks.toString()),
                Placeholder.unparsed("vertical_blocks", verticalBlocks.toString()),
                Placeholder.unparsed("balance", balance)
        );
    }

    public Component paymentFailedAfterCommand(String required, String balance) {
        return getMessage("economy.payment-failed-after-command",
                Placeholder.unparsed("required", required),
                Placeholder.unparsed("balance", balance)
        );
    }

    public Component confirmationRequired(String totalCost, String horizontalCost, String verticalCost,
                                          BigInteger horizontalBlocks, BigInteger verticalBlocks, String balance,
                                          String timeout) {
        return getMessage("economy.confirmation-required",
                Placeholder.unparsed("total_cost", totalCost),
                Placeholder.unparsed("horizontal_cost", horizontalCost),
                Placeholder.unparsed("vertical_cost", verticalCost),
                Placeholder.unparsed("horizontal_blocks", horizontalBlocks.toString()),
                Placeholder.unparsed("vertical_blocks", verticalBlocks.toString()),
                Placeholder.unparsed("balance", balance),
                Placeholder.unparsed("timeout", timeout)
        );
    }

    public Component noPendingAction() {
        return getMessage("economy.no-pending-action");
    }

    public Component actionCancelled() {
        return getMessage("economy.action-cancelled");
    }

    public Component selectionChanged() {
        return getMessage("economy.selection-changed");
    }

    public Component selectionLost() {
        return getMessage("economy.selection-lost");
    }

    public Component paymentProcessed(String totalAmount, String horizontalAmount, String verticalAmount,
                                      BigInteger horizontalBlocks, BigInteger verticalBlocks) {
        return getMessage("economy.payment-processed",
                Placeholder.unparsed("total_amount", totalAmount),
                Placeholder.unparsed("horizontal_amount", horizontalAmount),
                Placeholder.unparsed("vertical_amount", verticalAmount),
                Placeholder.unparsed("horizontal_blocks", horizontalBlocks.toString()),
                Placeholder.unparsed("vertical_blocks", verticalBlocks.toString())
        );
    }

    public Component autoFlagsApplied(String regionName) {
        return getMessage("auto-flags.applied",
                Placeholder.unparsed("region", regionName)
        );
    }

    public Component commandRestrictedInRegion() {
        return getMessage("protection.command-restricted");
    }
}