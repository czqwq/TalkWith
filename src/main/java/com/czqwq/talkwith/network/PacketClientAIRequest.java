package com.czqwq.talkwith.network;

import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.gui.GuiVanillaChat;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: request the client to send a message to AI and display the result
 * in the GuiAIChat. Used when a player uses the ">" shortcut but is not in a server session.
 */
public class PacketClientAIRequest implements IMessage {

    public String playerName;
    public String message;

    public PacketClientAIRequest() {}

    public PacketClientAIRequest(String playerName, String message) {
        this.playerName = playerName != null ? playerName : "";
        this.message = message != null ? message : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerName = ByteBufUtils.readUTF8String(buf);
        message = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, playerName != null ? playerName : "");
        ByteBufUtils.writeUTF8String(buf, message != null ? message : "");
    }

    public static class Handler implements IMessageHandler<PacketClientAIRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketClientAIRequest msg, MessageContext ctx) {
            final String pName = msg.playerName;
            final String pMsg = msg.message;
            ClientProxy.scheduleOnMainThread(() -> {
                // Empty message is a "gui open" signal — just open the GUI, no AI call.
                if (pMsg == null || pMsg.isEmpty()) {
                    com.czqwq.talkwith.command.TalkWithCommand.openGui();
                    return;
                }

                if (ClientProxy.useVanillaGui) {
                    // Vanilla mode: route AI call through vanilla chat
                    GuiVanillaChat.injectAndSend(pName, pMsg);
                } else if (ClientProxy.activeGui != null) {
                    // GUI is open: inject directly
                    ClientProxy.activeGui.injectAndSend(pMsg);
                } else {
                    // GUI is not open: perform AI call headlessly, push to shared history,
                    // and show the reply in vanilla chat so the player is not left in the dark.
                    ClientProxy.addToChatHistory("§e" + pName + ": §f" + pMsg);
                    ClientProxy.clientSession.addMessage("user", pMsg);
                    AIClient.sendAsync(
                        ClientProxy.clientSession.getMessages(
                            com.czqwq.talkwith.Config.loadPromptFromFile(com.czqwq.talkwith.Config.clientPromptFile)),
                        reply -> ClientProxy.scheduleOnMainThread(() -> {
                            ClientProxy.clientSession.addMessage("assistant", reply);
                            String replyLine = StatCollector.translateToLocal("talkwith.chat.ai_prefix") + reply;
                            ClientProxy.addToChatHistory(replyLine);
                            // Also surface in vanilla chat since the GUI is closed
                            com.czqwq.talkwith.util.TextUtils
                                .sendAIReply(StatCollector.translateToLocal("talkwith.chat.ai_prefix"), reply);
                        }),
                        err -> ClientProxy.scheduleOnMainThread(() -> {
                            String errLine = StatCollector.translateToLocal("talkwith.chat.error_prefix") + err;
                            ClientProxy.addToChatHistory(errLine);
                            com.czqwq.talkwith.util.TextUtils
                                .error(StatCollector.translateToLocal("talkwith.chat.error_prefix") + err);
                        }));
                }
            });
            return null;
        }
    }
}
