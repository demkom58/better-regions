package io.invokegs.betterregions.features;

import com.sk89q.worldguard.protection.flags.Flag;
import io.invokegs.betterregions.config.Configuration;
import io.invokegs.betterregions.config.Messages;
import io.invokegs.betterregions.integration.WorldGuardIntegration;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Feature that automatically applies configured flags to newly created regions.
 * Uses WorldGuard's built-in flag unmarshaling to handle value parsing.
 */
public final class AutoFlagsFeature {

    private final Configuration config;
    private final Messages messages;
    private final WorldGuardIntegration worldGuard;
    private final Logger logger;

    public AutoFlagsFeature(Configuration config, Messages messages, WorldGuardIntegration worldGuard, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.worldGuard = worldGuard;
        this.logger = logger;
    }

    /**
     * Applies auto flags to a region if the feature is enabled.
     * @param player the player who created the region
     * @param world the world containing the region
     * @param regionName the name of the region
     */
    public void applyAutoFlags(Player player, World world, String regionName) {
        var autoFlags = config.getAutoFlags();
        if (autoFlags.isEmpty()) return;

        var region = worldGuard.getRegion(world, regionName);
        if (region == null) return;

        var showMessages = config.showAutoFlagMessages();
        int appliedCount = 0;

        for (var entry : autoFlags.entrySet()) {
            try {
                var flag = entry.getKey();
                var value = entry.getValue();

                var unmarshaled = flag.unmarshal(value);
                setFlagDirect(region, flag, unmarshaled);

                appliedCount++;
            } catch (Exception e) {
                logger.log(Level.WARNING,
                        "Failed to set auto flag " + entry.getKey().getName() + " on region " + regionName +
                                " with value '" + entry.getValue() + "'", e);
            }
        }

        if (appliedCount > 0 && showMessages) {
            player.sendMessage(messages.autoFlagsApplied(regionName));
        }
    }

    /**
     * Sets a flag directly on the region.
     * @param region the region
     * @param flag the flag
     * @param value the already-parsed value
     */
    @SuppressWarnings("unchecked")
    private <T> void setFlagDirect(com.sk89q.worldguard.protection.regions.ProtectedRegion region, Flag<T> flag, Object value) {
        region.setFlag(flag, (T) value);
    }
}