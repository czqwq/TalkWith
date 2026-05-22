package com.czqwq.talkwith.network;

import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.gui.GuiVanillaChat;
import com.czqwq.talkwith.util.TextUtils;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketSessionBroadcast implements IMessage {

    public String playerName;
    public String playerMsg;
    public String aiReply;
    /** When true the payload is an error string (in {@link #aiReply}) rather than an AI reply. */
    public boolean isError;

    public PacketSessionBroadcast() {}

    public PacketSessionBroadcast(String playerName, String playerMsg, String aiReply) {
        this.playerName = playerName;
        this.playerMsg = playerMsg;
        this.aiReply = aiReply;
        this.isError = false;
    }

    /** Factory for error broadcasts — the error message is stored in {@link #aiReply}. */
    public static PacketSessionBroadcast error(String errorMsg) {
        PacketSessionBroadcast p = new PacketSessionBroadcast("", "", errorMsg);
        p.isError = true;
        return p;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerName = ByteBufUtils.readUTF8String(buf);
        playerMsg = ByteBufUtils.readUTF8String(buf);
        aiReply = ByteBufUtils.readUTF8String(buf);
        isError = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, playerName);
        ByteBufUtils.writeUTF8String(buf, playerMsg);
        ByteBufUtils.writeUTF8String(buf, aiReply);
        buf.writeBoolean(isError);
    }

    public static class Handler implements IMessageHandler<PacketSessionBroadcast, IMessage> {

        @Override
        public IMessage onMessage(PacketSessionBroadcast msg, MessageContext ctx) {
            final String pn = msg.playerName;
            final String pm = msg.playerMsg;
            final String ar = msg.aiReply;
            final boolean err = msg.isError;
            ClientProxy.scheduleOnMainThread(() -> {
                if (ClientProxy.useVanillaGui) {
                    // Vanilla mode: skip activeGui check, route directly to vanilla chat
                    if (err) {
                        GuiVanillaChat.appendError(ar);
                    } else {
                        GuiVanillaChat.appendReply(pn, pm, ar);
                    }
                    return;
                }

                // Always persist to shared history so future GUI opens can show the message
                if (err) {
                    ClientProxy.addToChatHistory(StatCollector.translateToLocal("talkwith.chat.error_prefix") + ar);
                } else {
                    ClientProxy.addToChatHistory("§e[" + pn + "]: §f" + pm);
                    ClientProxy.addToChatHistory(StatCollector.translateToLocal("talkwith.chat.ai_prefix") + ar);
                }

                GuiAIChat gui = ClientProxy.activeGui;
                if (err) {
                    if (gui != null) {
                        // Data already in chatHistory; just clear isThinking
                        gui.appendError(ar);
                    } else {
                        TextUtils.error(StatCollector.translateToLocalFormatted("talkwith.session.ai_error", ar));
                    }
                } else {
                    if (gui != null) {
                        // Data already in chatHistory; just scroll and clear isThinking
                        gui.appendReply(pn, pm, ar);
                    } else {
                        // GUI is not open: also notify via vanilla chat so the player sees the reply
                        TextUtils.addChatMessage("§e[" + pn + "]: §f" + pm);
                        TextUtils.sendAIReply(StatCollector.translateToLocal("talkwith.chat.ai_prefix"), ar);
                    }
                }
            });
            return null;
        }
    }
}
