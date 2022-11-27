package net.buycraft.plugin.forge.common.platform.standalone.runner;

import net.buycraft.plugin.forge.common.data.QueuedPlayer;

public interface PlayerDeterminer {
    /**
     * Determines whether a player is currently online.
     *
     * @param player the player
     * @return whether a player is currently online
     */
    boolean isPlayerOnline(QueuedPlayer player);

    /**
     * Determines how many slots this player has available in their inventory.
     *
     * @param player the player
     * @return number of slots available, or {@code -1} if it is not supported or the player is offline
     */
    int getFreeSlots(QueuedPlayer player);
}
