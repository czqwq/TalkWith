package com.czqwq.talkwith.network;

import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.util.TextUtils;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketHandshake implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketHandshake, IMessage> {

        @Override
        public IMessage onMessage(PacketHandshake msg, MessageContext ctx) {
            ClientProxy.serverHasMod = true;
            ClientProxy
                .scheduleOnMainThread(() -> TextUtils.info(StatCollector.translateToLocal("talkwith.server.has_mod")));
            return null;
        }
    }
}
