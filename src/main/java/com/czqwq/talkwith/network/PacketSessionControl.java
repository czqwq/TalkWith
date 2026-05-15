package com.czqwq.talkwith.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.czqwq.talkwith.ai.SharedSession;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketSessionControl implements IMessage {

    public String action;
    public String target;

    public PacketSessionControl() {}

    public PacketSessionControl(String action, String target) {
        this.action = action;
        this.target = target != null ? target : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        action = ByteBufUtils.readUTF8String(buf);
        target = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, action);
        ByteBufUtils.writeUTF8String(buf, target != null ? target : "");
    }

    public static class Handler implements IMessageHandler<PacketSessionControl, IMessage> {

        @Override
        public IMessage onMessage(PacketSessionControl msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            UUID playerUuid = player.getUniqueID();
            MinecraftServer server = MinecraftServer.getServer();

            // Find the session this player owns
            SharedSession session = null;
            for (SharedSession s : SharedSession.sessions.values()) {
                if (s.ownerUuid.equals(playerUuid) || s.hasPlayer(playerUuid)) {
                    session = s;
                    break;
                }
            }

            if (session == null) {
                player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You are not in any session."));
                return null;
            }

            switch (msg.action) {
                case "single" -> {
                    session.players.remove(playerUuid);
                    if (session.players.isEmpty() || session.ownerUuid.equals(playerUuid)) {
                        SharedSession.sessions.remove(session.sessionId);
                    }
                    player.addChatMessage(
                        new ChatComponentText("§a[TalkWith]§r Left shared session. Back to single mode."));
                }
                case "mute" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(
                            new ChatComponentText("§c[TalkWith]§r Only the session owner can do that."));
                        return null;
                    }
                    EntityPlayerMP target = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (target != null) {
                        session.mutedPlayers.add(target.getUniqueID());
                        player.addChatMessage(new ChatComponentText("§a[TalkWith]§r Muted " + msg.target + "."));
                    }
                }
                case "unmute" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(
                            new ChatComponentText("§c[TalkWith]§r Only the session owner can do that."));
                        return null;
                    }
                    EntityPlayerMP target = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (target != null) {
                        session.mutedPlayers.remove(target.getUniqueID());
                        player.addChatMessage(new ChatComponentText("§a[TalkWith]§r Unmuted " + msg.target + "."));
                    }
                }
                case "kick" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(
                            new ChatComponentText("§c[TalkWith]§r Only the session owner can do that."));
                        return null;
                    }
                    EntityPlayerMP target = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (target != null) {
                        session.players.remove(target.getUniqueID());
                        target
                            .addChatMessage(new ChatComponentText("§c[TalkWith]§r You were kicked from the session."));
                        player.addChatMessage(new ChatComponentText("§a[TalkWith]§r Kicked " + msg.target + "."));
                    }
                }
                case "cooldown" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(
                            new ChatComponentText("§c[TalkWith]§r Only the session owner can do that."));
                        return null;
                    }
                    try {
                        session.cooldown = Integer.parseInt(msg.target);
                        player.addChatMessage(
                            new ChatComponentText("§a[TalkWith]§r Cooldown set to " + session.cooldown + "s."));
                    } catch (NumberFormatException e) {
                        player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Invalid cooldown value."));
                    }
                }
                case "close" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(
                            new ChatComponentText("§c[TalkWith]§r Only the session owner can close the session."));
                        return null;
                    }
                    SharedSession.sessions.remove(session.sessionId);
                    for (UUID uuid : session.players) {
                        EntityPlayerMP member = getPlayerByUUID(server, uuid);
                        if (member != null) {
                            member.addChatMessage(new ChatComponentText("§c[TalkWith]§r Session closed."));
                        }
                    }
                }
                default -> player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Unknown action: " + msg.action));
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private static EntityPlayerMP getPlayerByUUID(MinecraftServer server, UUID uuid) {
            for (Object obj : server.getConfigurationManager().playerEntityList) {
                EntityPlayerMP p = (EntityPlayerMP) obj;
                if (p.getUniqueID()
                    .equals(uuid)) return p;
            }
            return null;
        }
    }
}
