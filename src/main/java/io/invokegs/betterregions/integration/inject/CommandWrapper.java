package io.invokegs.betterregions.integration.inject;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;

import java.util.List;

public abstract class CommandWrapper extends Command implements CommandExecutor, TabCompleter {
    protected CommandWrapper(String name) {
        super(name);
    }

    protected CommandWrapper(String name, String description, String usageMessage, List<String> aliases) {
        super(name, description, usageMessage, aliases);
    }
}
