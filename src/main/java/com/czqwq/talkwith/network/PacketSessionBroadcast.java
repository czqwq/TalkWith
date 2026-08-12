package com.czqwq.talkwith.network;

import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.gui.GuiVanillaChat;
import com.czqwq.talkwith.util.TextUtils;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketSessionBroadcast implements IMessage {

    /**
     * Writes a string to a {@link ByteBuf} using a 4-byte length prefix followed by
     * the UTF-8 encoded bytes. Unlike {@code ByteBufUtils.writeUTF8String}, this
     * supports strings up to ~2 GB and will not throw on long AI replies.
     */
    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    /** Inverse of {@link #writeString}. */
    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        // Guard against corrupted or malicious packets
        if (len < 0 || len > 10 * 1024 * 1024) {
            throw new IllegalStateException("PacketSessionBroadcast: string length out of range: " + len);
        }
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public String playerName;
    public String playerMsg;
    public String aiReply;
    /** When true the payload is an error string (in {@link #aiReply}) rather than an AI reply. */
    public boolean isError;
    /**
     * When true, only update {@link com.czqwq.talkwith.ClientProxy#chatHistory} — do NOT
     * display the message in vanilla chat or trigger GUI updates. Used to silently pre-populate
     * history on session reconnect so the user has context when they open the GUI.
     */
    public boolean historyOnly;

    public PacketSessionBroadcast() {}

    public PacketSessionBroadcast(String playerName, String playerMsg, String aiReply) {
        this.playerName = playerName;
        this.playerMsg = playerMsg;
        this.aiReply = aiReply;
        this.isError = false;
        this.historyOnly = false;
    }

    /** Factory for error broadcasts — the error message is stored in {@link #aiReply}. */
    public static PacketSessionBroadcast error(String errorMsg) {
        PacketSessionBroadcast p = new PacketSessionBroadcast("", "", errorMsg);
        p.isError = true;
        return p;
    }

    /**
     * Factory for silent history-only broadcasts — the message is added to
     * {@link com.czqwq.talkwith.ClientProxy#chatHistory} but not displayed in chat.
     * Used to restore recent session history on reconnect.
     */
    public static PacketSessionBroadcast historyOnly(String playerName, String playerMsg, String aiReply) {
        PacketSessionBroadcast p = new PacketSessionBroadcast(playerName, playerMsg, aiReply);
        p.historyOnly = true;
        return p;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerName = readString(buf);
        playerMsg = readString(buf);
        aiReply = readString(buf);
        isError = buf.readBoolean();
        historyOnly = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeString(buf, playerName);
        writeString(buf, playerMsg);
        writeString(buf, aiReply);
        buf.writeBoolean(isError);
        buf.writeBoolean(historyOnly);
    }

    public static class Handler implements IMessageHandler<PacketSessionBroadcast, IMessage> {

        @Override
        public IMessage onMessage(PacketSessionBroadcast msg, MessageContext ctx) {
            final String pn = msg.playerName;
            final String pm = msg.playerMsg;
            final String ar = msg.aiReply;
            final boolean err = msg.isError;
            final boolean histOnly = msg.historyOnly;
            ClientProxy.scheduleOnMainThread(() -> {
                String aiPrefix = StatCollector.translateToLocal("talkwith.chat.ai_prefix");

                // Silent history-only mode: populate chatHistory without displaying anything.
                // Used on reconnect to restore recent session context.
                if (histOnly) {
                    if (!err) {
                        ClientProxy.addToChatHistory("§e[" + pn + "]: §f" + pm);
                        for (String line : TextUtils.buildAIReplyLines(aiPrefix, ar)) {
                            ClientProxy.addToChatHistory(line);
                        }
                    }
                    return;
                }

                GuiAIChat gui = ClientProxy.activeGui;

                // If the GUI is explicitly open, always route to it — even when useVanillaGui is true.
                // This covers the case where the player opened the GUI while still in vanilla mode.
                if (gui != null) {
                    // Persist each formatted AI reply line to shared history so future GUI opens
                    // can show the messages correctly (avoiding single-entry multi-line truncation).
                    if (err) {
                        ClientProxy.addToChatHistory(StatCollector.translateToLocal("talkwith.chat.error_prefix") + ar);
                        gui.appendError(ar);
                    } else {
                        // The sender's own GUI already added a local "You: msg" line via
                        // GuiAIChat.sendMessage. Without this dedup the server echo would be
                        // appended again, so the sender sees every message twice in history.
                        if (!isEchoAlreadyShownLocally(pn, pm)) {
                            ClientProxy.addToChatHistory("§e[" + pn + "]: §f" + pm);
                        }
                        for (String line : TextUtils.buildAIReplyLines(aiPrefix, ar)) {
                            ClientProxy.addToChatHistory(line);
                        }
                        gui.appendReply(pn, pm, ar);
                    }
                    return;
                }

                // GUI is not open.
                if (ClientProxy.useVanillaGui()) {
                    // Vanilla mode: route directly to vanilla chat (no chatHistory update)
                    if (err) {
                        GuiVanillaChat.appendError(ar);
                    } else {
                        GuiVanillaChat.appendReply(pn, pm, ar);
                    }
                    return;
                }

                // Default mode but GUI is closed: persist and notify via vanilla chat as fallback.
                if (err) {
                    ClientProxy.addToChatHistory(StatCollector.translateToLocal("talkwith.chat.error_prefix") + ar);
                    TextUtils.error(StatCollector.translateToLocalFormatted("talkwith.session.ai_error", ar));
                } else {
                    ClientProxy.addToChatHistory("§e[" + pn + "]: §f" + pm);
                    for (String line : TextUtils.buildAIReplyLines(aiPrefix, ar)) {
                        ClientProxy.addToChatHistory(line);
                    }
                    TextUtils.addChatMessage("§e[" + pn + "]: §f" + pm);
                    TextUtils.sendAIReply(aiPrefix, ar);
                }
            });
            return null;
        }

        /**
         * True when the broadcast is the local player's own message AND the exact message
         * text already appears as a local "You: ..." line in chatHistory (added optimistically
         * by {@link GuiAIChat#sendMessage} when sending through the GUI). In that case the
         * server echo must not be appended again, or the sender sees every message twice.
         * If the player sent the message through another path (e.g. the vanilla chat {@code > }
         * shortcut) there is no local line, so the echo is kept.
         */
        private static boolean isEchoAlreadyShownLocally(String playerName, String playerMsg) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc.thePlayer == null || playerMsg == null || !playerName.equals(mc.thePlayer.getCommandSenderName())) {
                return false;
            }
            for (String line : ClientProxy.chatHistory) {
                int idx = line.indexOf(": ");
                if (idx >= 0 && line.substring(idx + 2)
                    .equals(playerMsg)) {
                    return true;
                }
            }
            return false;
        }
    }
}
