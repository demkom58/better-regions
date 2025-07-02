package io.invokegs.betterregions;

import io.invokegs.betterregions.commands.BetterRegionsCommand;
import io.invokegs.betterregions.config.Configuration;
import io.invokegs.betterregions.config.Messages;
import io.invokegs.betterregions.economy.EconomyService;
import io.invokegs.betterregions.features.AutoFlagsFeature;
import io.invokegs.betterregions.features.BlockLimitsFeature;
import io.invokegs.betterregions.features.RegionProtectFeature;
import io.invokegs.betterregions.features.VerticalExpandFeature;
import io.invokegs.betterregions.integration.RegionCommandWrapper;
import io.invokegs.betterregions.integration.inject.CommandInjector;
import io.invokegs.betterregions.integration.VaultIntegration;
import io.invokegs.betterregions.integration.WorldGuardIntegration;
import io.invokegs.betterregions.update.UpdateChecker;
import io.invokegs.betterregions.update.UpdateNotificationListener;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class BetterRegionsPlugin extends JavaPlugin {

    private final Configuration configuration = new Configuration(this);
    private final Messages messages = new Messages(this, configuration);
    private final WorldGuardIntegration worldGuardIntegration = new WorldGuardIntegration();
    private final VaultIntegration vaultIntegration = new VaultIntegration(this);
    private final EconomyService economyService = new EconomyService(vaultIntegration, configuration, messages, this);
    private final UpdateChecker updateChecker = new UpdateChecker(this);

    private final VerticalExpandFeature verticalExpandFeature
            = new VerticalExpandFeature(configuration, messages);
    private final BlockLimitsFeature blockLimitsFeature
            = new BlockLimitsFeature(configuration, messages);

    private final RegionProtectFeature regionProtectFeature
            = new RegionProtectFeature(this, configuration, messages, worldGuardIntegration);
    private final AutoFlagsFeature autoFlagsFeature
            = new AutoFlagsFeature(configuration, messages, worldGuardIntegration, getLogger());

    private final CommandInjector commandInjector = new CommandInjector(this, "region",
            templateCommand -> new RegionCommandWrapper(this,
                    templateCommand, configuration, messages, economyService, worldGuardIntegration,
                    verticalExpandFeature, blockLimitsFeature, autoFlagsFeature)
    );

    @Override
    public void onEnable() {
        try {
            setupIntegrations();
            if (!injectCommands()) return;

            registerListeners();
            registerCommands();
            setupUpdateChecker();

            getLogger().info("BetterRegions enabled successfully!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable BetterRegions", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        cleanup();
        getLogger().info("BetterRegions disabled.");
    }

    public void reload() {
        try {
            configuration.reload();
            messages.reload();
            economyService.reload();
            getLogger().info("BetterRegions reloaded successfully!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to reload BetterRegions", e);
        }
    }

    private void setupIntegrations() {
        worldGuardIntegration.setup();
        vaultIntegration.setup();
        economyService.setup();
    }

    private boolean injectCommands() {
        if (!commandInjector.inject()) {
            getLogger().severe("Failed to inject WorldGuard command replacement!");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
        return true;
    }

    private void registerListeners() {
        regionProtectFeature.enable();

        if (configuration.isCheckUpdatesEnabled()) {
            var updateListener = new UpdateNotificationListener(this, updateChecker, configuration);
            getServer().getPluginManager().registerEvents(updateListener, this);
        }
    }

    private void registerCommands() {
        var adminCommand = getCommand("betterregions");
        if (adminCommand != null) {
            var executor = new BetterRegionsCommand(this, messages, updateChecker);
            adminCommand.setExecutor(executor);
            adminCommand.setTabCompleter(executor);
        }
    }

    private void setupUpdateChecker() {
        if (configuration.isCheckUpdatesEnabled()) {
            updateChecker.checkForUpdates(true).exceptionally(throwable -> {
                getLogger().log(Level.WARNING, "Failed to check for updates on startup", throwable);
                return null;
            });
        }
    }

    private void cleanup() {
        economyService.cleanup();
        regionProtectFeature.disable();
    }

    public Configuration config() {
        return configuration;
    }

    public Messages messages() {
        return messages;
    }

    public EconomyService economy() {
        return economyService;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }
}