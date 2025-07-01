package io.invokegs.betterregions.integration.inject;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Level;

public final class CommandInjector {

    private final Plugin plugin;

    private final String commandName;
    private final Function<Command, CommandWrapper> wrapperFactory;
    private boolean injected = false;

    public CommandInjector(Plugin plugin, String commandName, Function<Command, CommandWrapper> wrapperFactory) {
        this.plugin = plugin;
        this.commandName = commandName;
        this.wrapperFactory = wrapperFactory;
    }

    /**
     * Injects our command wrapper into the server's command map.
     * @return true if injection was successful
     */
    public boolean inject() {
        try {
            if (injected) {
                plugin.getLogger().warning("Command wrapper is already injected - skipping");
                return true;
            }

            var commandMap = getCommandMap();
            if (commandMap == null) {
                plugin.getLogger().severe("Failed to get command map - command injection failed");
                return false;
            }

            var knownCommands = getKnownCommands(commandMap);
            if (knownCommands == null) {
                plugin.getLogger().severe("Failed to get known commands - command injection failed");
                return false;
            }

            var regionCommands = findAllRegionCommands(knownCommands);
            if (regionCommands.isEmpty()) {
                plugin.getLogger().severe("No WorldGuard region commands found - injection failed");
                return false;
            }

            plugin.getLogger().info("Found " + regionCommands.size() + " WorldGuard region command instances");

            var templateCommand = regionCommands.values().iterator().next();

            CommandWrapper commandWrapper = wrapperFactory.apply(templateCommand);
            replaceAllCommands(knownCommands, regionCommands, commandWrapper);

            plugin.getLogger().info("Successfully injected BetterRegions command wrapper for " +
                    regionCommands.size() + " command aliases");

            this.injected = true;
            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to inject command wrapper", e);
            return false;
        }
    }

    /**
     * Gets the server's command map.
     */
    private @Nullable SimpleCommandMap getCommandMap() {
        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        try {
            return (SimpleCommandMap) commandMap;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to access command map, type is not SimpleCommandMap, but "
                    + commandMap.getClass().getName(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private @Nullable Map<String, Command> getKnownCommands(SimpleCommandMap commandMap) {
        try {
            var knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            return (Map<String, Command>) knownCommandsField.get(commandMap);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to access known commands", e);
            return null;
        }
    }

    private Map<String, Command> findAllRegionCommands(Map<String, Command> knownCommands) {
        Map<String, Command> regionCommands = new HashMap<>();

        for (var entry : knownCommands.entrySet()) {
            var name = entry.getKey();
            var command = entry.getValue();

            if (regionCommands.containsKey(name)) continue;
            if (isWorldGuardRegionCommand(command)) {
                regionCommands.put(name, command);
            }
        }

        return regionCommands;
    }

    private boolean isWorldGuardRegionCommand(Command command) {
        return command.getName().equalsIgnoreCase(commandName);
    }

    private void replaceAllCommands(Map<String, Command> knownCommands,
                                    Map<String, Command> regionCommands,
                                    CommandWrapper wrapper) {
        Map<String, Command> aliasesToUpdate = new HashMap<>();
        for (var entry : regionCommands.entrySet()) {
            var alias = entry.getKey();
            aliasesToUpdate.put(alias, wrapper);
        }
        knownCommands.putAll(aliasesToUpdate);
    }
}