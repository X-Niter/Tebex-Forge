package net.buycraft.plugin.forge.common.execution.strategy;



public interface CommandExecutor {
    void queue(ToRunQueuedCommand command);
}
