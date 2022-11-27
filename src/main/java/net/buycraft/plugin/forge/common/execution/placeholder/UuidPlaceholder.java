package net.buycraft.plugin.forge.common.execution.placeholder;



import net.buycraft.plugin.forge.common.UuidUtil;
import net.buycraft.plugin.forge.common.data.QueuedCommand;
import net.buycraft.plugin.forge.common.data.QueuedPlayer;

import java.util.regex.Pattern;

public class UuidPlaceholder implements Placeholder {
    private static final Pattern REPLACE_UUID = Pattern.compile("[{\\(<\\[](uuid|id)[}\\)>\\]]", Pattern.CASE_INSENSITIVE);

    @Override
    public String replace(String command, QueuedPlayer player, QueuedCommand queuedCommand) {
        if (player.getUuid() == null) {
            return REPLACE_UUID.matcher(command).replaceAll(player.getName());
        }
        return REPLACE_UUID.matcher(command).replaceAll(UuidUtil.mojangUuidToJavaUuid(player.getUuid()).toString());
    }
}
