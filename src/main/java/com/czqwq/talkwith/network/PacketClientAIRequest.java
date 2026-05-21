package com.czqwq.talkwith.network;

import net.minecraft.client.Minecraft;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.gui.GuiVanillaChat;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: request the client to send a message to AI and display the result
 * in the GuiAIChat. Used when a player uses the ">" shortcut but is not in a server session.
 */
public class PacketClientAIRequest implements IMessage {

    public String playerName;
    public String message;

    public PacketClientAIRequest() {}

    public PacketClientAIRequest(String playerName, String message) {
        this.playerName = playerName != null ? playerName : "";
        this.message = message != null ? message : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerName = ByteBufUtils.readUTF8String(buf);
        message = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, playerName != null ? playerName : "");
        ByteBufUtils.writeUTF8String(buf, message != null ? message : "");
    }

    public static class Handler implements IMessageHandler<PacketClientAIRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketClientAIRequest msg, MessageContext ctx) {
            final String pName = msg.playerName;
            final String pMsg = msg.message;
            ClientProxy.scheduleOnMainThread(() -> {
                if (ClientProxy.useVanillaGui) {
                    // Vanilla mode: route AI call through vanilla chat
                    GuiVanillaChat.injectAndSend(pName, pMsg);
                } else {
                    // Default mode: ensure GuiAIChat is open and inject into it
                    if (ClientProxy.activeGui == null) {
                        Minecraft.getMinecraft()
                            .displayGuiScreen(new GuiAIChat(""));
                    }
                    GuiAIChat gui = ClientProxy.activeGui;
                    if (gui != null) {
                        gui.injectAndSend(pMsg);
                    }
                }
            });
            return null;
        }
    }
}
