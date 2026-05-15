package com.czqwq.talkwith;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.ServerChatEvent;

import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketHandshake;
import com.czqwq.talkwith.network.PacketOpenGui;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

public class ServerEventHandler {

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PacketHandler.INSTANCE.sendTo(new PacketHandshake(), (EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        String msg = event.message;
        if (msg.startsWith("> ") && event.player instanceof EntityPlayerMP) {
            event.setCanceled(true);
            String text = msg.substring(2)
                .trim();
            PacketHandler.INSTANCE.sendTo(new PacketOpenGui(text), (EntityPlayerMP) event.player);
        }
    }
}
