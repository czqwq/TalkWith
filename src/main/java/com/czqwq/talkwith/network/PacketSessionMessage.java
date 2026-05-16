package com.czqwq.talkwith.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import com.czqwq.talkwith.Config;
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

    private static IChatComponent err(String key) {
        return new ChatComponentText("§c[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key));
    }

    private static IChatComponent errf(String key, Object... args) {
        return new ChatComponentText("§c[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key, args));
    }

    public static class Handler implements IMessageHandler<PacketSessionMessage, IMessage> {

        @Override
        public IMessage onMessage(PacketSessionMessage msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            UUID playerUuid = player.getUniqueID();
            String playerName = player.getCommandSenderName();

            SharedSession session = SharedSession.sessions.get(msg.sessionId);
            if (session == null) {
                player.addChatMessage(err("talkwith.session.not_found"));
                return null;
            }
            if (!session.hasPlayer(playerUuid)) {
                player.addChatMessage(err("talkwith.session.not_in_session"));
                return null;
            }
            if (session.isMuted(playerUuid)) {
                player.addChatMessage(err("talkwith.session.muted"));
                return null;
            }
            if (session.isCooldownActive()) {
                long remaining = (session.cooldown * 1000L - (System.currentTimeMillis() - session.lastReplyTime))
                    / 1000 + 1;
                player.addChatMessage(errf("talkwith.session.cooldown", remaining));
                return null;
            }

            session.session.addMessage("user", playerName + ": " + msg.message);
            MinecraftServer server = MinecraftServer.getServer();
            String prompt = Config.loadPromptFromFile(session.sessionPromptFile);

            AIClient.sendAsync(
                session.session.getMessages(prompt),
                session.ownerBaseUrl,
                session.ownerApiKey,
                session.sessionModel,
                reply -> {
                    session.session.addMessage("assistant", reply);
                    session.lastReplyTime = System.currentTimeMillis();
                    session.addRecentMessage(playerName, msg.message, reply);
                    SessionWorldData.save();
                    broadcastToSession(session, playerName, msg.message, reply, server);
                },
                error -> broadcastErrorToSession(session, error, server));
            return null;
        }

        private void broadcastToSession(SharedSession session, String playerName, String playerMsg, String reply,
            MinecraftServer server) {
            PacketSessionBroadcast packet = new PacketSessionBroadcast(playerName, playerMsg, reply);
            for (UUID uuid : session.players) {
                EntityPlayerMP member = getPlayerByUUID(server, uuid);
                if (member != null) {
                    PacketHandler.INSTANCE.sendTo(packet, member);
                }
            }
        }

        private void broadcastErrorToSession(SharedSession session, String error, MinecraftServer server) {
            for (UUID uuid : session.players) {
                EntityPlayerMP member = getPlayerByUUID(server, uuid);
                if (member != null) {
                    member.addChatMessage(errf("talkwith.session.ai_error", error));
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static EntityPlayerMP getPlayerByUUID(MinecraftServer server, UUID uuid) {
            for (Object obj : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP p = (EntityPlayerMP) obj;
                if (p.getUniqueID()
                    .equals(uuid)) return p;
            }
            return null;
        }
    }
}
