package com.czqwq.talkwith.network;

import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.ai.SharedSession;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import java.util.UUID;

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
                player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Session not found."));
                return null;
            }
            if (!session.hasPlayer(playerUuid)) {
                player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You are not in this session."));
                return null;
            }
            if (session.isMuted(playerUuid)) {
                player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You are muted in this session."));
                return null;
            }
            if (session.isCooldownActive()) {
                long remaining = (session.cooldown * 1000L - (System.currentTimeMillis() - session.lastReplyTime)) / 1000 + 1;
                player.addChatMessage(new ChatComponentText("§c[TalkWith]§r AI is on cooldown. Please wait " + remaining + " seconds."));
                return null;
            }

            session.session.addMessage("user", playerName + ": " + msg.message);
            MinecraftServer server = MinecraftServer.getServer();

            AIClient.sendAsync(
                session.session.getMessages(Config.systemPrompt),
                session.ownerBaseUrl,
                session.ownerApiKey,
                reply -> {
                    session.session.addMessage("assistant", reply);
                    session.lastReplyTime = System.currentTimeMillis();
                    broadcastToSession(session, playerName, msg.message, reply, server);
                },
                err -> broadcastErrorToSession(session, err, server)
            );
            return null;
        }

        private void broadcastToSession(SharedSession session, String playerName, String playerMsg, String reply, MinecraftServer server) {
            PacketSessionBroadcast packet = new PacketSessionBroadcast(playerName, playerMsg, reply);
            for (UUID uuid : session.players) {
                EntityPlayerMP member = server.getConfigurationManager().getPlayerByUUID(uuid);
                if (member != null) {
                    PacketHandler.INSTANCE.sendTo(packet, member);
                }
            }
        }

        private void broadcastErrorToSession(SharedSession session, String err, MinecraftServer server) {
            for (UUID uuid : session.players) {
                EntityPlayerMP member = server.getConfigurationManager().getPlayerByUUID(uuid);
                if (member != null) {
                    member.addChatMessage(new ChatComponentText("§c[TalkWith]§r AI error: " + err));
                }
            }
        }
    }
}
