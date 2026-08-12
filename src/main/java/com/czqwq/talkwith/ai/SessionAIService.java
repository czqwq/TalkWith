package com.czqwq.talkwith.ai;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;

import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.ServerEventHandler;
import com.czqwq.talkwith.prompt.PromptStore;
import com.czqwq.talkwith.util.ServerPlayerUtils;

/**
 * Single service responsible for dispatching AI requests for shared sessions.
 * Used by both {@code PacketSessionMessage} and the server chat routing so the
 * dispatch logic lives in exactly one place.
 */
public final class SessionAIService {

    private SessionAIService() {}

    /**
     * Dispatches an AI request for the given session on behalf of {@code requester}.
     *
     * @return {@code null} if the request was dispatched; otherwise the localized error key
     *         to display to the requester.
     */
    public static String tryRequest(SharedSession session, EntityPlayerMP requester, String text) {
        if (!session.isProcessing.compareAndSet(false, true)) {
            return "talkwith.session.processing";
        }

        // Fall back to the server config when the session has no values of its own.
        String baseUrl = session.ownerBaseUrl == null || session.ownerBaseUrl.isEmpty() ? Config.baseUrl
            : session.ownerBaseUrl;
        String apiKey = session.ownerApiKey == null || session.ownerApiKey.isEmpty() ? Config.apiKey
            : session.ownerApiKey;
        String model = session.sessionModel == null || session.sessionModel.isEmpty() ? Config.model
            : session.sessionModel;

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            session.isProcessing.set(false);
            EntityPlayerMP owner = ServerPlayerUtils.getPlayerByUUID(MinecraftServer.getServer(), session.ownerUuid);
            if (owner != null) {
                owner.addChatMessage(
                    new ChatComponentText("§c[TalkWith]§r ")
                        .appendSibling(new ChatComponentTranslation("talkwith.session.not_configured")));
            }
            return "talkwith.session.not_configured";
        }

        final MinecraftServer server = MinecraftServer.getServer();
        final String playerName = requester.getCommandSenderName();
        session.session.addMessage("user", playerName + ": " + text);
        String prompt = PromptStore.readSession(session.sessionId, session.sessionPromptFile);
        int maxHist = session.sessionMaxHistory > 0 ? session.sessionMaxHistory : Config.maxHistory;
        AIClient.sendAsync(session.session.getMessages(prompt, maxHist), baseUrl, apiKey, model, reply -> {
            // AIClient callbacks run on its executor thread. All server state mutation and
            // packet sends must happen on the server thread, so marshal the whole body over.
            ServerEventHandler.scheduleOnServerThread(() -> {
                session.session.addMessage("assistant", reply);
                session.lastReplyTime = System.currentTimeMillis();
                session.lastActivity = session.lastReplyTime;
                session.addRecentMessage(playerName, text, reply);
                session.isProcessing.set(false);
                SessionWorldData.save();
                ServerEventHandler.broadcastToSession(session, playerName, text, reply, server);
            });
        }, error -> {
            ServerEventHandler.scheduleOnServerThread(() -> {
                session.isProcessing.set(false);
                ServerEventHandler.broadcastErrorToSession(session, error, server);
            });
        });
        return null;
    }
}
