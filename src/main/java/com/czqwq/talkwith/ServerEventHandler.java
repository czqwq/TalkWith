package com.czqwq.talkwith;

import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketHandshake;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.entity.player.PlayerEvent;

public class ServerEventHandler {

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedIn event) {
        if (event.player instanceof EntityPlayerMP player) {
            PacketHandler.INSTANCE.sendTo(new PacketHandshake(), player);
        }
    }
}
