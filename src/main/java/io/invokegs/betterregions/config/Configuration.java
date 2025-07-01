package io.invokegs.betterregions.config;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.Flags;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public final class Configuration {
    /**
     * Represents pricing information for a permission tier.
     */
    public record PricingTier(double horizontal, double vertical) {}

    /**
     * Explosion protection modes.
     */
    public enum ExplosionMode {
        /**
         * No protection - default WorldGuard behavior
         */
        UNTOUCHED,
        /**
         * Prevent block damage but allow entity damage
         */
        ENTITY_DAMAGE_ONLY,
        /**
         * Only users who can build in region can cause explosions
         */
        BUILDER_ONLY,
        /**
         * Only region owners/members can cause explosions
         */
        MEMBER_ONLY,
        /**
         * No explosions at all in regions
         */
        NO_EXPLOSIONS
    }

    private final Plugin plugin;
    private final File configFile;
    private YamlConfiguration config;

    private boolean verticalExpandEnabled;
    private BigInteger minHorizontal = BigInteger.valueOf(1);
    private BigInteger minVertical = BigInteger.valueOf(1);
    private boolean economyEnabled;
    private double defaultHorizontalPricePerBlock;
    private double defaultVerticalPricePerBlock;
    private int confirmationTimeoutSeconds;
    private final Map<String, PricingTier> pricePermissions = new HashMap<>();
    private boolean showAutoFlagMessages;
    private final Map<Flag<?>, String> autoFlags = new HashMap<>();
    private boolean fireSpreadProtection;
    private boolean blockBurnProtection;
    private ExplosionMode explosionMode;
    private Set<String> restrictedCommands = new HashSet<>();

    public Configuration(Plugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");

        saveDefaultConfig();
        load();
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reload() {
        load();
    }

    private void saveDefaultConfig() {
        if (!configFile.exists()) {
            plugin.saveResource("config.yml", false);
        }
    }

    private void load() {
        try {
            this.config = YamlConfiguration.loadConfiguration(configFile);
            loadAllSettings();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load configuration", e);
            setDefaults();
        }
    }

    private void loadAllSettings() {
        loadVerticalExpandSettings();
        loadBlockLimitSettings();
        loadEconomySettings();
        loadAutoFlagSettings();
        loadRegionProtectionSettings();
    }

    private void loadVerticalExpandSettings() {
        this.verticalExpandEnabled = config.getBoolean("features.vertical-expand.enabled", false);
    }

    private void loadBlockLimitSettings() {
        var section = config.getConfigurationSection("features.block-limits");
        this.minHorizontal = getBigInteger(section, "min-horizontal", BigInteger.valueOf(20));
        this.minVertical = getBigInteger(section, "min-vertical", BigInteger.valueOf(20));
    }

    private void loadEconomySettings() {
        var section = config.getConfigurationSection("economy");
        if (section == null) {
            this.economyEnabled = false;
            return;
        }

        this.economyEnabled = section.getBoolean("enabled", false);
        this.defaultHorizontalPricePerBlock = section.getDouble("horizontal-price-per-block", 0.1);
        this.defaultVerticalPricePerBlock = section.getDouble("vertical-price-per-block", 0.00005);
        this.confirmationTimeoutSeconds = section.getInt("confirmation-timeout-seconds", 120);

        loadPricePermissions(section);
    }

    private void loadPricePermissions(ConfigurationSection section) {
        var priceSection = section.getConfigurationSection("price-permissions");
        if (priceSection == null) return;

        pricePermissions.clear();
        for (var permission : priceSection.getKeys(false)) {
            var permissionSection = priceSection.getConfigurationSection(permission);
            if (permissionSection != null) {
                var horizontal = permissionSection.getDouble("horizontal", defaultHorizontalPricePerBlock);
                var vertical = permissionSection.getDouble("vertical", defaultVerticalPricePerBlock);
                pricePermissions.put(permission, new PricingTier(horizontal, vertical));
            }
        }
    }

    private void loadAutoFlagSettings() {
        var section = config.getConfigurationSection("features.auto-flags");
        if (section == null) return;

        this.showAutoFlagMessages = section.getBoolean("show-messages", false);
        loadAutoFlags(section);
    }

    private void loadAutoFlags(ConfigurationSection section) {
        var flagsSection = section.getConfigurationSection("flags");
        if (flagsSection == null) return;

        autoFlags.clear();
        var flagRegistry = WorldGuard.getInstance().getFlagRegistry();

        for (var flagName : flagsSection.getKeys(false)) {
            var flag = Flags.fuzzyMatchFlag(flagRegistry, flagName);
            if (flag != null) {
                var value = flagsSection.getString(flagName);
                if (value != null) {
                    autoFlags.put(flag, value);
                }
            }
        }
    }

    private void loadRegionProtectionSettings() {
        var section = config.getConfigurationSection("features.region-protection");
        if (section == null) {
            setDefaultProtectionSettings();
            return;
        }

        this.fireSpreadProtection = section.getBoolean("fire-spread", false);
        this.blockBurnProtection = section.getBoolean("block-burn", false);

        var explosionModeString = section.getString("explosion-mode", "DISABLED").toUpperCase();
        try {
            this.explosionMode = ExplosionMode.valueOf(explosionModeString);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid explosion mode: " + explosionModeString + ". Using DISABLED.");
            this.explosionMode = ExplosionMode.UNTOUCHED;
        }

        var commands = section.getStringList("restrict-commands.commands");
        this.restrictedCommands = new HashSet<>(commands);
    }

    private void setDefaultProtectionSettings() {
        this.fireSpreadProtection = false;
        this.blockBurnProtection = false;
        this.explosionMode = ExplosionMode.UNTOUCHED;
        this.restrictedCommands = new HashSet<>();
    }

    private void setDefaults() {
        this.verticalExpandEnabled = false;
        this.economyEnabled = false;
        this.defaultHorizontalPricePerBlock = 0.1;
        this.defaultVerticalPricePerBlock = 0.00005;
        setDefaultProtectionSettings();
    }

    private BigInteger getBigInteger(ConfigurationSection section, String key, BigInteger defaultValue) {
        var value = section.getString(key);
        if (value == null) return defaultValue;

        try {
            return new BigInteger(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean isVerticalExpandEnabled() { return verticalExpandEnabled; }
    public BigInteger getMinHorizontal() { return minHorizontal; }
    public BigInteger getMinVertical() { return minVertical; }
    public boolean isEconomyEnabled() { return economyEnabled; }
    public double getDefaultHorizontalPricePerBlock() { return defaultHorizontalPricePerBlock; }
    public double getDefaultVerticalPricePerBlock() { return defaultVerticalPricePerBlock; }
    public int getConfirmationTimeoutSeconds() { return confirmationTimeoutSeconds; }
    public Map<String, PricingTier> getPricePermissions() { return Map.copyOf(pricePermissions); }
    public boolean showAutoFlagMessages() { return showAutoFlagMessages; }
    public Map<Flag<?>, String> getAutoFlags() { return Map.copyOf(autoFlags); }
    public boolean isFireSpreadProtection() { return fireSpreadProtection; }
    public boolean isBlockBurnProtection() { return blockBurnProtection; }
    public ExplosionMode getExplosionMode() { return explosionMode; }
    public Set<String> getRestrictedCommands() { return Set.copyOf(restrictedCommands); }
}