package net.buycraft.plugin.forge.common.execution.placeholder;



import net.buycraft.plugin.forge.common.data.QueuedCommand;
import net.buycraft.plugin.forge.common.data.QueuedPlayer;

import java.util.regex.Pattern;

public class XuidPlaceholder implements Placeholder {
    private static final Pattern REPLACE_UUID = Pattern.compile("[{(<\\[]id[})>\\]]", Pattern.CASE_INSENSITIVE);

    @Override
    public String replace(String command, QueuedPlayer player, QueuedCommand queuedCommand) {
        if (player.getUuid() == null) {
            return command; // can't replace UUID for offline mode
        }
        return REPLACE_UUID.matcher(command).replaceAll(player.getUuid());
    }
}
