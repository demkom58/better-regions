package io.invokegs.betterregions.integration;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.logging.Level;

/**
 * Integration with Vault for economy and permission services.
 * Provides safe access to economy operations and permission checks.
 */
public final class VaultIntegration {

    private final Plugin plugin;
    private @Nullable Economy economy;
    private @Nullable Permission permission;
    private boolean vaultAvailable;

    public VaultIntegration(Plugin plugin) {
        this.plugin = plugin;
        this.vaultAvailable = false;
    }

    /**
     * Sets up Vault integration. Should be called during plugin enable.
     * @return true if Vault was successfully initialized
     */
    public boolean setup() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            plugin.getLogger().info("Vault not found - economy features will be disabled");
            return false;
        }

        boolean economySetup = setupEconomy();
        boolean permissionSetup = setupPermissions();

        if (!economySetup) {
            plugin.getLogger().warning("No economy provider found - economy features will be disabled");
        }

        if (!permissionSetup) {
            plugin.getLogger().warning("No permission provider found - permission-based features may not work");
        }

        this.vaultAvailable = true;
        return true;
    }

    private boolean setupEconomy() {
        var rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        this.economy = rsp.getProvider();
        plugin.getLogger().info("Economy provider found: " + economy.getName());
        return true;
    }

    private boolean setupPermissions() {
        var rsp = plugin.getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) {
            return false;
        }
        this.permission = rsp.getProvider();
        plugin.getLogger().info("Permission provider found: " + permission.getName());
        return true;
    }

    /**
     * Checks if Vault is available and working.
     */
    public boolean isVaultAvailable() {
        return vaultAvailable;
    }

    /**
     * Checks if economy integration is available.
     */
    public boolean isEconomyAvailable() {
        return economy != null;
    }

    /**
     * Checks if permission integration is available.
     */
    public boolean isPermissionAvailable() {
        return permission != null;
    }

    /**
     * Gets the player's current balance.
     * @param player the player to check
     * @return the balance, or 0.0 if economy is not available
     */
    public double getBalance(OfflinePlayer player) {
        if (economy == null) {
            return 0.0;
        }

        try {
            return economy.getBalance(player);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get balance for " + player.getName(), e);
            return 0.0;
        }
    }

    /**
     * Checks if the player has sufficient funds.
     * @param player the player to check
     * @param amount the required amount
     * @return true if the player has enough money
     */
    public boolean hasBalance(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) {
            return true;
        }

        try {
            return economy.has(player, amount);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check balance for " + player.getName(), e);
            return false;
        }
    }

    /**
     * Withdraws money from the player's account.
     * @param player the player to charge
     * @param amount the amount to withdraw
     * @return true if the transaction was successful
     */
    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null || amount <= 0) {
            return true;
        }

        try {
            var response = economy.withdrawPlayer(player, amount);
            if (response.transactionSuccess()) {
                return true;
            } else {
                plugin.getLogger().warning("Failed to withdraw " + amount + " from " + player.getName() + ": " + response.errorMessage);
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error withdrawing money from " + player.getName(), e);
            return false;
        }
    }

    /**
     * Formats a currency amount using the economy provider.
     * @param amount the amount to format
     * @return formatted currency string
     */
    public String formatCurrency(double amount) {
        if (economy == null) {
            return String.format("%.2f", amount);
        }

        try {
            return economy.format(amount);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to format currency amount: " + amount, e);
            return String.format("%.2f", amount);
        }
    }

    /**
     * Checks if a player has a specific permission.
     * @param player the player to check
     * @param permission the permission node
     * @return true if the player has the permission
     */
    public boolean hasPermission(OfflinePlayer player, String permission) {
        if (this.permission == null) {
            if (player.isOnline() && player.getPlayer() != null) {
                return player.getPlayer().hasPermission(permission);
            }
            return false;
        }

        try {
            return this.permission.playerHas(null, player, permission);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check permission " + permission + " for " + player.getName(), e);
            return false;
        }
    }

    /**
     * Gets the economy provider instance.
     * @return the economy provider, or null if not available
     */
    public @Nullable Economy getEconomy() {
        return economy;
    }

    /**
     * Gets the permission provider instance.
     * @return the permission provider, or null if not available
     */
    public @Nullable Permission getPermission() {
        return permission;
    }
}