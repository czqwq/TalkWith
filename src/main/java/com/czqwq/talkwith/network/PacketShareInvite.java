package com.czqwq.talkwith.network;

import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.IChatComponent;

import com.czqwq.talkwith.ClientProxy;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketShareInvite implements IMessage {

    public String senderName;
    public String sessionId;

    public PacketShareInvite() {}

    public PacketShareInvite(String senderName, String sessionId) {
        this.senderName = senderName;
        this.sessionId = sessionId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        senderName = ByteBufUtils.readUTF8String(buf);
        sessionId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, senderName);
        ByteBufUtils.writeUTF8String(buf, sessionId);
    }

    public static class Handler implements IMessageHandler<PacketShareInvite, IMessage> {

        @Override
        public IMessage onMessage(PacketShareInvite msg, MessageContext ctx) {
            ClientProxy.scheduleOnMainThread(() -> {
                IChatComponent base = new ChatComponentText("§a[TalkWith]§r ")
                    .appendSibling(new ChatComponentTranslation("talkwith.session.invited", msg.senderName))
                    .appendText(" ");
                IChatComponent link = new ChatComponentTranslation("talkwith.session.click_to_join");
                link.setChatStyle(
                    new ChatStyle().setChatClickEvent(
                        new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/talkwith session join " + msg.sessionId)));
                base.appendSibling(link);
                Minecraft.getMinecraft().thePlayer.addChatMessage(base);
            });
            return null;
        }
    }
}
