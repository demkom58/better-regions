package io.invokegs.betterregions.features;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import io.invokegs.betterregions.config.Configuration;
import io.invokegs.betterregions.config.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.math.BigInteger;

/**
 * Feature that enforces block limits on region creation and modification.
 * Supports permission-based limits and minimum size requirements.
 */
public final class BlockLimitsFeature {
    private final Configuration config;
    private final Messages messages;

    public BlockLimitsFeature(Configuration config, Messages messages) {
        this.config = config;
        this.messages = messages;
    }

    /**
     * Result of validating a player's selection against block limits.
     */
    public sealed interface ValidationResult permits ValidationResult.Allow, ValidationResult.Deny {
        record Allow() implements ValidationResult {}
        record Deny(Component reason) implements ValidationResult {}
    }

    /**
     * Validates a player's current selection against configured block limits.
     * @param player the player to validate
     * @return the validation result
     */
    public ValidationResult validateSelection(Player player) {
        if (config.getMinHorizontal().compareTo(BigInteger.ONE) <= 0
                && config.getMinVertical().compareTo(BigInteger.ONE) <= 0) {
            return new ValidationResult.Allow();
        }

        if (player.hasPermission("betterregions.limits.bypass")) {
            return new ValidationResult.Allow();
        }

        try {
            var worldEdit = WorldEditPlugin.getPlugin(WorldEditPlugin.class);
            var session = worldEdit.getSession(player);
            var world = BukkitAdapter.adapt(player.getWorld());
            var region = session.getSelection(world);

            var min = region.getMinimumPoint();
            var max = region.getMaximumPoint();

            var xSize = BigInteger.valueOf(max.x() - min.x() + 1);
            var ySize = BigInteger.valueOf(max.y() - min.y() + 1);
            var zSize = BigInteger.valueOf(max.z() - min.z() + 1);
            var minHorizontal = xSize.min(zSize);

            if (minHorizontal.compareTo(config.getMinHorizontal()) < 0
                    || ySize.compareTo(config.getMinVertical()) < 0) {
                return new ValidationResult.Deny(
                        messages.regionTooSmall(
                                xSize, ySize, zSize,
                                config.getMinHorizontal(),
                                config.getMinVertical(),
                                config.getMinHorizontal()
                        )
                );
            }
        } catch (Exception ignored) {}

        return new ValidationResult.Allow();
    }
}