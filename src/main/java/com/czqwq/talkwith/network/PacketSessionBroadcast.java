package com.czqwq.talkwith.network;

import com.czqwq.talkwith.ClientProxy;
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

    public PacketSessionBroadcast() {}

    public PacketSessionBroadcast(String playerName, String playerMsg, String aiReply) {
        this.playerName = playerName;
        this.playerMsg = playerMsg;
        this.aiReply = aiReply;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerName = ByteBufUtils.readUTF8String(buf);
        playerMsg = ByteBufUtils.readUTF8String(buf);
        aiReply = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, playerName);
        ByteBufUtils.writeUTF8String(buf, playerMsg);
        ByteBufUtils.writeUTF8String(buf, aiReply);
    }

    public static class Handler implements IMessageHandler<PacketSessionBroadcast, IMessage> {
        @Override
        public IMessage onMessage(PacketSessionBroadcast msg, MessageContext ctx) {
            ClientProxy.scheduleOnMainThread(() -> {
                TextUtils.addChatMessage("§e" + msg.playerName + ": §f" + msg.playerMsg);
                TextUtils.addChatMessage("§b[AI]§r " + msg.aiReply);
            });
            return null;
        }
    }
}
