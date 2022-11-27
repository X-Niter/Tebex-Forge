package net.buycraft.plugin.forge.common.execution.placeholder;



import net.buycraft.plugin.forge.common.data.QueuedCommand;
import net.buycraft.plugin.forge.common.data.QueuedPlayer;

import java.util.ArrayList;
import java.util.List;

public class PlaceholderManager {
    private final List<Placeholder> placeholderList = new ArrayList<>();

    public void addPlaceholder(Placeholder placeholder) {
        placeholderList.add(placeholder);
    }

    public String doReplace(QueuedPlayer player, QueuedCommand command) {
        String c = command.getCommand();
        for (Placeholder placeholder : placeholderList) {
            c = placeholder.replace(c, player, command);
        }
        return c;
    }
}
