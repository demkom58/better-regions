package io.invokegs.betterregions.features;

import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import io.invokegs.betterregions.config.Configuration;
import io.invokegs.betterregions.config.Messages;
import io.invokegs.betterregions.integration.WorldGuardIntegration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RegionProtectFeature implements Listener {

    private final Plugin plugin;
    private final Configuration config;
    private final Messages messages;
    private final WorldGuardIntegration worldGuard;
    private final NamespacedKey ownerKey;
    private final ConcurrentHashMap<Location, SkullPlacement> recentSkullPlacements = new ConcurrentHashMap<>();

    private record SkullPlacement(UUID playerUuid, long timestamp) {}

    public RegionProtectFeature(Plugin plugin, Configuration config, Messages messages, WorldGuardIntegration worldGuard) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.worldGuard = worldGuard;
        this.ownerKey = new NamespacedKey(plugin, "explosion_owner");
    }

    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupOldSkullPlacements, 6000L, 6000L);
    }

    public void disable() {
        BlockFromToEvent.getHandlerList().unregister(this);
        BlockSpreadEvent.getHandlerList().unregister(this);
        BlockBurnEvent.getHandlerList().unregister(this);
        EntityExplodeEvent.getHandlerList().unregister(this);
        BlockExplodeEvent.getHandlerList().unregister(this);
        EntityDamageEvent.getHandlerList().unregister(this);
        PlayerCommandPreprocessEvent.getHandlerList().unregister(this);
        BlockPlaceEvent.getHandlerList().unregister(this);
        BlockDispenseEvent.getHandlerList().unregister(this);
        TNTPrimeEvent.getHandlerList().unregister(this);
        ProjectileLaunchEvent.getHandlerList().unregister(this);
        recentSkullPlacements.clear();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFireSpread(BlockSpreadEvent event) {
        if (!config.isFireSpreadProtection()) {
            return;
        }

        if (event.getNewState().getType() != Material.FIRE) {
            return;
        }

        var from = event.getSource().getLocation();
        var to = event.getBlock().getLocation();
        var fromRegions = worldGuard.getRegionsAt(from);
        var toRegions = worldGuard.getRegionsAt(to);

        if (fromRegions == null || toRegions == null) {
            return;
        }

        var fromRegionSet = fromRegions.getRegions();
        var toRegionSet = toRegions.getRegions();

        if ((fromRegionSet.isEmpty() && !toRegionSet.isEmpty()) ||
                (!fromRegionSet.isEmpty() && !toRegionSet.isEmpty() && !fromRegionSet.equals(toRegionSet)) ||
                (!fromRegionSet.isEmpty() && toRegionSet.isEmpty())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (!config.isBlockBurnProtection()) {
            return;
        }

        var location = event.getBlock().getLocation();
        var regions = worldGuard.getRegionsAt(location);

        if (regions != null && !regions.getRegions().isEmpty()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTNTPrime(TNTPrimeEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            var tntEntities = event.getBlock().getLocation().getWorld().getNearbyEntities(event.getBlock().getLocation(), 1, 1, 1).stream()
                    .filter(e -> e instanceof TNTPrimed)
                    .map(e -> (TNTPrimed) e)
                    .filter(tnt -> !hasOwner(tnt))
                    .toList();

            if (tntEntities.isEmpty()) return;
            var tnt = tntEntities.getFirst();

            switch (event.getCause()) {
                case PLAYER -> {
                    var primingEntity = event.getPrimingEntity();
                    if (primingEntity instanceof Player player) {
                        setEntityOwner(tnt, player.getUniqueId());
                    }
                }
                case PROJECTILE -> {
                    var primingEntity = event.getPrimingEntity();
                    if (primingEntity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
                        setEntityOwner(tnt, player.getUniqueId());
                    }
                }
                case EXPLOSION -> handleExplosionChain(tnt, event.getBlock().getLocation());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        if (event.getItem().getType() != Material.TNT) return;

        var dispenserBlock = event.getBlock();
        var dispenserState = (Dispenser) dispenserBlock.getState();
        var ownerString = dispenserState.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);

        if (ownerString != null) {
            try {
                var ownerUuid = UUID.fromString(ownerString);

                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    var tntEntities = dispenserBlock.getLocation().getWorld().getNearbyEntities(dispenserBlock.getLocation(), 3, 3, 3).stream()
                            .filter(e -> e instanceof TNTPrimed)
                            .map(e -> (TNTPrimed) e)
                            .filter(tnt -> !hasOwner(tnt))
                            .toList();

                    for (var tnt : tntEntities) {
                        setEntityOwner(tnt, ownerUuid);
                    }
                }, 1L);

            } catch (IllegalArgumentException ignored) {}
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        var explosionMode = config.getExplosionMode();
        if (explosionMode == Configuration.ExplosionMode.UNTOUCHED) {
            return;
        }

        var entity = event.getEntity();
        var location = event.getLocation();
        var player = getExplosionSourceOffline(entity);

        var blocksToRemove = new ArrayList<Block>();

        for (var block : event.blockList()) {
            var blockRegions = worldGuard.getRegionsAt(block.getLocation());

            if (blockRegions == null || blockRegions.getRegions().isEmpty()) {
                continue;
            }

            boolean blockExplosion = switch (explosionMode) {
                case NO_EXPLOSIONS, ENTITY_DAMAGE_ONLY -> true;
                case BUILDER_ONLY, MEMBER_ONLY ->
                        player == null || !canPlayerExplodeAt(player, block.getLocation(), blockRegions, explosionMode);
                default -> false;
            };

            if (blockExplosion) {
                blocksToRemove.add(block);
            }
        }

        event.blockList().removeAll(blocksToRemove);

        if (explosionMode == Configuration.ExplosionMode.NO_EXPLOSIONS) {
            var centerRegions = worldGuard.getRegionsAt(location);
            if (centerRegions != null && !centerRegions.getRegions().isEmpty()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        var explosionMode = config.getExplosionMode();
        if (explosionMode == Configuration.ExplosionMode.UNTOUCHED) {
            return;
        }

        var location = event.getBlock().getLocation();
        var regions = worldGuard.getRegionsAt(location);

        if (regions == null || regions.getRegions().isEmpty()) {
            return;
        }

        switch (explosionMode) {
            case NO_EXPLOSIONS, BUILDER_ONLY, MEMBER_ONLY -> event.setCancelled(true);
            case ENTITY_DAMAGE_ONLY -> event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        var explosionMode = config.getExplosionMode();
        if (explosionMode != Configuration.ExplosionMode.NO_EXPLOSIONS) {
            return;
        }

        var cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION &&
                cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            return;
        }

        var location = event.getEntity().getLocation();
        var regions = worldGuard.getRegionsAt(location);

        if (regions != null && !regions.getRegions().isEmpty()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        var restrictedCommands = config.getRestrictedCommands();
        if (restrictedCommands.isEmpty()) return;

        var player = event.getPlayer();
        var location = player.getLocation();

        if (worldGuard.canBypass(player) || worldGuard.canBuild(player, location)) {
            return;
        }

        var command = event.getMessage().substring(1).toLowerCase(Locale.ROOT);
        for (var restricted : restrictedCommands) {
            var normalizedRestricted = restricted.toLowerCase(Locale.ROOT);
            if (normalizedRestricted.startsWith("/")) {
                normalizedRestricted = normalizedRestricted.substring(1);
            }

            if (command.startsWith(normalizedRestricted) &&
                    (command.length() == normalizedRestricted.length() ||
                            command.charAt(normalizedRestricted.length()) == ' ')) {
                event.setCancelled(true);
                player.sendMessage(messages.commandRestrictedInRegion());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        var block = event.getBlock();
        var player = event.getPlayer();

        if (block.getType() == Material.DISPENSER) {
            var dispenserState = (Dispenser) block.getState();
            dispenserState.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, player.getUniqueId().toString());
            dispenserState.update();
        }
        else if (block.getType() == Material.WITHER_SKELETON_SKULL || block.getType() == Material.WITHER_SKELETON_WALL_SKULL) {
            recentSkullPlacements.put(block.getLocation(), new SkullPlacement(player.getUniqueId(), System.currentTimeMillis()));
            cleanupOldSkullPlacements();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWitherSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Wither wither)) return;
        if (event.getSpawnReason() != org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.BUILD_WITHER) return;

        var witherLocation = wither.getLocation();

        var nearbySkullPlacements = recentSkullPlacements.entrySet().stream()
                .filter(entry -> {
                    var skullLoc = entry.getKey();
                    var placement = entry.getValue();

                    if (skullLoc.getWorld().equals(witherLocation.getWorld()) &&
                            skullLoc.distance(witherLocation) <= 5.0) {
                        return (System.currentTimeMillis() - placement.timestamp()) <= 30000;
                    }
                    return false;
                })
                .toList();

        if (!nearbySkullPlacements.isEmpty()) {
            var mostRecentPlacement = nearbySkullPlacements.stream()
                    .max(Comparator.comparingLong(a -> a.getValue().timestamp()))
                    .get();

            var spawnerUuid = mostRecentPlacement.getValue().playerUuid();
            setEntityOwner(wither, spawnerUuid);

            nearbySkullPlacements.forEach(entry -> recentSkullPlacements.remove(entry.getKey()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        var projectile = event.getEntity();
        var shooter = projectile.getShooter();

        if (projectile instanceof WitherSkull && shooter instanceof Wither wither) {
            var witherOwner = getEntityOwner(wither);
            if (witherOwner != null) {
                setEntityOwner(projectile, witherOwner);
            }
        }
        else if (shooter instanceof Player player) {
            setEntityOwner(projectile, player.getUniqueId());
        }
    }

    private void setEntityOwner(Entity entity, UUID ownerUuid) {
        entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, ownerUuid.toString());
    }

    private @Nullable UUID getEntityOwner(Entity entity) {
        var container = entity.getPersistentDataContainer();
        var ownerString = container.get(ownerKey, PersistentDataType.STRING);
        if (ownerString != null) {
            try {
                return UUID.fromString(ownerString);
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    private boolean hasOwner(Entity entity) {
        return getEntityOwner(entity) != null;
    }

    private @Nullable OfflinePlayer getExplosionSourceOffline(Entity entity) {
        var ownerUuid = getEntityOwner(entity);
        if (ownerUuid != null) {
            return Bukkit.getOfflinePlayer(ownerUuid);
        }

        if (entity instanceof TNTPrimed tnt) {
            var source = tnt.getSource();
            if (source instanceof Player player) {
                return player;
            } else if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
                return player;
            }
        }

        if (entity instanceof WitherSkull skull) {
            var shooter = skull.getShooter();
            if (shooter instanceof Wither wither) {
                var witherOwner = getEntityOwner(wither);
                if (witherOwner != null) {
                    return Bukkit.getOfflinePlayer(witherOwner);
                }
            } else if (shooter instanceof Player player) {
                return player;
            }
        }

        if (entity instanceof Creeper creeper) {
            var target = creeper.getTarget();
            if (target instanceof Player player) {
                return player;
            }
        }

        if (entity instanceof Fireball fireball) {
            var shooter = fireball.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }

        return null;
    }

    private boolean canPlayerExplodeAt(OfflinePlayer player, Location location, ApplicableRegionSet regions, Configuration.ExplosionMode mode) {
        if (player.isOnline() && player.getPlayer() != null) {
            var onlinePlayer = player.getPlayer();
            if (worldGuard.canBypass(onlinePlayer)) {
                return true;
            }

            return switch (mode) {
                case BUILDER_ONLY -> worldGuard.canBuild(onlinePlayer, location);
                case MEMBER_ONLY -> isPlayerOwnerOrMember(onlinePlayer, regions);
                default -> false;
            };
        }

        var localPlayer = WorldGuardPlugin.inst().wrapOfflinePlayer(player);
        boolean isOwner = regions.isOwnerOfAll(localPlayer);
        boolean isMember = regions.isMemberOfAll(localPlayer);

        if (mode == Configuration.ExplosionMode.MEMBER_ONLY) {
            return isOwner || isMember;
        }

        if (mode == Configuration.ExplosionMode.BUILDER_ONLY) {
            return isOwner || isMember;
        }

        return false;
    }

    private boolean isPlayerOwnerOrMember(Player player, ApplicableRegionSet regions) {
        var localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
        return regions.isOwnerOfAll(localPlayer) || regions.isMemberOfAll(localPlayer);
    }

    private void handleExplosionChain(TNTPrimed newTnt, Location primeLocation) {
        var nearbyExplosives = primeLocation.getWorld().getNearbyEntities(primeLocation, 10, 10, 10).stream()
                .filter(e -> e instanceof TNTPrimed || e instanceof Creeper || e instanceof Wither)
                .filter(this::hasOwner)
                .toList();

        for (var explosive : nearbyExplosives) {
            var ownerUuid = getEntityOwner(explosive);
            if (ownerUuid != null) {
                setEntityOwner(newTnt, ownerUuid);
                break;
            }
        }
    }

    private void cleanupOldSkullPlacements() {
        var currentTime = System.currentTimeMillis();
        var cutoffTime = currentTime - 60000;
        recentSkullPlacements.entrySet().removeIf(entry -> entry.getValue().timestamp() < cutoffTime);
    }
}