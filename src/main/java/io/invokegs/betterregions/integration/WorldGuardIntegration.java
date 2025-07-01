package io.invokegs.betterregions.integration;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Integration with WorldGuard for region management and queries.
 * Provides convenient access to WorldGuard functionality.
 */
public final class WorldGuardIntegration {
    private @Nullable WorldGuard worldGuard;
    private @Nullable WorldGuardPlugin worldGuardPlugin;
    private @Nullable RegionContainer regionContainer;
    private @Nullable RegionQuery regionQuery;

    public void setup() {
        this.worldGuard = WorldGuard.getInstance();
        this.worldGuardPlugin = WorldGuardPlugin.inst();
        this.regionContainer = worldGuard.getPlatform().getRegionContainer();
        this.regionQuery = regionContainer.createQuery();
    }

    /**
     * Wraps a Bukkit player as a WorldGuard LocalPlayer.
     * @param player the Bukkit player
     * @return the wrapped LocalPlayer, or null if WorldGuard is not available
     */
    public @Nullable LocalPlayer wrapPlayer(Player player) {
        if (worldGuardPlugin == null) return null;
        return worldGuardPlugin.wrapPlayer(player);
    }

    /**
     * Gets the RegionManager for a world.
     * @param world the world
     * @return the RegionManager, or null if not available
     */
    public @Nullable RegionManager getRegionManager(World world) {
        if (regionContainer == null) return null;
        return regionContainer.get(BukkitAdapter.adapt(world));
    }

    /**
     * Gets a region by name in a world.
     * @param world the world
     * @param regionName the region name
     * @return the ProtectedRegion, or null if not found
     */
    public @Nullable ProtectedRegion getRegion(World world, String regionName) {
        var manager = getRegionManager(world);
        if (manager == null) return null;

        return manager.getRegion(regionName);
    }

    /**
     * Gets all regions at a location.
     * @param location the location to check
     * @return the applicable regions, or null if WorldGuard is not available
     */
    public @Nullable ApplicableRegionSet getRegionsAt(Location location) {
        if (regionQuery == null) return null;
        return regionQuery.getApplicableRegions(BukkitAdapter.adapt(location));
    }

    /**
     * Checks if a player can build at a location.
     * @param player the player
     * @param location the location
     * @return true if the player can build
     */
    public boolean canBuild(Player player, Location location) {
        if (regionQuery == null) return true;

        var localPlayer = wrapPlayer(player);
        if (localPlayer == null) return true;

        return regionQuery.testBuild(BukkitAdapter.adapt(location), localPlayer);
    }

    /**
     * Checks if a player has bypass permissions.
     * @param player the player to check
     * @return true if the player can bypass WorldGuard protection
     */
    public boolean canBypass(Player player) {
        if (worldGuard == null) return false;

        var localPlayer = wrapPlayer(player);
        if (localPlayer == null) return false;

        return worldGuard.getPlatform().getSessionManager()
                .hasBypass(localPlayer, BukkitAdapter.adapt(player.getWorld()));
    }
}