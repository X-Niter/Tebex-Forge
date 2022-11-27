package net.buycraft.plugin.forge.common.execution.placeholder;


import net.buycraft.plugin.forge.common.data.QueuedCommand;
import net.buycraft.plugin.forge.common.data.QueuedPlayer;

public interface Placeholder {
    String replace(String command, QueuedPlayer player, QueuedCommand queuedCommand);
}
