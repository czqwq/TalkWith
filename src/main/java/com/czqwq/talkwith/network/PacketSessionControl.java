package com.czqwq.talkwith.network;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.ServerEventHandler;
import com.czqwq.talkwith.ai.SessionWorldData;
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

            // ----------------------------------------------------------------
            // Actions that don't require an existing session
            // ----------------------------------------------------------------

            if ("share".equals(msg.action) || "invite".equals(msg.action)) {
                EntityPlayerMP targetPlayer = server.getConfigurationManager()
                    .func_152612_a(msg.target);
                if (targetPlayer == null) {
                    player.addChatMessage(errf("talkwith.session.player_not_found", msg.target));
                    return null;
                }
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
                // Record the invitation so the target player can join a private session
                session.invitedPlayers.add(targetPlayer.getUniqueID());
                SessionWorldData.save();
                return null;
            }

            if ("server_create".equals(msg.action)) {
                if (getPlayerSession(playerUuid) != null) {
                    player.addChatMessage(err("talkwith.session.already_in"));
                    return null;
                }
                // Name is now mandatory — reject empty names on the server side too
                String proposedName = (msg.target != null) ? msg.target.trim() : "";
                if (proposedName.isEmpty()) {
                    player.addChatMessage(err("talkwith.session.name_required"));
                    return null;
                }
                for (SharedSession s : SharedSession.sessions.values()) {
                    if (proposedName.equals(s.sessionName)) {
                        player.addChatMessage(errf("talkwith.session.name_taken", proposedName));
                        return null;
                    }
                }
                // Initialise with the server's config defaults so the session works out of the box
                SharedSession newSession = new SharedSession(
                    playerUuid,
                    playerName,
                    Config.baseUrl,
                    Config.apiKey,
                    Config.model);
                newSession.sessionName = proposedName;
                SharedSession.sessions.put(newSession.sessionId, newSession);
                player.addChatMessage(okf("talkwith.session.created", proposedName));
                PacketHandler.INSTANCE.sendTo(new PacketOpenGui(newSession.sessionId), player);
                SessionWorldData.save();
                return null;
            }

            if ("list".equals(msg.action)) {
                if (SharedSession.sessions.isEmpty()) {
                    player.addChatMessage(ok("talkwith.session.list_empty"));
                } else {
                    java.util.List<SharedSession> sorted = new java.util.ArrayList<>(SharedSession.sessions.values());
                    sorted.sort((a, b) -> Long.compare(b.lastActivity, a.lastActivity));
                    player.addChatMessage(okf("talkwith.session.list_header", sorted.size()));
                    for (SharedSession s : sorted) {
                        String nameOrId = s.sessionName.isEmpty() ? s.sessionId : s.sessionName;
                        player.addChatMessage(
                            okf("talkwith.session.list_entry", nameOrId, s.ownerName, s.players.size()));
                    }
                }
                return null;
            }

            if ("info".equals(msg.action)) {
                SharedSession infoSession = getPlayerSession(playerUuid);
                if (infoSession == null) {
                    player.addChatMessage(ok("talkwith.session.info_none"));
                } else {
                    String nameOrId = infoSession.sessionName.isEmpty() ? infoSession.sessionId
                        : infoSession.sessionName;
                    String visibilityKey = infoSession.isPublic ? "talkwith.session.visibility.public"
                        : "talkwith.session.visibility.private";
                    player.addChatMessage(
                        okf(
                            "talkwith.session.info",
                            nameOrId,
                            infoSession.ownerName,
                            infoSession.players.size(),
                            infoSession.moderators.size(),
                            infoSession.sessionModel,
                            new ChatComponentTranslation(visibilityKey)));
                }
                return null;
            }

            if ("status_info".equals(msg.action)) {
                SharedSession s = getPlayerSession(playerUuid);
                if (s == null) {
                    player.addChatMessage(ok("talkwith.session.info_none"));
                    return null;
                }
                boolean isOwner = s.ownerUuid.equals(playerUuid);
                boolean isMod = !isOwner && s.isMod(playerUuid);
                boolean isSingleOverride = ServerEventHandler.singleModeOverride.contains(playerUuid);
                String nameOrId = s.sessionName.isEmpty() ? s.sessionId : s.sessionName;
                String roleKey = isOwner ? "talkwith.status.role.owner"
                    : isMod ? "talkwith.status.role.mod" : "talkwith.status.role.member";
                // Being in a session = multi mode by default; single_override means the player
                // explicitly switched back to local AI with /talkwith switch single.
                String modeKey = isSingleOverride ? "talkwith.status.mode.single_override"
                    : "talkwith.status.mode.multi";
                // Send each field as a separate message so that long Chinese text doesn't
                // overflow a single chat line (each ChatComponentTranslation is self-contained).
                player.addChatMessage(
                    okf(
                        "talkwith.status.mode_role",
                        new ChatComponentTranslation(modeKey),
                        new ChatComponentTranslation(roleKey)));
                player.addChatMessage(okf("talkwith.status.session_name", nameOrId));
                player.addChatMessage(okf("talkwith.status.session_members", s.players.size()));
                player.addChatMessage(okf("talkwith.status.session_prompt", s.sessionPromptFile));
                return null;
            }

            // cfg_list_prompts: list available prompt JSON files in config/talkwith/
            if ("cfg_list_prompts".equals(msg.action)) {
                List<String> files = Config.listPromptFiles();
                if (files.isEmpty()) {
                    player.addChatMessage(ok("talkwith.config.prompts_list_empty"));
                } else {
                    player.addChatMessage(okf("talkwith.config.prompts_list_header", files.size()));
                    for (String f : files) {
                        player.addChatMessage(new ChatComponentText("§7 - §f" + f));
                    }
                }
                return null;
            }

            // takeover_toggle: toggle takeover mode (all chat intercepted, no ">" prefix needed)
            if ("takeover_toggle".equals(msg.action)) {
                if (ServerEventHandler.takeoverPlayers.contains(playerUuid)) {
                    ServerEventHandler.takeoverPlayers.remove(playerUuid);
                    player.addChatMessage(ok("talkwith.takeover.disabled"));
                } else {
                    ServerEventHandler.takeoverPlayers.add(playerUuid);
                    String mode = ServerEventHandler.chatModes.getOrDefault(playerUuid, "ai");
                    player.addChatMessage(okf("talkwith.takeover.enabled", mode));
                }
                return null;
            }

            // chat_mode: set chat mode (ai/group/public) for takeover routing
            if ("chat_mode".equals(msg.action)) {
                String mode = msg.target != null ? msg.target.toLowerCase() : "ai";
                if (!mode.equals("group") && !mode.equals("public") && !mode.equals("ai")) {
                    player.addChatMessage(err("talkwith.chat.usage"));
                    return null;
                }
                ServerEventHandler.chatModes.put(playerUuid, mode);
                player.addChatMessage(ok("talkwith.takeover.chat_mode." + mode));
                return null;
            }

            // request: player requests to join a private session (target = session name or id)
            if ("request".equals(msg.action)) {
                if (msg.target == null || msg.target.trim()
                    .isEmpty()) {
                    player.addChatMessage(err("talkwith.session.request_usage"));
                    return null;
                }
                // Resolve target session by name then by id
                SharedSession targetSession = null;
                for (SharedSession s : SharedSession.sessions.values()) {
                    if (!s.sessionName.isEmpty() && s.sessionName.equalsIgnoreCase(msg.target)) {
                        targetSession = s;
                        break;
                    }
                }
                if (targetSession == null) {
                    targetSession = SharedSession.sessions.get(msg.target);
                }
                if (targetSession == null) {
                    player.addChatMessage(errf("talkwith.session.not_found_named", msg.target));
                    return null;
                }
                if (targetSession.isPublic) {
                    player.addChatMessage(err("talkwith.session.request_public"));
                    return null;
                }
                if (targetSession.hasPlayer(playerUuid)) {
                    player.addChatMessage(err("talkwith.session.already_in_session"));
                    return null;
                }
                if (targetSession.invitedPlayers.contains(playerUuid)) {
                    // Player already has an invite — tell them to join directly
                    player.addChatMessage(err("talkwith.session.request_already_invited"));
                    return null;
                }
                targetSession.joinRequests.add(playerUuid);
                SessionWorldData.save();
                player.addChatMessage(
                    okf(
                        "talkwith.session.request_sent",
                        targetSession.sessionName.isEmpty() ? targetSession.sessionId : targetSession.sessionName));
                // Notify online owner and mods
                for (UUID modUuid : targetSession.players) {
                    if (!targetSession.isOwnerOrMod(modUuid)) continue;
                    EntityPlayerMP modPlayer = getPlayerByUUID(server, modUuid);
                    if (modPlayer != null) {
                        modPlayer.addChatMessage(okf("talkwith.session.request_received", playerName));
                    }
                }
                return null;
            }

            // ----------------------------------------------------------------
            // Actions that require an existing session
            // ----------------------------------------------------------------

            SharedSession session = getPlayerSession(playerUuid);

            if (session == null && !"delete".equals(msg.action)) {
                player.addChatMessage(err("talkwith.session.not_found"));
                return null;
            }

            switch (msg.action) {
                case "switch_single" -> {
                    // Enter single-mode override: > messages go to local AI
                    ServerEventHandler.singleModeOverride.add(playerUuid);
                    player.addChatMessage(ok("talkwith.switch.single.ok"));
                }
                case "switch_multi" -> {
                    // Return to session AI routing
                    ServerEventHandler.singleModeOverride.remove(playerUuid);
                    player.addChatMessage(ok("talkwith.switch.multi.ok"));
                }
                case "leave" -> {
                    if (session.ownerUuid.equals(playerUuid)) {
                        UUID newOwnerUuid = null;
                        String newOwnerName = null;
                        // Prefer an online member for immediate notification
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
                        // If no online candidate, pick any offline member so the session survives
                        if (newOwnerUuid == null) {
                            for (UUID uuid : session.players) {
                                if (!uuid.equals(playerUuid)) {
                                    newOwnerUuid = uuid;
                                    // Name will be updated when the new owner next logs in
                                    break;
                                }
                            }
                        }
                        session.players.remove(playerUuid);
                        if (newOwnerUuid != null) {
                            session.ownerUuid = newOwnerUuid;
                            if (newOwnerName != null) session.ownerName = newOwnerName;
                            SessionWorldData.save();
                            EntityPlayerMP newOwnerPlayer = getPlayerByUUID(server, newOwnerUuid);
                            if (newOwnerPlayer != null) {
                                newOwnerPlayer.addChatMessage(ok("talkwith.session.owner_transferred"));
                            }
                        } else {
                            // Owner was the only member: delete the session
                            SharedSession.sessions.remove(session.sessionId);
                            SessionWorldData.save();
                        }
                    } else {
                        session.players.remove(playerUuid);
                        SessionWorldData.save();
                    }
                    ServerEventHandler.clearPlayerState(playerUuid);
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
                        SessionWorldData.save();
                        // Notify all members; the owner is included in session.players so
                        // their PacketOpenGui("") and message are sent from within the loop.
                        for (UUID uuid : session.players) {
                            ServerEventHandler.clearPlayerState(uuid);
                            EntityPlayerMP member = getPlayerByUUID(server, uuid);
                            if (member != null) {
                                if (uuid.equals(playerUuid)) {
                                    // Owner gets the success message instead of the generic "closed" one
                                    member.addChatMessage(ok("talkwith.session.delete_ok"));
                                } else {
                                    member.addChatMessage(err("talkwith.session.closed"));
                                }
                                PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), member);
                            }
                        }
                    }
                    // If the owner was not in session.players for some reason, still clear their GUI
                    if (session == null || !session.players.contains(playerUuid)) {
                        PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), player);
                        player.addChatMessage(ok("talkwith.session.delete_ok"));
                    }
                }
                case "setting_model" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.sessionModel = msg.target;
                    SessionWorldData.save();
                    player.addChatMessage(okf("talkwith.session.setting_model", msg.target));
                }
                case "setting_baseurl" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.ownerBaseUrl = msg.target;
                    SessionWorldData.save();
                    player.addChatMessage(okf("talkwith.session.setting_baseurl", msg.target));
                }
                case "setting_apikey" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.ownerApiKey = msg.target;
                    SessionWorldData.save();
                    player.addChatMessage(ok("talkwith.session.setting_apikey"));
                }
                case "cfg_prompt_file" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    String filename = Config.sanitizePromptFilename(msg.target);
                    // Ensure the file exists (creates default if missing)
                    Config.loadPromptFromFile(filename);
                    session.sessionPromptFile = filename;
                    SessionWorldData.save();
                    player.addChatMessage(okf("talkwith.config.prompt_file.set", filename));
                }
                case "mute" -> {
                    if (!session.isOwnerOrMod(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_or_mod_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer != null) {
                        session.mutedPlayers.add(targetPlayer.getUniqueID());
                        SessionWorldData.save();
                        player.addChatMessage(okf("talkwith.session.muted_player", msg.target));
                    }
                }
                case "unmute" -> {
                    if (!session.isOwnerOrMod(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_or_mod_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer != null) {
                        session.mutedPlayers.remove(targetPlayer.getUniqueID());
                        SessionWorldData.save();
                        player.addChatMessage(okf("talkwith.session.unmuted_player", msg.target));
                    }
                }
                case "kick" -> {
                    if (!session.isOwnerOrMod(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_or_mod_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer != null) {
                        session.players.remove(targetPlayer.getUniqueID());
                        SessionWorldData.save();
                        ServerEventHandler.clearPlayerState(targetPlayer.getUniqueID());
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
                        SessionWorldData.save();
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
                    SessionWorldData.save();
                    // Notify all members; owner is in session.players so handled in the loop.
                    for (UUID uuid : session.players) {
                        ServerEventHandler.clearPlayerState(uuid);
                        EntityPlayerMP member = getPlayerByUUID(server, uuid);
                        if (member != null) {
                            if (uuid.equals(playerUuid)) {
                                member.addChatMessage(ok("talkwith.session.close_ok"));
                            } else {
                                member.addChatMessage(err("talkwith.session.closed"));
                            }
                            PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), member);
                        }
                    }
                    // If owner is not in session.players for any reason, still clear their GUI
                    if (!session.players.contains(playerUuid)) {
                        player.addChatMessage(ok("talkwith.session.close_ok"));
                        PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), player);
                    }
                }
                case "history_clear" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.session.clear();
                    session.recentMessages.clear();
                    SessionWorldData.save();
                    player.addChatMessage(ok("talkwith.session.history_cleared"));
                }
                case "rename" -> {
                    if (!session.isOwnerOrMod(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_or_mod_only"));
                        return null;
                    }
                    String newName = msg.target != null ? msg.target.trim() : "";
                    if (newName.isEmpty()) {
                        player.addChatMessage(err("talkwith.session.rename_usage"));
                        return null;
                    }
                    for (SharedSession s : SharedSession.sessions.values()) {
                        if (!s.sessionId.equals(session.sessionId) && newName.equals(s.sessionName)) {
                            player.addChatMessage(errf("talkwith.session.name_taken", newName));
                            return null;
                        }
                    }
                    session.sessionName = newName;
                    SessionWorldData.save();
                    player.addChatMessage(okf("talkwith.session.renamed", newName));
                }
                case "setmod" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer == null) {
                        player.addChatMessage(errf("talkwith.session.player_not_found", msg.target));
                        return null;
                    }
                    UUID targetUuid = targetPlayer.getUniqueID();
                    if (targetUuid.equals(session.ownerUuid)) {
                        player.addChatMessage(err("talkwith.session.setmod_owner"));
                        return null;
                    }
                    session.moderators.add(targetUuid);
                    SessionWorldData.save();
                    player.addChatMessage(okf("talkwith.session.mod_added", msg.target));
                    targetPlayer.addChatMessage(
                        okf(
                            "talkwith.session.you_are_mod",
                            session.sessionName.isEmpty() ? session.sessionId : session.sessionName));
                }
                case "removemod" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer == null) {
                        player.addChatMessage(errf("talkwith.session.player_not_found", msg.target));
                        return null;
                    }
                    session.moderators.remove(targetPlayer.getUniqueID());
                    SessionWorldData.save();
                    player.addChatMessage(okf("talkwith.session.mod_removed", msg.target));
                }
                case "accept" -> {
                    if (!session.isOwnerOrMod(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_or_mod_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer == null) {
                        player.addChatMessage(errf("talkwith.session.player_not_found", msg.target));
                        return null;
                    }
                    UUID targetUuid = targetPlayer.getUniqueID();
                    if (!session.joinRequests.contains(targetUuid)) {
                        player.addChatMessage(errf("talkwith.session.no_join_request", msg.target));
                        return null;
                    }
                    session.joinRequests.remove(targetUuid);
                    session.invitedPlayers.remove(targetUuid);
                    session.players.add(targetUuid);
                    SessionWorldData.save();
                    String displayName = session.sessionName.isEmpty() ? session.sessionId : session.sessionName;
                    player.addChatMessage(okf("talkwith.session.request_accepted_by_mod", msg.target));
                    targetPlayer.addChatMessage(okf("talkwith.session.request_accepted", displayName));
                    PacketHandler.INSTANCE.sendTo(new PacketOpenGui(session.sessionId), targetPlayer);
                    for (String[] entry : session.recentMessages) {
                        PacketHandler.INSTANCE
                            .sendTo(new PacketSessionBroadcast(entry[0], entry[1], entry[2]), targetPlayer);
                    }
                }
                case "deny" -> {
                    if (!session.isOwnerOrMod(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_or_mod_only"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer == null) {
                        player.addChatMessage(errf("talkwith.session.player_not_found", msg.target));
                        return null;
                    }
                    UUID targetUuid = targetPlayer.getUniqueID();
                    if (!session.joinRequests.contains(targetUuid)) {
                        player.addChatMessage(errf("talkwith.session.no_join_request", msg.target));
                        return null;
                    }
                    session.joinRequests.remove(targetUuid);
                    SessionWorldData.save();
                    player.addChatMessage(okf("talkwith.session.request_denied_by_mod", msg.target));
                    targetPlayer.addChatMessage(
                        okf(
                            "talkwith.session.request_denied",
                            session.sessionName.isEmpty() ? session.sessionId : session.sessionName));
                }
                case "set_public" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.isPublic = true;
                    SessionWorldData.save();
                    player.addChatMessage(ok("talkwith.session.set_public"));
                }
                case "set_private" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    session.isPublic = false;
                    SessionWorldData.save();
                    player.addChatMessage(ok("talkwith.session.set_private"));
                }
                case "transfer" -> {
                    if (!session.ownerUuid.equals(playerUuid)) {
                        player.addChatMessage(err("talkwith.session.owner_only"));
                        return null;
                    }
                    if (msg.target == null || msg.target.trim()
                        .isEmpty()) {
                        player.addChatMessage(err("talkwith.session.transfer_usage"));
                        return null;
                    }
                    EntityPlayerMP targetPlayer = server.getConfigurationManager()
                        .func_152612_a(msg.target);
                    if (targetPlayer == null) {
                        player.addChatMessage(errf("talkwith.session.player_not_found", msg.target));
                        return null;
                    }
                    UUID targetUuid = targetPlayer.getUniqueID();
                    if (!session.hasPlayer(targetUuid)) {
                        player.addChatMessage(errf("talkwith.session.transfer_not_in_session", msg.target));
                        return null;
                    }
                    session.ownerUuid = targetUuid;
                    session.ownerName = targetPlayer.getCommandSenderName();
                    // Transfer the owner's API credentials to the new owner's config defaults
                    session.ownerBaseUrl = Config.baseUrl;
                    session.ownerApiKey = Config.apiKey;
                    SessionWorldData.save();
                    player.addChatMessage(okf("talkwith.session.transferred_to", msg.target));
                    targetPlayer.addChatMessage(ok("talkwith.session.owner_transferred"));
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
