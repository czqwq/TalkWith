package com.czqwq.talkwith.network;

import com.czqwq.talkwith.ClientProxy;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: sets the client's current session ID.
 * Sending an empty string clears it (returns to client/single mode).
 */
public class PacketOpenGui implements IMessage {

    public String sessionId;

    public PacketOpenGui() {}

    public PacketOpenGui(String sessionId) {
        this.sessionId = sessionId != null ? sessionId : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        sessionId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, sessionId != null ? sessionId : "");
    }

    public static class Handler implements IMessageHandler<PacketOpenGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenGui msg, MessageContext ctx) {
            final String sid = msg.sessionId;
            ClientProxy.scheduleOnMainThread(
                () -> { ClientProxy.currentSessionId = (sid == null || sid.isEmpty()) ? null : sid; });
            return null;
        }
    }
}
