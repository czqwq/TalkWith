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

            // Resolve by name first, then fall back to exact session-ID match
            SharedSession session = null;
            for (SharedSession s : SharedSession.sessions.values()) {
                if (!s.sessionName.isEmpty() && s.sessionName.equalsIgnoreCase(msg.sessionId)) {
                    session = s;
                    break;
                }
            }
            if (session == null) {
                session = SharedSession.sessions.get(msg.sessionId);
            }

            if (session == null) {
                player.addChatMessage(err("talkwith.session.not_found"));
                return null;
            }
            if (session.hasPlayer(player.getUniqueID())) {
                player.addChatMessage(err("talkwith.session.already_in_session"));
                return null;
            }
            session.players.add(player.getUniqueID());
            String displayName = session.sessionName.isEmpty() ? session.sessionId : session.sessionName;
            player.addChatMessage(okf("talkwith.session.joined", displayName));
            PacketHandler.INSTANCE.sendTo(new PacketOpenGui(session.sessionId), player);
            // Send recent history so the joining player has context
            for (String[] entry : session.recentMessages) {
                PacketHandler.INSTANCE.sendTo(new PacketSessionBroadcast(entry[0], entry[1], entry[2]), player);
            }
            return null;
        }
    }
}
