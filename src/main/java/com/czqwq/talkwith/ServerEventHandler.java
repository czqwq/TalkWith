package com.czqwq.talkwith;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.czqwq.talkwith.ai.SessionAIService;
import com.czqwq.talkwith.ai.SessionWorldData;
import com.czqwq.talkwith.ai.SharedSession;
import com.czqwq.talkwith.network.PacketClientAIRequest;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketHandshake;
import com.czqwq.talkwith.network.PacketOpenGui;
import com.czqwq.talkwith.network.PacketSessionBroadcast;
import com.czqwq.talkwith.teams.TeamManager;
import com.czqwq.talkwith.util.ServerPlayerUtils;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class ServerEventHandler {

    /**
     * Players who have used the single-mode override toggle.
     * Their {@code >} chat messages are routed to their local AI even while they remain
     * members of a server session. Cleared via {@link #clearPlayerState(UUID)}.
     */
    public static final Set<UUID> singleModeOverride = new CopyOnWriteArraySet<>();

    /** Tasks queued by worker threads to run on the server thread; drained every server tick. */
    private static final ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();

    /**
     * Marshals a task onto the server thread (safe to call from any thread). Needed because
     * AI callbacks run on {@link com.czqwq.talkwith.ai.AIClient}'s executor thread but must
     * mutate server state / send packets on the server thread. Mirrors
     * {@link ClientProxy#scheduleOnMainThread} for the client.
     */
    public static void scheduleOnServerThread(Runnable r) {
        serverTasks.add(r);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Runnable r;
        while ((r = serverTasks.poll()) != null) {
            try {
                r.run();
            } catch (Exception e) {
                TalkWith.LOG.error("Server thread task error", e);
            }
        }
    }

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

        // Defensively ensure sessions are loaded from disk before trying to restore.
        // On integrated-server worlds the WorldEvent.Load and PlayerLoggedInEvent can fire
        // in rapid succession; calling restore() here is idempotent (MapStorage caches the
        // result) and guarantees sessions are available regardless of event ordering.
        SessionWorldData.restore();

        // Ensure every player has a team (creates a solo team on first join).
        TeamManager.getOrCreateTeam(player.getCommandSenderName(), playerUuid);

        // Always consume a persisted single-override entry — even if the session restore
        // below fails. Otherwise a stale entry would silently re-activate the override in
        // a session the player never switched.
        boolean hadOverride = SessionWorldData.singleOverrideSet.remove(playerUuid);

        // Restore previous session if the player disconnected while in one.
        // Use a silent packet so the GUI does not auto-open on every login.
        // Atomically remove the mapping so no other thread can race on it.
        // If the session is not found (stale mapping after a crash), the entry
        // is intentionally dropped — restore() above already loaded fresh data.
        String lastSessionId = SessionWorldData.playerSessionMap.remove(playerUuid);
        if (lastSessionId != null) {
            SharedSession session = SharedSession.sessions.get(lastSessionId);
            if (session != null) {
                session.players.add(playerUuid);
                // Refresh the owner's display name if this player is the owner.
                if (session.ownerUuid.equals(playerUuid)) {
                    session.ownerName = player.getCommandSenderName();
                }
                if (hadOverride) {
                    singleModeOverride.add(playerUuid);
                }
                // silent=true: only update client state, no GUI popup.
                PacketHandler.INSTANCE
                    .sendTo(new PacketOpenGui(lastSessionId, true, session.sessionName, hadOverride), player);
                SessionWorldData.save();
                // Re-send recent history silently so the client's chatHistory is pre-populated.
                for (String[] entry : session.recentMessages) {
                    PacketHandler.INSTANCE
                        .sendTo(PacketSessionBroadcast.historyOnly(entry[0], entry[1], entry[2]), player);
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        UUID playerUuid = event.player.getUniqueID();

        // Clear all per-player state.
        // Save single-override state before clearing so it can be restored on reconnect.
        if (singleModeOverride.contains(playerUuid)) {
            SessionWorldData.singleOverrideSet.add(playerUuid);
        }
        clearPlayerState(playerUuid);

        for (SharedSession session : SharedSession.sessions.values()) {
            if (!session.hasPlayer(playerUuid)) continue;

            // Remember which session this player was in so they can be restored on reconnect.
            SessionWorldData.playerSessionMap.put(playerUuid, session.sessionId);
            session.players.remove(playerUuid);
            // Session stays alive — ownership is NOT auto-transferred on logout.
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
        String textContent = hasPrefix ? msg.substring(2)
            .trim() : msg.trim();

        // Special "gui" shortcut — works with or without the "> " prefix.
        // Opens GuiAIChat on the client (or sends a PacketOpenGui for session context).
        if (textContent.equalsIgnoreCase("gui")) {
            event.setCanceled(true);
            openGuiForPlayer(player, playerUuid, playerName);
            return;
        }

        // Let normal chat through without the "> " prefix.
        if (!hasPrefix) return;

        event.setCanceled(true);
        routeToAI(
            player,
            playerUuid,
            playerName,
            msg.substring(2)
                .trim());
    }

    // -------------------------------------------------------------------------
    // GUI shortcut helper
    // -------------------------------------------------------------------------

    /**
     * Opens {@link com.czqwq.talkwith.gui.GuiAIChat} on the client.
     * If the player is in a session (and not in single-override mode), sends a
     * {@link PacketOpenGui} with the session ID. Otherwise sends a
     * {@link PacketClientAIRequest} with an empty message, which the client handler
     * interprets as "just open the GUI".
     */
    private void openGuiForPlayer(EntityPlayerMP player, UUID playerUuid, String playerName) {
        SharedSession foundSession = SharedSession.findByPlayer(playerUuid);
        boolean useLocalAI = (foundSession == null) || singleModeOverride.contains(playerUuid);
        if (!useLocalAI) {
            PacketHandler.INSTANCE.sendTo(
                new PacketOpenGui(
                    foundSession.sessionId,
                    false,
                    foundSession.sessionName,
                    singleModeOverride.contains(playerUuid)),
                player);
        } else {
            // PacketClientAIRequest with empty message signals "open GUI, no AI call"
            PacketHandler.INSTANCE.sendTo(new PacketClientAIRequest(playerName, ""), player);
        }
    }

    // -------------------------------------------------------------------------
    // Routing helpers
    // -------------------------------------------------------------------------

    private void routeToAI(EntityPlayerMP player, UUID playerUuid, String playerName, String text) {
        // Find the session this player belongs to (if any)
        SharedSession foundSession = SharedSession.findByPlayer(playerUuid);

        // If in a session but single-override is active (or no session), use local AI
        boolean useLocalAI = (foundSession == null) || singleModeOverride.contains(playerUuid);

        if (!useLocalAI) {
            final SharedSession session = foundSession;
            String errorKey = SessionAIService.tryRequest(session, player, text);
            if (errorKey != null) {
                player.addChatMessage(
                    new ChatComponentText("§c[TalkWith]§r ").appendSibling(new ChatComponentTranslation(errorKey)));
            }
        } else {
            // No session or single-mode override: delegate to the client for local AI processing
            PacketHandler.INSTANCE.sendTo(new PacketClientAIRequest(playerName, text), player);
        }
    }

    // -------------------------------------------------------------------------
    // Session broadcast helpers
    // -------------------------------------------------------------------------

    public static void broadcastToSession(SharedSession session, String playerName, String playerMsg, String reply,
        MinecraftServer server) {
        PacketSessionBroadcast packet = new PacketSessionBroadcast(playerName, playerMsg, reply);
        for (UUID uuid : session.players) {
            EntityPlayerMP member = ServerPlayerUtils.getPlayerByUUID(server, uuid);
            if (member != null) {
                PacketHandler.INSTANCE.sendTo(packet, member);
            }
        }
    }

    public static void broadcastErrorToSession(SharedSession session, String err, MinecraftServer server) {
        PacketSessionBroadcast errorPacket = PacketSessionBroadcast.error(err);
        for (UUID uuid : session.players) {
            EntityPlayerMP member = ServerPlayerUtils.getPlayerByUUID(server, uuid);
            if (member != null) {
                PacketHandler.INSTANCE.sendTo(errorPacket, member);
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
    }
}
