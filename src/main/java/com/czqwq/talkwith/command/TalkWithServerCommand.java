package com.czqwq.talkwith.command;

import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.ai.SharedSession;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketShareInvite;

public class TalkWithServerCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "talkwith";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/talkwith <share|mute|unmute|kick|cooldown|close>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP player)) {
            sender.addChatMessage(new ChatComponentText("§c[TalkWith]§r This command must be run by a player."));
            return;
        }

        if (args.length == 0) {
            player.addChatMessage(new ChatComponentText("§c[TalkWith]§r " + getCommandUsage(sender)));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "share" -> {
                if (args.length < 2) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Usage: /talkwith share <playerName>"));
                    return;
                }
                EntityPlayerMP target = MinecraftServer.getServer()
                    .getConfigurationManager()
                    .func_152612_a(args[1]);
                if (target == null) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Player not found: " + args[1]));
                    return;
                }
                SharedSession session = new SharedSession(
                    player.getUniqueID(),
                    player.getCommandSenderName(),
                    Config.baseUrl,
                    Config.apiKey);
                SharedSession.sessions.put(session.sessionId, session);
                player.addChatMessage(new ChatComponentText("§a[TalkWith]§r Session created: " + session.sessionId));
                PacketHandler.INSTANCE
                    .sendTo(new PacketShareInvite(player.getCommandSenderName(), session.sessionId), target);
            }
            case "mute" -> {
                if (args.length < 2) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Usage: /talkwith mute <playerName>"));
                    return;
                }
                SharedSession session = getOwnedSession(player);
                if (session == null) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You don't own a session."));
                    return;
                }
                EntityPlayerMP target = MinecraftServer.getServer()
                    .getConfigurationManager()
                    .func_152612_a(args[1]);
                if (target != null) {
                    session.mutedPlayers.add(target.getUniqueID());
                    player.addChatMessage(new ChatComponentText("§a[TalkWith]§r Muted " + args[1] + "."));
                } else {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Player not found: " + args[1]));
                }
            }
            case "unmute" -> {
                if (args.length < 2) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Usage: /talkwith unmute <playerName>"));
                    return;
                }
                SharedSession session = getOwnedSession(player);
                if (session == null) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You don't own a session."));
                    return;
                }
                EntityPlayerMP target = MinecraftServer.getServer()
                    .getConfigurationManager()
                    .func_152612_a(args[1]);
                if (target != null) {
                    session.mutedPlayers.remove(target.getUniqueID());
                    player.addChatMessage(new ChatComponentText("§a[TalkWith]§r Unmuted " + args[1] + "."));
                } else {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Player not found: " + args[1]));
                }
            }
            case "kick" -> {
                if (args.length < 2) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Usage: /talkwith kick <playerName>"));
                    return;
                }
                SharedSession session = getOwnedSession(player);
                if (session == null) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You don't own a session."));
                    return;
                }
                EntityPlayerMP target = MinecraftServer.getServer()
                    .getConfigurationManager()
                    .func_152612_a(args[1]);
                if (target != null) {
                    session.players.remove(target.getUniqueID());
                    target.addChatMessage(new ChatComponentText("§c[TalkWith]§r You were kicked from the session."));
                    player.addChatMessage(new ChatComponentText("§a[TalkWith]§r Kicked " + args[1] + "."));
                } else {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Player not found: " + args[1]));
                }
            }
            case "cooldown" -> {
                if (args.length < 2) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Usage: /talkwith cooldown <seconds>"));
                    return;
                }
                SharedSession session = getOwnedSession(player);
                if (session == null) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You don't own a session."));
                    return;
                }
                try {
                    session.cooldown = Integer.parseInt(args[1]);
                    player.addChatMessage(
                        new ChatComponentText("§a[TalkWith]§r Cooldown set to " + session.cooldown + "s."));
                } catch (NumberFormatException e) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r Invalid value: " + args[1]));
                }
            }
            case "close" -> {
                SharedSession session = getOwnedSession(player);
                if (session == null) {
                    player.addChatMessage(new ChatComponentText("§c[TalkWith]§r You don't own a session."));
                    return;
                }
                SharedSession.sessions.remove(session.sessionId);
                for (UUID uuid : session.players) {
                    EntityPlayerMP member = getPlayerByUUID(uuid);
                    if (member != null) {
                        member.addChatMessage(new ChatComponentText("§c[TalkWith]§r Session closed."));
                    }
                }
            }
            default -> player.addChatMessage(new ChatComponentText("§c[TalkWith]§r " + getCommandUsage(sender)));
        }
    }

    private SharedSession getOwnedSession(EntityPlayerMP player) {
        UUID uuid = player.getUniqueID();
        for (SharedSession s : SharedSession.sessions.values()) {
            if (s.ownerUuid.equals(uuid)) return s;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static EntityPlayerMP getPlayerByUUID(UUID uuid) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) obj;
            if (p.getUniqueID()
                .equals(uuid)) return p;
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "share", "mute", "unmute", "kick", "cooldown", "close");
        }
        return null;
    }
}
