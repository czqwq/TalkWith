package com.czqwq.talkwith.util;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.common.UsernameCache;

public class ServerPlayerUtils {

    public static String getPlayerName(EntityPlayer player) {
        return player.getCommandSenderName();
    }

    public static String getPlayerName(UUID player) {
        String name = UsernameCache.getLastKnownUsername(player);
        return name != null ? name : player.toString();
    }

    public static EntityPlayer getPlayerByUUID(World world, UUID playerId) {
        return world.func_152378_a(playerId);
    }

    /** Returns the online {@link EntityPlayerMP} with the given UUID, or {@code null}. */
    @SuppressWarnings("unchecked")
    public static EntityPlayerMP getPlayerByUUID(MinecraftServer server, UUID uuid) {
        if (server == null) return null;
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) obj;
            if (p.getUniqueID()
                .equals(uuid)) return p;
        }
        return null;
    }
}
