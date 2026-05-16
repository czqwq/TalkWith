package com.czqwq.talkwith;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.ai.SessionWorldData;
import com.czqwq.talkwith.ai.SharedSession;
import com.czqwq.talkwith.network.PacketClientAIRequest;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketHandshake;
import com.czqwq.talkwith.network.PacketSessionBroadcast;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

public class ServerEventHandler {

    /**
     * Players who have used {@code /talkwith switch single}.
     * Their {@code >} chat messages are routed to their local AI even while they remain
     * members of a server session. Cleared on logout or on {@code /talkwith switch multi}.
     */
    public static final Set<UUID> singleModeOverride = new CopyOnWriteArraySet<>();

    // -------------------------------------------------------------------------
    // Player login / logout
    // -------------------------------------------------------------------------

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

        // Clear any single-mode override
        singleModeOverride.remove(playerUuid);

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
                    session.ownerUuid = newOwnerUuid;
                    session.ownerName = newOwnerName;
                    session.players.remove(playerUuid);
                    SessionWorldData.save();
                    EntityPlayerMP newOwnerPlayer = getPlayerByUUID(server, newOwnerUuid);
                    if (newOwnerPlayer != null) {
                        newOwnerPlayer.addChatMessage(
                            new ChatComponentText("§a[TalkWith]§r ")
                                .appendSibling(new ChatComponentTranslation("talkwith.session.owner_transferred")));
                    }
                } else {
                    // No remaining online members — persist history in world data, remove from memory
                    session.players.remove(playerUuid);
                    SharedSession.sessions.remove(session.sessionId);
                    SessionWorldData.save();
                }
            } else {
                session.players.remove(playerUuid);
            }
            break;
        }
    }

    // -------------------------------------------------------------------------
    // World load — restore sessions from world save data
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        // Only restore once, from the overworld (dimension 0).
        if (event.world.provider.dimensionId != 0) return;
        // Only runs on the logical server side.
        if (event.world.isRemote) return;
        SessionWorldData.restore();
    }

    // -------------------------------------------------------------------------
    // Chat routing ("> message" shortcut)
    // -------------------------------------------------------------------------

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

        // Find the session this player belongs to (if any)
        SharedSession foundSession = null;
        for (SharedSession s : SharedSession.sessions.values()) {
            if (s.hasPlayer(playerUuid)) {
                foundSession = s;
                break;
            }
        }

        // If the player is in a session but has switched to single-mode override,
        // route to their local client AI instead.
        boolean useLocalAI = (foundSession == null) || singleModeOverride.contains(playerUuid);

        if (!useLocalAI) {
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
            String prompt = Config.loadPromptFromFile(session.sessionPromptFile);
            AIClient.sendAsync(
                session.session.getMessages(prompt),
                session.ownerBaseUrl,
                session.ownerApiKey,
                session.sessionModel,
                reply -> {
                    session.session.addMessage("assistant", reply);
                    session.lastReplyTime = System.currentTimeMillis();
                    session.addRecentMessage(playerName, text, reply);
                    SessionWorldData.save();
                    broadcastToSession(session, playerName, text, reply, server);
                },
                err -> broadcastErrorToSession(session, err, server));
        } else {
            // No session or single-mode override: delegate to the client for local AI processing
            PacketHandler.INSTANCE.sendTo(new PacketClientAIRequest(playerName, text), player);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
                    new ChatComponentText("§c[TalkWith]§r ")
                        .appendSibling(new ChatComponentTranslation("talkwith.session.ai_error", err)));
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
