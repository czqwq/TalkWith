package com.czqwq.talkwith;

import net.minecraft.entity.player.EntityPlayerMP;

import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketHandshake;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

public class ServerEventHandler {

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PacketHandler.INSTANCE.sendTo(new PacketHandshake(), (EntityPlayerMP) event.player);
        }
    }
}
