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
import com.czqwq.talkwith.prompt.PromptStore;
import com.czqwq.talkwith.teams.Team;
import com.czqwq.talkwith.teams.TeamManager;
import com.czqwq.talkwith.teams.TeamSessionData;
import com.czqwq.talkwith.util.ServerPlayerUtils;

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

            // --- server_create ---
            if ("server_create".equals(msg.action)) {
                if (SharedSession.findByPlayer(playerUuid) != null) {
                    player.addChatMessage(err("talkwith.session.already_in"));
                    return null;
                }
                String proposedName = (msg.target != null) ? msg.target.trim() : "";
                if (proposedName.isEmpty()) {
                    player.addChatMessage(err("talkwith.session.name_required"));
                    return null;
                }
                if (SharedSession.findByName(proposedName) != null) {
                    player.addChatMessage(errf("talkwith.session.name_taken", proposedName));
                    return null;
                }
                SharedSession session = new SharedSession(playerUuid, playerName, "", "", Config.model);
                SharedSession.sessions.put(session.sessionId, session);
                session.sessionName = proposedName;
                player.addChatMessage(okf("talkwith.session.created", proposedName));
                PacketHandler.INSTANCE.sendTo(new PacketOpenGui(session.sessionId, false, session.sessionName), player);
                // Associate the session with the owner's team and sync it to all members.
                Team team = TeamManager.getTeamByPlayer(playerUuid);
                if (team != null) {
                    TeamSessionData.setSessionId(team, session.sessionId);
                }
                SessionWorldData.save();
                return null;
            }

            // --- list ---
            if ("list".equals(msg.action)) {
                if (SharedSession.sessions.isEmpty()) {
                    player.addChatMessage(ok("talkwith.session.list_empty"));
                } else {
                    player.addChatMessage(okf("talkwith.session.list_header", SharedSession.sessions.size()));
                    for (SharedSession s : SharedSession.sessions.values()) {
                        String name = s.sessionName.isEmpty() ? s.sessionId : s.sessionName;
                        player.addChatMessage(
                            new ChatComponentText("  - ").appendSibling(
                                new ChatComponentTranslation(
                                    "talkwith.session.list_entry",
                                    name,
                                    s.ownerName,
                                    s.players.size())));
                    }
                }
                return null;
            }

            // --- info ---
            if ("info".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(ok("talkwith.session.info_none"));
                    return null;
                }
                String nameOrId = s.sessionName.isEmpty() ? s.sessionId : s.sessionName;
                player.addChatMessage(
                    okf("talkwith.session.info", nameOrId, s.ownerName, s.players.size(), s.sessionModel));
                return null;
            }

            // --- status_info ---
            if ("status_info".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(ok("talkwith.session.info_none"));
                    return null;
                }
                boolean isOwner = s.ownerUuid.equals(playerUuid);
                boolean isSingleOverride = ServerEventHandler.singleModeOverride.contains(playerUuid);
                String roleKey = isOwner ? "talkwith.status.role.owner" : "talkwith.status.role.member";
                String modeKey = isSingleOverride ? "talkwith.status.mode.single_override"
                    : "talkwith.status.mode.multi";
                player.addChatMessage(
                    okf(
                        "talkwith.status.mode_role",
                        new ChatComponentTranslation(modeKey),
                        new ChatComponentTranslation(roleKey)));
                String nameOrId = s.sessionName.isEmpty() ? s.sessionId : s.sessionName;
                player.addChatMessage(okf("talkwith.status.session_name", nameOrId));
                player.addChatMessage(okf("talkwith.status.session_members", s.players.size()));
                if (s.sessionModel != null && !s.sessionModel.isEmpty()) {
                    player.addChatMessage(okf("talkwith.config.model.show", s.sessionModel));
                }
                if (s.sessionPromptFile != null && !s.sessionPromptFile.isEmpty()) {
                    player.addChatMessage(okf("talkwith.config.prompt_file.show", s.sessionPromptFile));
                }
                return null;
            }

            // --- setting_get (reply with current session AI settings for the settings GUI) ---
            if ("setting_get".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    return new PacketSessionSettings(
                        Config.model,
                        Config.baseUrl,
                        !Config.apiKey.isEmpty(),
                        Config.clientPromptFile);
                }
                boolean hasKey = s.ownerApiKey != null && !s.ownerApiKey.isEmpty();
                return new PacketSessionSettings(
                    s.sessionModel != null && !s.sessionModel.isEmpty() ? s.sessionModel : Config.model,
                    s.ownerBaseUrl != null && !s.ownerBaseUrl.isEmpty() ? s.ownerBaseUrl : Config.baseUrl,
                    hasKey,
                    s.sessionPromptFile != null && !s.sessionPromptFile.isEmpty() ? s.sessionPromptFile
                        : Config.clientPromptFile);
            }

            // --- cfg_list_prompts (chat feedback for the /talkwith config command) ---
            if ("cfg_list_prompts".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                List<String> files = PromptStore.listSession(s.sessionId);
                if (files.isEmpty()) {
                    player.addChatMessage(ok("talkwith.config.prompts_list_empty"));
                } else {
                    player.addChatMessage(okf("talkwith.config.prompts_list_header", files.size()));
                    for (String f : files) {
                        player.addChatMessage(new ChatComponentText("  §7- §f" + f));
                    }
                }
                return null;
            }

            // --- prompt_list (reply with the sender session's prompt files for the GUI) ---
            if ("prompt_list".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    return PacketPromptData.list(new java.util.ArrayList<String>());
                }
                return PacketPromptData.list(PromptStore.listSession(s.sessionId));
            }

            // --- prompt_read (reply with a prompt file's content for the GUI) ---
            if ("prompt_read".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                String filename = Config.sanitizePromptFilename(msg.target);
                return PacketPromptData.content(filename, PromptStore.readSession(s.sessionId, filename));
            }

            // --- cfg_prompt_file (owner only) ---
            if ("cfg_prompt_file".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                if (!s.ownerUuid.equals(playerUuid)) {
                    player.addChatMessage(err("talkwith.session.owner_only"));
                    return null;
                }
                String filename = Config.sanitizePromptFilename(msg.target);
                // Validate/create the file immediately so typos surface at set time.
                PromptStore.readSession(s.sessionId, filename);
                s.sessionPromptFile = filename;
                SessionWorldData.save();
                player.addChatMessage(okf("talkwith.config.prompt_file.set", filename));
                return null;
            }

            // --- Switch single/multi (session members only) ---
            if ("switch_single".equals(msg.action) || "switch_multi".equals(msg.action)) {
                if (SharedSession.findByPlayer(playerUuid) == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                if ("switch_single".equals(msg.action)) {
                    ServerEventHandler.singleModeOverride.add(playerUuid);
                    player.addChatMessage(ok("talkwith.switch.single.ok"));
                } else {
                    ServerEventHandler.singleModeOverride.remove(playerUuid);
                    player.addChatMessage(ok("talkwith.switch.multi.ok"));
                }
                return null;
            }

            // --- leave ---
            if ("leave".equals(msg.action)) {
                SharedSession session = SharedSession.findByPlayer(playerUuid);
                if (session == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                if (session.ownerUuid.equals(playerUuid)) {
                    UUID newOwnerUuid = null;
                    String newOwnerName = null;
                    for (UUID uuid : session.players) {
                        if (!uuid.equals(playerUuid)) {
                            EntityPlayerMP candidate = ServerPlayerUtils.getPlayerByUUID(server, uuid);
                            if (candidate != null) {
                                newOwnerUuid = uuid;
                                newOwnerName = candidate.getCommandSenderName();
                                break;
                            }
                        }
                    }
                    if (newOwnerUuid == null) {
                        for (UUID uuid : session.players) {
                            if (!uuid.equals(playerUuid)) {
                                newOwnerUuid = uuid;
                                break;
                            }
                        }
                    }
                    session.players.remove(playerUuid);
                    if (newOwnerUuid != null) {
                        session.ownerUuid = newOwnerUuid;
                        if (newOwnerName != null) session.ownerName = newOwnerName;
                        SessionWorldData.save();
                        EntityPlayerMP newOwnerPlayer = ServerPlayerUtils.getPlayerByUUID(server, newOwnerUuid);
                        if (newOwnerPlayer != null) {
                            newOwnerPlayer.addChatMessage(ok("talkwith.session.owner_transferred"));
                        }
                    } else {
                        clearSessionFromTeams(session);
                        PromptStore.deleteSessionDir(session.sessionId);
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
                return null;
            }

            // --- delete (owner only) ---
            if ("delete".equals(msg.action)) {
                SharedSession session = SharedSession.findByPlayer(playerUuid);
                if (session == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                if (!session.ownerUuid.equals(playerUuid)) {
                    player.addChatMessage(err("talkwith.session.owner_only"));
                    return null;
                }
                clearSessionFromTeams(session);
                PromptStore.deleteSessionDir(session.sessionId);
                SharedSession.sessions.remove(session.sessionId);
                SessionWorldData.save();
                for (UUID uuid : session.players) {
                    ServerEventHandler.clearPlayerState(uuid);
                    EntityPlayerMP member = ServerPlayerUtils.getPlayerByUUID(server, uuid);
                    if (member != null) {
                        member.addChatMessage(ok("talkwith.session.closed"));
                        PacketHandler.INSTANCE.sendTo(new PacketOpenGui(""), member);
                    }
                }
                return null;
            }

            // --- Model/URL/Key config (owner only) ---
            if ("setting_model".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                if (!s.ownerUuid.equals(playerUuid)) {
                    player.addChatMessage(err("talkwith.session.owner_only"));
                    return null;
                }
                s.sessionModel = msg.target;
                SessionWorldData.save();
                player.addChatMessage(okf("talkwith.model.set", msg.target));
                return null;
            }
            if ("setting_baseurl".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                if (!s.ownerUuid.equals(playerUuid)) {
                    player.addChatMessage(err("talkwith.session.owner_only"));
                    return null;
                }
                s.ownerBaseUrl = msg.target;
                SessionWorldData.save();
                player.addChatMessage(okf("talkwith.baseurl.set", msg.target));
                return null;
            }
            if ("setting_apikey".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                if (!s.ownerUuid.equals(playerUuid)) {
                    player.addChatMessage(err("talkwith.session.owner_only"));
                    return null;
                }
                s.ownerApiKey = msg.target;
                SessionWorldData.save();
                player.addChatMessage(ok("talkwith.api.key_updated"));
                return null;
            }

            // --- history_clear (owner only) ---
            if ("history_clear".equals(msg.action)) {
                SharedSession s = SharedSession.findByPlayer(playerUuid);
                if (s == null) {
                    player.addChatMessage(err("talkwith.session.not_found"));
                    return null;
                }
                if (!s.ownerUuid.equals(playerUuid)) {
                    player.addChatMessage(err("talkwith.session.owner_only"));
                    return null;
                }
                s.session.clear();
                s.recentMessages.clear();
                SessionWorldData.save();
                player.addChatMessage(ok("talkwith.session.history_cleared"));
                return null;
            }

            player.addChatMessage(errf("talkwith.unknown_sub", msg.action));
            return null;
        }

        /** Clears the session id from the teams of all session members (session is gone). */
        private static void clearSessionFromTeams(SharedSession session) {
            for (UUID uuid : session.players) {
                Team team = TeamManager.getTeamByPlayer(uuid);
                if (team != null) {
                    TeamSessionData.clearSessionId(team, session.sessionId);
                }
            }
        }
    }
}
