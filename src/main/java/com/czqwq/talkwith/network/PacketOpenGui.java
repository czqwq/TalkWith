package com.czqwq.talkwith.network;

import net.minecraft.client.Minecraft;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.gui.GuiAIChat;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: sets the client's current session ID.
 * Sending an empty string clears it (returns to client/single mode).
 */
public class PacketOpenGui implements IMessage {

    public String sessionId;

    public PacketOpenGui() {}

    public PacketOpenGui(String sessionId) {
        this.sessionId = sessionId != null ? sessionId : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        sessionId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, sessionId != null ? sessionId : "");
    }

    public static class Handler implements IMessageHandler<PacketOpenGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenGui msg, MessageContext ctx) {
            final String sid = msg.sessionId;
            ClientProxy.scheduleOnMainThread(() -> {
                if (sid == null || sid.isEmpty()) {
                    ClientProxy.currentSessionId = null;
                    // Close the GUI if it is currently open for a session
                    if (ClientProxy.activeGui != null) {
                        Minecraft.getMinecraft()
                            .displayGuiScreen(null);
                    }
                } else {
                    ClientProxy.currentSessionId = sid;
                    // Open the GUI if it is not already open
                    if (ClientProxy.activeGui == null) {
                        Minecraft.getMinecraft()
                            .displayGuiScreen(new GuiAIChat(""));
                    } else {
                        // Update the session ID on the already-open GUI
                        ClientProxy.activeGui.currentSessionId = sid;
                    }
                }
            });
            return null;
        }
    }
}
