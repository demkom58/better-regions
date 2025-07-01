package io.invokegs.betterregions.features;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.math.BlockVector3;
import io.invokegs.betterregions.config.Configuration;
import io.invokegs.betterregions.config.Messages;
import org.bukkit.entity.Player;

/**
 * Feature that automatically expands player selections to full vertical range.
 * Useful for creating regions that extend from bedrock to build limit.
 */
public final class VerticalExpandFeature {

    private final Configuration config;
    private final Messages messages;

    public VerticalExpandFeature(Configuration config, Messages messages) {
        this.config = config;
        this.messages = messages;
    }

    /**
     * Attempts to expand a player's selection vertically.
     * @param player the player whose selection to expand
     * @return true if the expansion was successful
     */
    public boolean expandVertically(Player player) {
        if (!config.isVerticalExpandEnabled()) {
            return false;
        }

        try {
            var worldEdit = WorldEditPlugin.getPlugin(WorldEditPlugin.class);
            var session = worldEdit.getSession(player);
            var world = BukkitAdapter.adapt(player.getWorld());

            var region = session.getSelection(world);
            var minY = world.getMinY();
            var maxY = world.getMaxY();

            region.expand(
                    BlockVector3.at(0, maxY - region.getMaximumPoint().y(), 0),
                    BlockVector3.at(0, minY - region.getMinimumPoint().y(), 0)
            );

            session.getRegionSelector(world).learnChanges();
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Expands a player's selection and sends a confirmation message.
     * @param player the player whose selection to expand
     * @return true if the expansion was successful and confirmed
     */
    public boolean expandVerticallyWithMessage(Player player) {
        if (expandVertically(player)) {
            player.sendMessage(messages.verticalExpansionApplied());
            return true;
        }
        return false;
    }
}