package com.czqwq.talkwith.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.ServerEventHandler;
import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.ai.SessionWorldData;
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
            UUID playerUuid = player.getUniqueID();
            String playerName = player.getCommandSenderName();

            SharedSession session = SharedSession.sessions.get(msg.sessionId);
            if (session == null) {
                // Route through PacketSessionBroadcast so the GUI clears the thinking indicator.
                PacketHandler.INSTANCE.sendTo(
                    PacketSessionBroadcast.error(StatCollector.translateToLocal("talkwith.session.not_found")),
                    player);
                return null;
            }
            if (!session.hasPlayer(playerUuid)) {
                PacketHandler.INSTANCE.sendTo(
                    PacketSessionBroadcast.error(StatCollector.translateToLocal("talkwith.session.not_in_session")),
                    player);
                return null;
            }
            if (session.isMuted(playerUuid)) {
                PacketHandler.INSTANCE.sendTo(
                    PacketSessionBroadcast.error(StatCollector.translateToLocal("talkwith.session.muted")),
                    player);
                return null;
            }
            if (session.isCooldownActive()) {
                long remaining = (session.cooldown * 1000L - (System.currentTimeMillis() - session.lastReplyTime))
                    / 1000 + 1;
                PacketHandler.INSTANCE.sendTo(
                    PacketSessionBroadcast
                        .error(StatCollector.translateToLocalFormatted("talkwith.session.cooldown", remaining)),
                    player);
                return null;
            }
            if (!session.isProcessing.compareAndSet(false, true)) {
                PacketHandler.INSTANCE.sendTo(
                    PacketSessionBroadcast.error(StatCollector.translateToLocal("talkwith.session.processing")),
                    player);
                return null;
            }

            session.session.addMessage("user", playerName + ": " + msg.message);
            MinecraftServer server = MinecraftServer.getServer();
            String prompt = Config.loadPromptFromFile(session.sessionPromptFile);
            int maxHist = session.sessionMaxHistory > 0 ? session.sessionMaxHistory : Config.maxHistory;

            AIClient.sendAsync(
                session.session.getMessages(prompt, maxHist),
                session.ownerBaseUrl,
                session.ownerApiKey,
                session.sessionModel,
                reply -> {
                    session.session.addMessage("assistant", reply);
                    session.lastReplyTime = System.currentTimeMillis();
                    session.lastActivity = session.lastReplyTime;
                    session.addRecentMessage(playerName, msg.message, reply);
                    session.isProcessing.set(false);
                    SessionWorldData.save();
                    ServerEventHandler.broadcastToSession(session, playerName, msg.message, reply, server);
                },
                error -> {
                    session.isProcessing.set(false);
                    ServerEventHandler.broadcastErrorToSession(session, error, server);
                });
            return null;
        }
    }
}
