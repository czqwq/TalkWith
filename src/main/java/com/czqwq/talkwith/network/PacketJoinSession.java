package com.czqwq.talkwith.network;

import com.czqwq.talkwith.ai.SharedSession;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

public class PacketJoinSession implements IMessage {

    public String sessionId;

    public PacketJoinSession() {}

    public PacketJoinSession(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        sessionId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, sessionId);
    }

    public static class Handler implements IMessageHandler<PacketJoinSession, IMessage> {
        @Override
        public IMessage onMessage(PacketJoinSession msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            SharedSession session = SharedSession.sessions.get(msg.sessionId);
            if (session == null) {
                player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Session not found: " + msg.sessionId));
                return null;
            }
            if (session.hasPlayer(player.getUniqueID())) {
                player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You are already in this session."));
                return null;
            }
            session.players.add(player.getUniqueID());
            player.addChatMessage(new ChatComponentText("§a[TalkWith]§r Joined session " + msg.sessionId + "."));
            return null;
        }
    }
}
