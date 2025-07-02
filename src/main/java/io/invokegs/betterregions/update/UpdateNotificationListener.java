package io.invokegs.betterregions.update;

import io.invokegs.betterregions.config.Configuration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Listener that notifies administrators about available updates when they join.
 */
public final class UpdateNotificationListener implements Listener {

    private final Plugin plugin;
    private final UpdateChecker updateChecker;
    private final Configuration config;

    public UpdateNotificationListener(Plugin plugin, UpdateChecker updateChecker, Configuration config) {
        this.plugin = plugin;
        this.updateChecker = updateChecker;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();

        if (!player.hasPermission("betterregions.admin")) return;
        if (!config.isCheckUpdatesEnabled()) return;

        if (!updateChecker.shouldCheck(60)) {
            if (updateChecker.isUpdateAvailable()) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> updateChecker.notifySender(player), 40L);
            }
            return;
        }

        updateChecker.checkForUpdates().thenRun(() -> {
            if (updateChecker.isUpdateAvailable()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> updateChecker.notifySender(player));
            }
        });
    }
}