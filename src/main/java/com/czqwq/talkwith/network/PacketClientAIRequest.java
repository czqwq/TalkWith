package com.czqwq.talkwith.network;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.util.TextUtils;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: request the client to send a message to AI and display the result
 * in vanilla chat. Used when a player uses the ">" shortcut but is not in a server session.
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
                TextUtils.addChatMessage("§e[" + pName + "]: §f" + pMsg);
                ClientProxy.clientSession.addMessage("user", pMsg);
                AIClient.sendAsync(
                    ClientProxy.clientSession.getMessages(Config.loadPromptFromFile(Config.clientPromptFile)),
                    Config.baseUrl,
                    Config.apiKey,
                    Config.model,
                    reply -> ClientProxy.scheduleOnMainThread(() -> {
                        ClientProxy.clientSession.addMessage("assistant", reply);
                        TextUtils.addChatMessage("§b[AI]: §r" + reply);
                    }),
                    err -> ClientProxy.scheduleOnMainThread(() -> TextUtils.error(err)));
            });
            return null;
        }
    }
}
