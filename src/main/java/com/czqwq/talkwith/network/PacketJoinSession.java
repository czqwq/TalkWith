package com.czqwq.talkwith.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import com.czqwq.talkwith.ai.SharedSession;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketJoinSession implements IMessage {

    public String sessionId;

    public PacketJoinSession() {}

    public PacketJoinSession(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        sessionId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, sessionId);
    }

    private static IChatComponent err(String key) {
        return new ChatComponentText("§c[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key));
    }

    private static IChatComponent okf(String key, Object... args) {
        return new ChatComponentText("§a[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key, args));
    }

    public static class Handler implements IMessageHandler<PacketJoinSession, IMessage> {

        @Override
        public IMessage onMessage(PacketJoinSession msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            SharedSession session = SharedSession.sessions.get(msg.sessionId);
            if (session == null) {
                player.addChatMessage(err("talkwith.session.not_found"));
                return null;
            }
            if (session.hasPlayer(player.getUniqueID())) {
                player.addChatMessage(err("talkwith.session.already_in_session"));
                return null;
            }
            session.players.add(player.getUniqueID());
            player.addChatMessage(okf("talkwith.session.joined", msg.sessionId));
            PacketHandler.INSTANCE.sendTo(new PacketOpenGui(msg.sessionId), player);
            return null;
        }
    }
}
