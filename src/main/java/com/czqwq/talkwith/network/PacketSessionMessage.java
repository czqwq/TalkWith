package com.czqwq.talkwith.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ai.SessionAIService;
import com.czqwq.talkwith.ai.SharedSession;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketSessionMessage implements IMessage {

    public String sessionId;
    public String message;

    public PacketSessionMessage() {}

    public PacketSessionMessage(String sessionId, String message) {
        this.sessionId = sessionId;
        this.message = message;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        sessionId = ByteBufUtils.readUTF8String(buf);
        message = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, sessionId);
        ByteBufUtils.writeUTF8String(buf, message);
    }

    public static class Handler implements IMessageHandler<PacketSessionMessage, IMessage> {

        @Override
        public IMessage onMessage(PacketSessionMessage msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;

            SharedSession session = SharedSession.sessions.get(msg.sessionId);
            if (session == null) {
                // Route through PacketSessionBroadcast so the GUI clears the thinking indicator.
                PacketHandler.INSTANCE.sendTo(
                    PacketSessionBroadcast.error(StatCollector.translateToLocal("talkwith.session.not_found")),
                    player);
                return null;
            }
            if (!session.hasPlayer(player.getUniqueID())) {
                PacketHandler.INSTANCE.sendTo(
                    PacketSessionBroadcast.error(StatCollector.translateToLocal("talkwith.session.not_in_session")),
                    player);
                return null;
            }
            String errorKey = SessionAIService.tryRequest(session, player, msg.message);
            if (errorKey != null) {
                PacketHandler.INSTANCE
                    .sendTo(PacketSessionBroadcast.error(StatCollector.translateToLocal(errorKey)), player);
            }
            return null;
        }
    }
}
