package com.czqwq.talkwith;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
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
     * members of a server session. Cleared via {@link #clearPlayerState(UUID)}.
     */
    public static final Set<UUID> singleModeOverride = new CopyOnWriteArraySet<>();

    /**
     * Players in takeover mode — all chat messages (no {@code >} prefix needed) are
     * intercepted and routed according to {@link #chatModes}.
     */
    public static final Set<UUID> takeoverPlayers = new CopyOnWriteArraySet<>();

    /**
     * Per-player chat mode while in takeover: {@code "ai"} (default), {@code "group"},
     * or {@code "public"}.
     */
    public static final Map<UUID, String> chatModes = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Player login / logout
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID playerUuid = player.getUniqueID();

        // Announce the mod is present on this server
        PacketHandler.INSTANCE.sendTo(new PacketHandshake(), player);

        // Restore previous session if the player disconnected while in one
        String lastSessionId = SessionWorldData.playerSessionMap.remove(playerUuid);
        if (lastSessionId != null) {
            SharedSession session = SharedSession.sessions.get(lastSessionId);
            if (session != null) {
                session.players.add(playerUuid);
                PacketHandler.INSTANCE.sendTo(new com.czqwq.talkwith.network.PacketOpenGui(lastSessionId), player);
                SessionWorldData.save();
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        UUID playerUuid = event.player.getUniqueID();
        MinecraftServer server = MinecraftServer.getServer();

        // Clear all per-player state
        clearPlayerState(playerUuid);

        for (SharedSession session : SharedSession.sessions.values()) {
            if (!session.hasPlayer(playerUuid)) continue;

            // Remember which session this player was in so they can be restored on reconnect
            SessionWorldData.playerSessionMap.put(playerUuid, session.sessionId);

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

                session.players.remove(playerUuid);
                if (newOwnerUuid != null) {
                    session.ownerUuid = newOwnerUuid;
                    session.ownerName = newOwnerName;
                    EntityPlayerMP newOwnerPlayer = getPlayerByUUID(server, newOwnerUuid);
                    if (newOwnerPlayer != null) {
                        newOwnerPlayer.addChatMessage(
                            new ChatComponentText("§a[TalkWith]§r ")
                                .appendSibling(new ChatComponentTranslation("talkwith.session.owner_transferred")));
                    }
                }
                // Session stays in SharedSession.sessions — persisted for reconnect even with no members
            } else {
                session.players.remove(playerUuid);
            }
            SessionWorldData.save();
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
    // Chat routing
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;

        EntityPlayerMP player = (EntityPlayerMP) event.player;
        String msg = event.message;
        UUID playerUuid = player.getUniqueID();
        String playerName = player.getCommandSenderName();

        boolean hasPrefix = msg.startsWith("> ");
        boolean hasTakeover = takeoverPlayers.contains(playerUuid);

        // Let normal chat through if neither prefix nor takeover
        if (!hasPrefix && !hasTakeover) return;

        event.setCanceled(true);

        if (hasPrefix) {
            // Explicit "> " prefix always routes to AI regardless of takeover chat-mode
            routeToAI(
                player,
                playerUuid,
                playerName,
                msg.substring(2)
                    .trim());
            return;
        }

        // Takeover mode (no prefix): route by chat-mode
        String mode = chatModes.getOrDefault(playerUuid, "ai");
        if ("public".equals(mode)) {
            broadcastPublicChat(playerName, msg);
        } else if ("group".equals(mode)) {
            routeToGroup(player, playerUuid, playerName, msg);
        } else {
            // "ai" (default)
            routeToAI(player, playerUuid, playerName, msg);
        }
    }

    // -------------------------------------------------------------------------
    // Routing helpers
    // -------------------------------------------------------------------------

    private void routeToAI(EntityPlayerMP player, UUID playerUuid, String playerName, String text) {
        // Find the session this player belongs to (if any)
        SharedSession foundSession = null;
        for (SharedSession s : SharedSession.sessions.values()) {
            if (s.hasPlayer(playerUuid)) {
                foundSession = s;
                break;
            }
        }

        // If in a session but single-override is active (or no session), use local AI
        boolean useLocalAI = (foundSession == null) || singleModeOverride.contains(playerUuid);

        if (!useLocalAI) {
            final SharedSession session = foundSession;

            if (session.isMuted(playerUuid)) {
                player.addChatMessage(
                    new ChatComponentText("§c[TalkWith]§r ")
                        .appendSibling(new ChatComponentTranslation("talkwith.session.muted")));
                return;
            }

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

    /**
     * Broadcasts a message to all online players as plain chat (mimics vanilla chat format).
     * Used in takeover {@code public} mode.
     */
    @SuppressWarnings("unchecked")
    private void broadcastPublicChat(String playerName, String text) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        ChatComponentText chatMsg = new ChatComponentText("<" + playerName + "> " + text);
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            ((EntityPlayerMP) obj).addChatMessage(chatMsg);
        }
    }

    /**
     * Broadcasts a message only to the player's current session members (no AI involvement).
     * Used in takeover {@code group} mode so the conversation stays private to the group.
     */
    private void routeToGroup(EntityPlayerMP player, UUID playerUuid, String playerName, String text) {
        SharedSession foundSession = null;
        for (SharedSession s : SharedSession.sessions.values()) {
            if (s.hasPlayer(playerUuid)) {
                foundSession = s;
                break;
            }
        }
        if (foundSession == null) {
            player.addChatMessage(
                new ChatComponentText("§c[TalkWith]§r ")
                    .appendSibling(new ChatComponentTranslation("talkwith.takeover.group.no_session")));
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        IChatComponent chatMsg = new ChatComponentTranslation("talkwith.chat.group_format", playerName, text);
        for (UUID uuid : foundSession.players) {
            EntityPlayerMP member = getPlayerByUUID(server, uuid);
            if (member != null) member.addChatMessage(chatMsg);
        }
    }

    // -------------------------------------------------------------------------
    // Session broadcast helpers
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

    // -------------------------------------------------------------------------
    // Per-player state management
    // -------------------------------------------------------------------------

    /**
     * Clears ALL per-player server-side state for the given UUID.
     * Must be called on logout, kick, leave, and session close for affected players.
     */
    public static void clearPlayerState(UUID uuid) {
        singleModeOverride.remove(uuid);
        takeoverPlayers.remove(uuid);
        chatModes.remove(uuid);
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
