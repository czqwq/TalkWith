package com.czqwq.talkwith.teams;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.util.CommandUtils;

/**
 * Team command, registered twice: once as the player command ({@code /<teamCommandRoot>})
 * and once as the admin command ({@code /<teamCommandRoot>_admin}). The admin variant
 * resolves teams by name and bypasses role checks.
 */
public class TeamCommand extends CommandBase {

    private final boolean admin;

    public TeamCommand() {
        this(false);
    }

    public TeamCommand(boolean admin) {
        this.admin = admin;
    }

    @Override
    public String getCommandName() {
        return admin ? Config.teamCommandRoot + "_admin" : Config.teamCommandRoot;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return admin ? 2 : 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return admin ? "talkwith.chat.teams.admin.message.usage" : "talkwith.chat.teams.message.usage";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            sendUsage(sender);
            return;
        }
        if (admin) {
            processAdminCommand(sender, args);
        } else {
            processPlayerCommand(sender, args);
        }
    }

    // -------------------------------------------------------------------------
    // Player command
    // -------------------------------------------------------------------------

    private void processPlayerCommand(ICommandSender sender, String[] args) {
        switch (args[0].toLowerCase()) {
            case "rename" -> {
                if (args.length < 2) {
                    CommandUtils.error(sender, "talkwith.chat.teams.error.rename_usage");
                    return;
                }
                executeRename(sender, args[1]);
            }
            case "invite" -> {
                if (args.length < 2) {
                    CommandUtils.error(sender, "talkwith.chat.teams.error.invite_usage");
                    return;
                }
                executeInvite(sender, args[1]);
            }
            case "accept" -> executeAccept(sender, args.length > 1 ? args[1] : "");
            case "deny" -> executeDeny(sender, args.length > 1 ? args[1] : "");
            case "leave" -> executeLeave(sender);
            case "promote" -> {
                if (args.length < 2) {
                    CommandUtils.error(sender, "talkwith.chat.teams.error.promote_usage");
                    return;
                }
                executePromote(sender, args[1]);
            }
            case "demote" -> {
                if (args.length < 2) {
                    CommandUtils.error(sender, "talkwith.chat.teams.error.demote_usage");
                    return;
                }
                executeDemote(sender, args[1]);
            }
            case "kick" -> {
                if (args.length < 2) {
                    CommandUtils.error(sender, "talkwith.chat.teams.error.kick_usage");
                    return;
                }
                executeKick(sender, args[1]);
            }
            case "info" -> executeInfo(sender);
            case "merge" -> {
                if (args.length < 3) {
                    CommandUtils.error(sender, "talkwith.chat.teams.error.merge_usage");
                    return;
                }
                switch (args[1].toLowerCase()) {
                    case "request" -> executeMergeRequest(sender, args[2]);
                    case "cancel" -> executeMergeCancel(sender, args[2]);
                    case "accept" -> executeMergeAccept(sender, args[2]);
                    case "deny" -> executeMergeDeny(sender, args[2]);
                    default -> CommandUtils.error(sender, "talkwith.chat.teams.error.merge_usage");
                }
            }
            case "disband" -> executeDisband(sender);
            case "help" -> executeHelp(sender);
            default -> sendUsage(sender);
        }
    }

    private void executeRename(ICommandSender sender, String newName) {
        if (newName.length() > Team.MAX_TEAM_NAME_LENGTH) {
            CommandUtils.error(sender, "talkwith.chat.teams.message.team_name_too_long");
            return;
        }
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team team = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!team.isOwner(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_owner_rename");
            return;
        }

        String oldName = team.getTeamName();
        if (!TeamActions.onRename(team, oldName, newName, false, null)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.name_in_use");
        }
    }

    private void executeInvite(ICommandSender sender, String targetName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team team = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!team.isOfficer(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_officer_invite");
            return;
        }

        EntityPlayer target = player.worldObj.getPlayerEntityByName(targetName);
        if (target == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_online", targetName);
            return;
        }
        if (team.isMember(target.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.invite_teammate", targetName);
            return;
        }
        if (target.getUniqueID()
            .equals(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.invite_self");
            return;
        }

        TeamActions.onInvite(team, player, target);
    }

    private void executeAccept(ICommandSender sender, String teamName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;
        UUID playerId = player.getUniqueID();

        Set<Team> invites = TeamManager.getPendingInvites(playerId);
        Team invitingTeam = TeamCommandsUtils.resolvePendingTeamTarget(
            sender,
            invites,
            teamName,
            "talkwith.chat.teams.error.no_invite",
            "talkwith.chat.teams.error.disambiguate_invite",
            "talkwith.chat.teams.error.no_invite_specific");
        if (invitingTeam == null) return;

        Team currentTeam = TeamManager.getTeamByPlayer(playerId);
        if (currentTeam == invitingTeam) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.already_in_team");
            return;
        }
        if (currentTeam != null && currentTeam.playerCannotAcceptInvites(playerId)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.last_owner_leave");
            return;
        }

        TeamActions.onAccept(invitingTeam, player);
    }

    private void executeDeny(ICommandSender sender, String teamName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Set<Team> invites = TeamManager.getPendingInvites(player.getUniqueID());
        Team specificTeam = TeamCommandsUtils.resolvePendingTeamTarget(
            sender,
            invites,
            teamName,
            "talkwith.chat.teams.error.no_invite",
            "talkwith.chat.teams.error.disambiguate_invite",
            "talkwith.chat.teams.error.no_invite_specific");
        if (specificTeam == null) return;

        TeamActions.onDeny(specificTeam, player);
    }

    private void executeLeave(ICommandSender sender) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;
        UUID playerId = player.getUniqueID();

        Team team = TeamManager.getTeamByPlayer(playerId);
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }

        if (team.getMembers()
            .size() == 1) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.last_member_leave");
            return;
        }
        if (team.isOwner(playerId) && team.getOwners()
            .size() == 1) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.last_owner_leave");
            return;
        }

        TeamActions.onLeave(player);
    }

    private void executePromote(ICommandSender sender, String targetName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team team = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!team.isOwner(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_owner_promote");
            return;
        }

        UUID targetUuid = TeamCommandsUtils.resolveTeamMemberUuid(team, targetName);
        if (targetUuid == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.other_not_in_team", targetName);
            return;
        }
        if (team.isOwner(targetUuid)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.promote_owner", targetName);
            return;
        }

        TeamActions.onPromote(team, targetUuid, false, null);
    }

    private void executeDemote(ICommandSender sender, String targetName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team team = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!team.isOwner(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_owner_demote");
            return;
        }

        UUID targetUuid = TeamCommandsUtils.resolveTeamMemberUuid(team, targetName);
        if (targetUuid == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.other_not_in_team", targetName);
            return;
        }
        if (targetUuid.equals(player.getUniqueID()) && team.getOwners()
            .size() == 1) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.last_owner_demote");
            return;
        }
        if (!team.isOfficer(targetUuid)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.demote_member", targetName);
            return;
        }

        TeamActions.onDemote(team, targetUuid, false, null);
    }

    private void executeKick(ICommandSender sender, String targetName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;
        Team team = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }

        UUID targetUuid = TeamCommandsUtils.resolveTeamMemberUuid(team, targetName);
        if (targetUuid == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.other_not_in_team", targetName);
            return;
        }
        if (targetUuid.equals(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.cannot_kick_self");
            return;
        }
        if (!TeamCommandsUtils.canKick(team.getRole(player.getUniqueID()), team.getRole(targetUuid))) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.kick_not_allowed");
            return;
        }
        TeamActions.onKick(team, targetUuid, false, null);
    }

    private void executeInfo(ICommandSender sender) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team team = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }

        TeamCommandsUtils.printTeamInfo(sender, team);
    }

    private void executeMergeRequest(ICommandSender sender, String targetTeamName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team source = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (source == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!source.isOwner(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_owner_merge_request");
            return;
        }

        Team target = TeamManager.getTeamByName(targetTeamName);
        if (target == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.team_not_found", targetTeamName);
            return;
        }
        if (target == source) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.merge_self");
            return;
        }

        if (TeamManager.hasPendingMergeRequest(source, target)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.merge_already_requested", targetTeamName);
            return;
        }

        TeamActions.onMergeRequest(player, source, target);
    }

    private void executeMergeCancel(ICommandSender sender, String targetTeamName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team source = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (source == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!source.isOwner(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_owner_merge_request");
            return;
        }
        Team target = TeamManager.getTeamByName(targetTeamName);
        if (target == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.team_not_found", targetTeamName);
            return;
        }
        if (!TeamManager.hasPendingMergeRequest(source, target)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.no_such_merge_request", targetTeamName);
            return;
        }

        TeamActions.onMergeCancel(player, source, target);
    }

    private void executeMergeAccept(ICommandSender sender, String sourceTeamName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team target = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (target == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!target.isOwner(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_owner_merge_response");
            return;
        }

        Set<Team> pendingMerges = TeamManager.getPendingMergeRequests(target);
        Team source = TeamCommandsUtils.resolvePendingTeamTarget(
            sender,
            pendingMerges,
            sourceTeamName,
            "talkwith.chat.teams.error.no_merge_request",
            "talkwith.chat.teams.error.disambiguate_merge",
            "talkwith.chat.teams.error.no_merge_request_specific");
        if (source == null) return;

        TeamActions.onMergeAccept(source, target, false, null);
    }

    private void executeMergeDeny(ICommandSender sender, String sourceTeamName) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;

        Team target = TeamManager.getTeamByPlayer(player.getUniqueID());
        if (target == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!target.isOwner(player.getUniqueID())) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_owner_merge_response");
            return;
        }

        Set<Team> pendingMerges = TeamManager.getPendingMergeRequests(target);
        Team source = TeamCommandsUtils.resolvePendingTeamTarget(
            sender,
            pendingMerges,
            sourceTeamName,
            "talkwith.chat.teams.error.no_merge_request",
            "talkwith.chat.teams.error.disambiguate_merge",
            "talkwith.chat.teams.error.no_merge_request_specific");
        if (source == null) return;

        TeamActions.onMergeDeny(player, source, target);
    }

    private void executeDisband(ICommandSender sender) {
        EntityPlayer player = TeamCommandsUtils.asPlayer(sender);
        if (player == null) return;
        UUID playerId = player.getUniqueID();

        Team team = TeamManager.getTeamByPlayer(playerId);
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_in_team");
            return;
        }
        if (!team.isOwner(playerId)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.not_owner_disband");
            return;
        }
        if (!team.canBeDisbanded()) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.last_owner_disband");
            return;
        }

        TeamActions.onDisband(team, false, null);
    }

    // -------------------------------------------------------------------------
    // Admin command
    // -------------------------------------------------------------------------

    private void processAdminCommand(ICommandSender sender, String[] args) {
        switch (args[0].toLowerCase()) {
            case "rename" -> {
                if (args.length < 3) {
                    CommandUtils.error(sender, "talkwith.chat.teams.admin.error.rename_usage");
                    return;
                }
                executeAdminRename(sender, args[1], args[2]);
            }
            case "promote" -> {
                if (args.length < 3) {
                    CommandUtils.error(sender, "talkwith.chat.teams.admin.error.promote_usage");
                    return;
                }
                executeAdminPromote(sender, args[1], args[2]);
            }
            case "demote" -> {
                if (args.length < 3) {
                    CommandUtils.error(sender, "talkwith.chat.teams.admin.error.demote_usage");
                    return;
                }
                executeAdminDemote(sender, args[1], args[2]);
            }
            case "kick" -> {
                if (args.length < 3) {
                    CommandUtils.error(sender, "talkwith.chat.teams.admin.error.kick_usage");
                    return;
                }
                executeAdminKick(sender, args[1], args[2]);
            }
            case "merge" -> {
                if (args.length < 3) {
                    CommandUtils.error(sender, "talkwith.chat.teams.admin.error.merge_usage");
                    return;
                }
                executeAdminMerge(sender, args[1], args[2]);
            }
            case "disband" -> {
                if (args.length < 2) {
                    CommandUtils.error(sender, "talkwith.chat.teams.admin.error.disband_usage");
                    return;
                }
                executeAdminDisband(sender, args[1]);
            }
            case "info" -> {
                if (args.length < 2) {
                    CommandUtils.error(sender, "talkwith.chat.teams.admin.error.info_usage");
                    return;
                }
                executeAdminInfo(sender, args[1]);
            }
            case "help" -> executeHelp(sender);
            default -> sendUsage(sender);
        }
    }

    private void executeAdminRename(ICommandSender sender, String oldName, String newName) {
        if (newName.length() > Team.MAX_TEAM_NAME_LENGTH) {
            CommandUtils.error(sender, "talkwith.chat.teams.message.team_name_too_long");
            return;
        }
        Team team = TeamManager.getTeamByName(oldName);
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.team_not_found", oldName);
            return;
        }

        if (!TeamActions.onRename(team, oldName, newName, true, sender)) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.name_in_use", newName);
        }
    }

    private void executeAdminPromote(ICommandSender sender, String teamName, String playerName) {
        Team team = TeamManager.getTeamByName(teamName);
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.team_not_found", teamName);
            return;
        }

        UUID uuid = TeamCommandsUtils.resolveTeamMemberUuid(team, playerName);
        if (uuid == null || !team.isMember(uuid)) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.player_not_in_team", playerName, teamName);
            return;
        }
        if (team.isOwner(uuid)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.promote_owner", playerName);
            return;
        }

        TeamActions.onPromote(team, uuid, true, sender);
    }

    private void executeAdminDemote(ICommandSender sender, String teamName, String playerName) {
        Team team = TeamManager.getTeamByName(teamName);
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.team_not_found", teamName);
            return;
        }

        UUID uuid = TeamCommandsUtils.resolveTeamMemberUuid(team, playerName);
        if (uuid == null || !team.isMember(uuid)) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.player_not_in_team", playerName, teamName);
            return;
        }
        if (!team.isOfficer(uuid)) {
            CommandUtils.error(sender, "talkwith.chat.teams.error.demote_member", playerName);
            return;
        }
        if (team.isOwner(uuid) && team.getOwners()
            .size() == 1) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.demote_last_owner", playerName, teamName);
            return;
        }

        TeamActions.onDemote(team, uuid, true, sender);
    }

    private void executeAdminKick(ICommandSender sender, String teamName, String playerName) {
        Team team = TeamManager.getTeamByName(teamName);
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.team_not_found", teamName);
            return;
        }

        UUID uuid = TeamCommandsUtils.resolveTeamMemberUuid(team, playerName);
        if (uuid == null || !team.isMember(uuid)) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.player_not_in_team", playerName, teamName);
            return;
        }
        if (team.isOwner(uuid) && team.getOwners()
            .size() == 1) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.kick_last_owner", playerName, teamName);
            return;
        }

        TeamActions.onKick(team, uuid, true, sender);
    }

    private void executeAdminMerge(ICommandSender sender, String sourceName, String targetName) {
        Team sourceTeam = TeamManager.getTeamByName(sourceName);
        if (sourceTeam == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.team_not_found", sourceName);
            return;
        }
        Team targetTeam = TeamManager.getTeamByName(targetName);
        if (targetTeam == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.team_not_found", targetName);
            return;
        }

        if (sourceTeam.getTeamId()
            .equals(targetTeam.getTeamId())) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.message.merge_teams_same");
            return;
        }

        TeamActions.onMergeAccept(sourceTeam, targetTeam, true, sender);
    }

    private void executeAdminDisband(ICommandSender sender, String teamName) {
        Team team = TeamManager.getTeamByName(teamName);
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.team_not_found", teamName);
            return;
        }
        if (!team.canBeDisbanded()) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.cannot_disband_solo_team");
            return;
        }

        TeamActions.onDisband(team, true, sender);
    }

    private void executeAdminInfo(ICommandSender sender, String teamName) {
        Team team = TeamManager.getTeamByName(teamName);
        if (team == null) {
            CommandUtils.error(sender, "talkwith.chat.teams.admin.error.team_not_found", teamName);
            return;
        }

        TeamCommandsUtils.printTeamInfo(sender, team);
    }

    // -------------------------------------------------------------------------
    // Help / usage / tab completion
    // -------------------------------------------------------------------------

    private void executeHelp(ICommandSender sender) {
        String root = admin ? TeamCommandsUtils.getCommandAdminRoot() : TeamCommandsUtils.getCommandRoot();
        if (admin) {
            sender.addChatMessage(
                new ChatComponentTranslation("talkwith.chat.teams.admin.help.1", Config.teamSystemName));
            for (int i = 2; i <= 8; i++) {
                sender.addChatMessage(new ChatComponentTranslation("talkwith.chat.teams.admin.help." + i, root));
            }
        } else {
            sender.addChatMessage(new ChatComponentTranslation("talkwith.chat.teams.help.1", Config.teamSystemName));
            for (int i = 2; i <= 14; i++) {
                sender.addChatMessage(new ChatComponentTranslation("talkwith.chat.teams.help." + i, root));
            }
        }
    }

    private void sendUsage(ICommandSender sender) {
        ChatComponentTranslation msg = new ChatComponentTranslation(getCommandUsage(sender), Config.teamSystemName);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.YELLOW);
        sender.addChatMessage(msg);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            if (admin) {
                return getListOfStringsMatchingLastWord(
                    args,
                    "rename",
                    "promote",
                    "demote",
                    "kick",
                    "merge",
                    "disband",
                    "info",
                    "help");
            }
            return getListOfStringsMatchingLastWord(
                args,
                "rename",
                "invite",
                "accept",
                "deny",
                "leave",
                "promote",
                "demote",
                "kick",
                "info",
                "merge",
                "disband",
                "help");
        }
        if (args.length == 2 && !admin) {
            if (args[0].equalsIgnoreCase("merge")) {
                return getListOfStringsMatchingLastWord(args, "request", "cancel", "accept", "deny");
            }
        }
        return null;
    }
}
