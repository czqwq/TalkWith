package com.czqwq.talkwith.gui;

import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.util.TextUtils;

/**
 * Static helper class for vanilla chat mode AI routing.
 * All AI input/output goes through the vanilla chat HUD instead of {@link GuiAIChat}.
 */
public class GuiVanillaChat {

    private GuiVanillaChat() {}

    /**
     * Called when a session broadcast arrives in vanilla mode.
     * Displays the player's message and the AI reply in vanilla chat.
     */
    public static void appendReply(String playerName, String playerMsg, String aiReply) {
        TextUtils.addChatMessage("§e[" + playerName + "]: §f" + playerMsg);
        TextUtils.sendAIReply(StatCollector.translateToLocal("talkwith.chat.ai_prefix"), aiReply);
    }

    /**
     * Called when a session broadcast error arrives in vanilla mode.
     */
    public static void appendError(String errorMsg) {
        TextUtils.error(StatCollector.translateToLocalFormatted("talkwith.session.ai_error", errorMsg));
    }

    /**
     * Injects a user message into vanilla chat and fires an async AI request.
     * Used when a {@code PacketClientAIRequest} arrives and vanilla mode is active.
     *
     * @param playerName the player's display name (shown as {@code [playerName]: text})
     * @param text       the message text to send to the AI
     */
    public static void injectAndSend(String playerName, String text) {
        TextUtils.addChatMessage("§e[" + playerName + "]: §f" + text);
        ClientProxy.clientSession.addMessage("user", text);
        AIClient.sendAsync(
            ClientProxy.clientSession.getMessages(Config.loadPromptFromFile(Config.clientPromptFile)),
            reply -> ClientProxy.scheduleOnMainThread(() -> {
                ClientProxy.clientSession.addMessage("assistant", reply);
                TextUtils.sendAIReply(StatCollector.translateToLocal("talkwith.chat.ai_prefix"), reply);
            }),
            err -> ClientProxy.scheduleOnMainThread(
                () -> TextUtils.error(StatCollector.translateToLocal("talkwith.chat.error_prefix") + err)));
    }
}
