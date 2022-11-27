package net.buycraft.plugin.forge.common.platform;

import java.util.Locale;

public enum PlatformType {
    BUKKIT,
    BUNGEECORD,
    SPONGE,
    NUKKIT,
    FORGE,
    VELOCITY,
    NONE;

    public String platformName() {
        return name().toLowerCase(Locale.US);
    }
}
