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
 * Server → Client: open GuiAIChat with the given initial text.
 * Used by the ">" prefix shortcut (ServerChatEvent intercept).
 */
public class PacketOpenGui implements IMessage {

    public String initialText;

    public PacketOpenGui() {}

    public PacketOpenGui(String initialText) {
        this.initialText = initialText != null ? initialText : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        initialText = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, initialText);
    }

    public static class Handler implements IMessageHandler<PacketOpenGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenGui msg, MessageContext ctx) {
            String text = msg.initialText;
            ClientProxy.scheduleOnMainThread(
                () -> Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiAIChat(text)));
            return null;
        }
    }
}
