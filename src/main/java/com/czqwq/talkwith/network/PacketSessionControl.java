package com.czqwq.talkwith.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import com.czqwq.talkwith.Config;
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

    private static IChatComponent err(String key) {
        return new ChatComponentText("§c[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key));
    }

    private static IChatComponent ok(String key) {
        return new ChatComponentText("§a[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key));
    }

    private static IChatComponent okf(String key, Object... args) {
        return new ChatComponentText("§a[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key, args));
    }

    private static IChatComponent errf(String key, Object... args) {
        return new ChatComponentText("§c[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key, args));
    }

    public static class Handler implements IMessageHandler<PacketSessionControl, IMessage> {

        @Override
        public IMessage onMessage(PacketSessionControl msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            UUID playerUuid = player.getUniqueID();
            String playerName = player.getCommandSenderName();
            MinecraftServer server = MinecraftServer.getServer();

            // --- Actions that create a session or don't require one ---

            if ("share".equals(msg.action) || "invite".equals(msg.action)) {
                EntityPlayerMP targetPlayer = server.getConfigurationManager()
                    .func_152612_a(msg.target);
                if (targetPlayer == null) {
                    player.addChatMessage(errf("talkwith.session.player_not_found", msg.target));
                    return null;
                }
                // Invite to existing owned session, or create a new one
                SharedSession existing = getOwnedSession(playerUuid);
                SharedSession session;
                if (existing != null) {
                    session = existing;
                } else {
                    session = new SharedSession(playerUuid, playerName, Config.baseUrl, Config.apiKey, Config.model);
                    SharedSession.sessions.put(session.sessionId, session);
                    player.addChatMessage(okf("talkwith.session.created", session.sessionId));
                    PacketHandler.INSTANCE.sendTo(new PacketOpenGui(session.sessionId), player);
                }
                PacketHandler.INSTANCE.sendTo(new PacketShareInvite(playerName, session.sessionId), targetPlayer);
                return null;
            }

            if ("server_create".equals(msg.action)) {
                // Reject if player is already in any session
                if (getPlayerSession(playerUuid) != null) {
                    player.addChatMessage(err("talkwith.session.already_in"));
                    return null;
                }
                SharedSession newSession = new SharedSession(playerUuid, playerName, "", "", "");
                SharedSession.sessions.put(newSession.sessionId, newSession);
                player.addChatMessage(okf("talkwith.session.created", newSession.sessionId));
                PacketHandler.INSTANCE.sendTo(new PacketOpenGui(newSession.sessionId), player);
                return null;
            }

            if ("client_create".equals(msg.action)) {
                // Close any session the player owns
                SharedSession owned = getOwnedSession(playerUuid);
                if (owned != null) {
                    SharedSession.sessions.remove(owned.sessionId);
                    for (UUID uuid : owned.players) {
                        if (!uuid.equals(playerUuid)) {
                            EntityPlayerMP member = getPlayerByUUID(server, uuid);
                            if (member != null) {
                                member.addChatMessage(err("talkwith.session.closed"));
                                PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), member);
                            }
                        }
                    }
                }
                // Also leave any session the player joined as a non-owner member
                SharedSession joined = getPlayerSession(playerUuid);
                if (joined != null) {
                    joined.players.remove(playerUuid);
                }
                PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), player);
                player.addChatMessage(ok("talkwith.session.delete_ok"));
                return null;
            }

            if ("list".equals(msg.action)) {
                if (SharedSession.sessions.isEmpty()) {
                    player.addChatMessage(ok("talkwith.session.list_empty"));
                } else {
                    player.addChatMessage(okf("talkwith.session.list_header", SharedSession.sessions.size()));
                    for (SharedSession s : SharedSession.sessions.values()) {
                        player.addChatMessage(
                            okf("talkwith.session.list_entry", s.sessionId, s.ownerName, s.players.size()));
                    }
                }
                return null;
            }

            if ("info".equals(msg.action)) {
                SharedSession infoSession = getPlayerSession(playerUuid);
                if (infoSession == null) {
                    player.addChatMessage(ok("talkwith.session.info_none"));
                } else {
                    player.addChatMessage(
                        okf(
                            "talkwith.session.info",
                            infoSession.sessionId,
                            infoSession.ownerName,
                            infoSession.players.size(),
                            infoSession.sessionModel));
                }
                return null;
            }

            // --- Actions that require an existing session ---

            SharedSession session = getPlayerSession(playerUuid);

            if (session == null && !"delete".equals(msg.action)) {
                player.addChatMessage(err("talkwith.session.not_found"));
                return null;
            }

            switch (msg.action) {
                case "single" -> {
                    session.players.remove(playerUuid);
                    if (session.players.isEmpty() || session.ownerUuid.equals(playerUuid)) {
                        SharedSession.sessions.remove(session.sessionId);
                    }
                    player.addChatMessage(ok("talkwith.session.left"));
                    PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), player);
                }
                case "leave" -> {
                    if (session.ownerUuid.equals(playerUuid)) {
                        // Owner leaving: transfer to next member or close
                        UUID newOwnerUuid = null;
                        String newOwnerName = null;
                        for (UUID uuid : session.players) {
                            if (!uuid.equals(playerUuid)) {
                                EntityPlayerMP candidate = getPlayerByUUID(server, uuid);
                                if (candidate != null) {
                                    newOwnerUuid = uuid;
                                    newOwnerName = candidate.getCommandSenderName();
                                    break;
                                }
                            }
                        }
                        session.players.remove(playerUuid);
                        if (newOwnerUuid != null) {
                            session.ownerUuid = newOwnerUuid;
                            session.ownerName = newOwnerName;
                            EntityPlayerMP newOwnerPlayer = getPlayerByUUID(server, newOwnerUuid);
                            if (newOwnerPlayer != null) {
                                newOwnerPlayer.addChatMessage(ok("talkwith.session.owner_transferred"));
                            }
                        } else {
                            SharedSession.sessions.remove(session.sessionId);
                        }
                    } else {
                        // Non-owner leaving
                        session.players.remove(playerUuid);
                    }
                    player.addChatMessage(ok("talkwith.session.left"));
                    PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), player);
                }
                case "delete" -> {
                    if (session != null) {
                        if (!session.ownerUuid.equals(playerUuid)) {
                            player.addChatMessage(err("talkwith.session.owner_only"));
                            return null;
                        }
                        SharedSession.sessions.remove(session.sessionId);
                        for (UUID uuid : session.players) {
                            EntityPlayerMP member = getPlayerByUUID(server, uuid);
                            if (member != null) {
                                member.addChatMessage(err("talkwith.session.closed"));
                                PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), member);
                            }
                        }
                    }
                    PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), player);
                    player.addChatMessage(ok("talkwith.session.delete_ok"));
                }
                case "setting_model" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.sessionModel = msg.target;
                    player.addChatMessage(okf("talkwith.session.setting_model", msg.target));
                }
                case "setting_baseurl" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.ownerBaseUrl = msg.target;
                    player.addChatMessage(okf("talkwith.session.setting_baseurl", msg.target));
                }
                case "setting_apikey" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.ownerApiKey = msg.target;
                    player.addChatMessage(ok("talkwith.session.setting_apikey"));
                }
                case "mute" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer != null) {
                        session.mutedPlayers.add(targetPlayer.getUniqueID());
                        player.addChatMessage(okf("talkwith.session.muted_player", msg.target));
                    }
                }
                case "unmute" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer != null) {
                        session.mutedPlayers.remove(targetPlayer.getUniqueID());
                        player.addChatMessage(okf("talkwith.session.unmuted_player", msg.target));
                    }
                }
                case "kick" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer != null) {
                        session.players.remove(targetPlayer.getUniqueID());
                        targetPlayer.addChatMessage(err("talkwith.session.kicked"));
                        PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), targetPlayer);
                        player.addChatMessage(okf("talkwith.session.kicked_player", msg.target));
                    }
                }
                case "cooldown" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    try {
                        session.cooldown = Integer.parseInt(msg.target);
                        player.addChatMessage(okf("talkwith.session.cooldown_set", session.cooldown));
                    } catch (NumberFormatException e) {
                        player.addChatMessage(err("talkwith.session.invalid_cooldown"));
                    }
                }
                case "close" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.close_owner_only"));
                        return null;
                    }
                    SharedSession.sessions.remove(session.sessionId);
                    for (UUID uuid : session.players) {
                        EntityPlayerMP member = getPlayerByUUID(server, uuid);
                        if (member != null) {
                            member.addChatMessage(err("talkwith.session.closed"));
                            PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), member);
                        }
                    }
                    PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), player);
                }
                default -> player.addChatMessage(errf("talkwith.unknown_sub", msg.action));
            }
            return null;
        }

        private static SharedSession getOwnedSession(UUID playerUuid) {
            for (SharedSession s : SharedSession.sessions.values()) {
                if (s.ownerUuid.equals(playerUuid)) return s;
            }
            return null;
        }

        private static SharedSession getPlayerSession(UUID playerUuid) {
            for (SharedSession s : SharedSession.sessions.values()) {
                if (s.hasPlayer(playerUuid)) return s;
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
