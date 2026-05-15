package com.czqwq.talkwith;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.event.ServerChatEvent;

import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.ai.SessionPersistence;
import com.czqwq.talkwith.ai.SharedSession;
import com.czqwq.talkwith.network.PacketClientAIRequest;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketHandshake;
import com.czqwq.talkwith.network.PacketSessionBroadcast;

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
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        UUID playerUuid = event.player.getUniqueID();
        MinecraftServer server = MinecraftServer.getServer();

        for (SharedSession session : SharedSession.sessions.values()) {
            if (!session.hasPlayer(playerUuid)) continue;

            if (session.ownerUuid.equals(playerUuid)) {
                // Try to transfer ownership to the next online member
                UUID newOwnerUuid = null;
                String newOwnerName = null;
                for (UUID uuid : session.players) {
                    if (!uuid.equals(playerUuid)) {
                        EntityPlayerMP candidate = getPlayerByUUID(server, uuid);
                        if (candidate != null) {
                            newOwnerUuid = uuid;
                            newOwnerName = candidate.getCommandSenderName();
                            break;
                        }
                    }
                }

                if (newOwnerUuid != null) {
                    // Transfer ownership — save updated owner to disk
                    session.ownerUuid = newOwnerUuid;
                    session.ownerName = newOwnerName;
                    session.players.remove(playerUuid);
                    SessionPersistence.save(session);
                    EntityPlayerMP newOwnerPlayer = getPlayerByUUID(server, newOwnerUuid);
                    if (newOwnerPlayer != null) {
                        newOwnerPlayer.addChatMessage(
                            new ChatComponentText("§a[TalkWith]§r ")
                                .appendSibling(new ChatComponentTranslation("talkwith.session.owner_transferred")));
                    }
                } else {
                    // No remaining online members — persist history then remove from memory
                    // (file stays on disk so history survives restart)
                    SharedSession.sessions.remove(session.sessionId);
                }
            } else {
                // Regular member disconnected — silently remove
                session.players.remove(playerUuid);
            }
            break;
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        String msg = event.message;
        if (!msg.startsWith("> ") || !(event.player instanceof EntityPlayerMP)) return;

        event.setCanceled(true);
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        String text = msg.substring(2)
            .trim();
        UUID playerUuid = player.getUniqueID();
        String playerName = player.getCommandSenderName();

        // Check if the player is in a server session
        SharedSession foundSession = null;
        for (SharedSession s : SharedSession.sessions.values()) {
            if (s.hasPlayer(playerUuid)) {
                foundSession = s;
                break;
            }
        }

        if (foundSession != null) {
            final SharedSession session = foundSession;

            // Enforce mute
            if (session.isMuted(playerUuid)) {
                player.addChatMessage(
                    new ChatComponentText("§c[TalkWith]§r ")
                        .appendSibling(new ChatComponentTranslation("talkwith.session.muted")));
                return;
            }

            // Enforce cooldown
            if (session.isCooldownActive()) {
                long remaining = (session.cooldown * 1000L - (System.currentTimeMillis() - session.lastReplyTime))
                    / 1000 + 1;
                player.addChatMessage(
                    new ChatComponentText("§c[TalkWith]§r ")
                        .appendSibling(new ChatComponentTranslation("talkwith.session.cooldown", remaining)));
                return;
            }

            final MinecraftServer server = MinecraftServer.getServer();
            session.session.addMessage("user", playerName + ": " + text);
            AIClient.sendAsync(
                session.session.getMessages(Config.systemPrompt),
                session.ownerBaseUrl,
                session.ownerApiKey,
                session.sessionModel,
                reply -> {
                    session.session.addMessage("assistant", reply);
                    session.lastReplyTime = System.currentTimeMillis();
                    session.addRecentMessage(playerName, text, reply);
                    SessionPersistence.save(session);
                    broadcastToSession(session, playerName, text, reply, server);
                },
                err -> broadcastErrorToSession(session, err, server));
        } else {
            // No server session: delegate to client for local AI processing
            PacketHandler.INSTANCE.sendTo(new PacketClientAIRequest(playerName, text), player);
        }
    }

    private static void broadcastToSession(SharedSession session, String playerName, String playerMsg, String reply,
        MinecraftServer server) {
        PacketSessionBroadcast packet = new PacketSessionBroadcast(playerName, playerMsg, reply);
        for (UUID uuid : session.players) {
            EntityPlayerMP member = getPlayerByUUID(server, uuid);
            if (member != null) {
                PacketHandler.INSTANCE.sendTo(packet, member);
            }
        }
    }

    private static void broadcastErrorToSession(SharedSession session, String err, MinecraftServer server) {
        for (UUID uuid : session.players) {
            EntityPlayerMP member = getPlayerByUUID(server, uuid);
            if (member != null) {
                member.addChatMessage(
                    new net.minecraft.util.ChatComponentText("§c[TalkWith]§r ").appendSibling(
                        new net.minecraft.util.ChatComponentTranslation("talkwith.session.ai_error", err)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    static EntityPlayerMP getPlayerByUUID(MinecraftServer server, UUID uuid) {
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) obj;
            if (p.getUniqueID()
                .equals(uuid)) return p;
        }
        return null;
    }
}
