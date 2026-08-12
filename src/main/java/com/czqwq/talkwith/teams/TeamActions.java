package com.czqwq.talkwith.teams;

import static com.czqwq.talkwith.util.CommandUtils.colorChatComponent;
import static com.czqwq.talkwith.util.CommandUtils.success;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

import com.czqwq.talkwith.TalkWith;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.teams.PacketTeamDataSync;
import com.czqwq.talkwith.util.ServerPlayerUtils;

public class TeamActions {

    /**
     * Renames the team and notifies all members.
     *
     * @return {@code false} when the new name is invalid (e.g. already in use).
     */
    public static boolean onRename(Team team, String oldName, String newName, boolean adminAction,
        @Nullable ICommandSender admin) {
        if (!team.renameTeam(newName)) return false;
        TeamManager.forEachOnlineTeamMember(team, member -> {
            PacketHandler.INSTANCE.sendTo(TeamNetwork.createTeamInfoSyncPacket(member.getUniqueID()), member);
            success(
                member,
                adminAction ? "talkwith.chat.teams.message.admin_renamed_team"
                    : "talkwith.chat.teams.message.renamed_team",
                colorChatComponent(EnumChatFormatting.GOLD, newName));
        });
        if (adminAction && admin != null) {
            success(
                admin,
                "talkwith.chat.teams.admin.message.renamed",
                colorChatComponent(EnumChatFormatting.GOLD, oldName),
                colorChatComponent(EnumChatFormatting.GOLD, newName));
        }
        return true;
    }

    public static void onInvite(Team team, EntityPlayer source, EntityPlayer target) {
        TeamManager.addPendingInvite(target.getUniqueID(), team);

        ChatComponentTranslation notification = new ChatComponentTranslation(
            "talkwith.chat.teams.message.received_invite",
            colorChatComponent(EnumChatFormatting.GOLD, source.getCommandSenderName()),
            colorChatComponent(EnumChatFormatting.GOLD, team.getTeamName()),
            colorChatComponent(
                EnumChatFormatting.YELLOW,
                TeamCommandsUtils.getCommandRoot() + " accept \"" + team.getTeamName() + "\""),
            colorChatComponent(
                EnumChatFormatting.YELLOW,
                TeamCommandsUtils.getCommandRoot() + " deny \"" + team.getTeamName() + "\""));
        notification.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        target.addChatMessage(notification);

        success(
            source,
            "talkwith.chat.teams.message.sent_invite",
            colorChatComponent(EnumChatFormatting.GOLD, ServerPlayerUtils.getPlayerName(target)));
    }

    public static void onAccept(Team invitingTeam, EntityPlayer player) {
        // Leave current team first. If the team would be disbanded, merge it into the new team automatically.
        UUID playerId = player.getUniqueID();
        Team oldTeam = TeamManager.getTeamByPlayer(playerId);
        if (oldTeam == null) {
            // Player has no team (edge case) — create a solo team so the merge can proceed.
            oldTeam = TeamManager.getOrCreateTeam(player.getCommandSenderName(), playerId);
        }
        if (oldTeam == invitingTeam) {
            TalkWith.LOG
                .debug("onAccept: player {} is already a member of team {}", playerId, invitingTeam.getTeamName());
            return;
        }
        if (oldTeam.getMembers()
            .size() == 1) {
            TeamManager.mergeTeams(invitingTeam, oldTeam);
        } else {
            oldTeam.removeMember(playerId);
            invitingTeam.addMember(playerId);
            TeamManager.transferTeamData(oldTeam, invitingTeam, playerId, TeamDataTransferReason.JoinedExistingTeam);
            oldTeam.markDirty();
            PacketTeamDataSync oldTeamData = TeamNetwork.createCompleteTeamDataSyncPacket(oldTeam);
            TeamManager.forEachOnlineTeamMember(oldTeam, member -> {
                if (member.getUniqueID()
                    .equals(playerId)) return;
                PacketHandler.INSTANCE.sendTo(oldTeamData, member);
                success(
                    member,
                    "talkwith.chat.teams.message.other_left_team",
                    colorChatComponent(EnumChatFormatting.GOLD, ServerPlayerUtils.getPlayerName(player)));
            });
        }
        TeamManager.removeAllPendingInvites(playerId);
        TeamManager.cachePlayerTeam(playerId, invitingTeam);
        invitingTeam.markDirty();

        PacketTeamDataSync newTeamData = TeamNetwork.createCompleteTeamDataSyncPacket(invitingTeam);
        TeamManager.forEachOnlineTeamMember(invitingTeam, member -> {
            if (member.getUniqueID()
                .equals(playerId)) {
                // Send the info packet BEFORE the data packet so the client can apply the payload.
                PacketHandler.INSTANCE
                    .sendTo(TeamNetwork.createTeamInfoSyncPacket(player.getUniqueID()), (EntityPlayerMP) player);
            }
            PacketHandler.INSTANCE.sendTo(newTeamData, member);
            if (member.getUniqueID()
                .equals(playerId)) {
                success(
                    member,
                    "talkwith.chat.teams.message.joined_team",
                    colorChatComponent(EnumChatFormatting.GOLD, invitingTeam.getTeamName()));
            } else {
                success(
                    member,
                    "talkwith.chat.teams.message.other_joined_team",
                    colorChatComponent(EnumChatFormatting.GOLD, ServerPlayerUtils.getPlayerName(player)));
            }
        });
    }

    public static void onDeny(Team team, EntityPlayer player) {
        TeamManager.removePendingInvite(player.getUniqueID(), team);
        success(
            player,
            "talkwith.chat.teams.message.declined_invite",
            colorChatComponent(EnumChatFormatting.GOLD, team.getTeamName()));
    }

    public static void onKick(Team team, UUID kicked, boolean adminAction, @Nullable ICommandSender admin) {
        team.removeMember(kicked);

        Team newTeam = TeamManager.createTeam(ServerPlayerUtils.getPlayerName(kicked), kicked);
        TeamManager.transferTeamData(team, newTeam, kicked, TeamDataTransferReason.JoinedNewTeam);
        team.markDirty();
        newTeam.markDirty();
        PacketTeamDataSync teamData = TeamNetwork.createCompleteTeamDataSyncPacket(team);
        TeamManager.forEachOnlineTeamMember(team, member -> {
            PacketHandler.INSTANCE.sendTo(teamData, member);
            success(
                member,
                adminAction ? "talkwith.chat.teams.message.admin_other_kicked_from_team"
                    : "talkwith.chat.teams.message.other_kicked_from_team",
                colorChatComponent(EnumChatFormatting.GOLD, ServerPlayerUtils.getPlayerName(kicked)));
        });

        TeamManager.forEachOnlineTeamMember(newTeam, member -> {
            if (member.getUniqueID()
                .equals(kicked)) {
                TeamNetwork.sendPlayerAllTeamData(member, newTeam);
                success(
                    member,
                    adminAction ? "talkwith.chat.teams.message.admin_kicked_from_team"
                        : "talkwith.chat.teams.message.kicked_from_team",
                    colorChatComponent(EnumChatFormatting.GOLD, team.getTeamName()));
            }
        });

        if (adminAction && admin != null) {
            success(
                admin,
                "talkwith.chat.admin.message.kicked",
                colorChatComponent(EnumChatFormatting.GOLD, ServerPlayerUtils.getPlayerName(kicked)),
                colorChatComponent(EnumChatFormatting.GOLD, team.getTeamName()));
        }
    }

    public static void onLeave(EntityPlayer player) {
        UUID playerId = player.getUniqueID();
        Team oldTeam = TeamManager.getTeamByPlayer(playerId);
        if (oldTeam == null) {
            TalkWith.LOG.debug("onLeave: player {} has no team", playerId);
            return;
        }
        String teamName = oldTeam.getTeamName();
        oldTeam.removeMember(playerId);

        if (oldTeam.getMembers()
            .isEmpty()) {
            TeamManager.disbandTeam(oldTeam);
        } else {
            PacketTeamDataSync oldTeamData = TeamNetwork.createCompleteTeamDataSyncPacket(oldTeam);
            TeamManager.forEachOnlineTeamMember(oldTeam, member -> {
                PacketHandler.INSTANCE.sendTo(oldTeamData, member);
                success(
                    member,
                    "talkwith.chat.teams.message.other_left_team",
                    colorChatComponent(EnumChatFormatting.GOLD, ServerPlayerUtils.getPlayerName(player)));
            });
        }

        // Create a new solo team for the player
        Team newTeam = TeamManager.createTeam(player.getCommandSenderName(), player.getUniqueID());
        TeamManager.transferTeamData(oldTeam, newTeam, playerId, TeamDataTransferReason.JoinedNewTeam);
        if (!oldTeam.getMembers()
            .isEmpty()) oldTeam.markDirty();
        newTeam.markDirty();
        TeamNetwork.sendPlayerAllTeamData((EntityPlayerMP) player, newTeam);

        success(player, "talkwith.chat.teams.message.left_team", colorChatComponent(EnumChatFormatting.GOLD, teamName));
    }

    public static void onPromote(Team team, UUID target, boolean adminAction, @Nullable ICommandSender admin) {
        ChatComponentText playerComp = colorChatComponent(
            EnumChatFormatting.GOLD,
            ServerPlayerUtils.getPlayerName(target));
        if (team.isOfficer(target)) {
            team.addOwner(target);
            TeamManager.forEachOnlineTeamMember(team, member -> {
                success(member, "talkwith.chat.teams.message.promoted_to_owner", playerComp);
                if (member.getUniqueID()
                    .equals(target)) {
                    PacketHandler.INSTANCE.sendTo(TeamNetwork.createTeamInfoSyncPacket(target), member);
                }
            });
            if (adminAction) {
                success(
                    admin,
                    "talkwith.chat.teams.admin.message.promoted_to_owner",
                    playerComp,
                    colorChatComponent(EnumChatFormatting.GOLD, team.getTeamName()));
            }
        } else {
            team.addOfficer(target);
            TeamManager.forEachOnlineTeamMember(team, member -> {
                success(member, "talkwith.chat.teams.message.promoted_to_officer", playerComp);
                if (member.getUniqueID()
                    .equals(target)) {
                    PacketHandler.INSTANCE.sendTo(TeamNetwork.createTeamInfoSyncPacket(target), member);
                }
            });
            if (adminAction) {
                success(
                    admin,
                    "talkwith.chat.teams.admin.message.promoted_to_officer",
                    playerComp,
                    colorChatComponent(EnumChatFormatting.GOLD, team.getTeamName()));
            }
        }
    }

    public static void onDemote(Team team, UUID target, boolean adminAction, @Nullable ICommandSender admin) {
        ChatComponentText playerComp = colorChatComponent(
            EnumChatFormatting.GOLD,
            ServerPlayerUtils.getPlayerName(target));
        if (team.isOwner(target)) {
            team.removeOwner(target);
            TeamManager.forEachOnlineTeamMember(team, member -> {
                success(member, "talkwith.chat.teams.message.demoted_to_officer", playerComp);
                if (member.getUniqueID()
                    .equals(target)) {
                    PacketHandler.INSTANCE.sendTo(TeamNetwork.createTeamInfoSyncPacket(target), member);
                }
            });
            if (adminAction) {
                success(
                    admin,
                    "talkwith.chat.teams.admin.message.demoted_to_officer",
                    playerComp,
                    colorChatComponent(EnumChatFormatting.GOLD, team.getTeamName()));
            }
        } else {
            team.removeOfficer(target);
            TeamManager.forEachOnlineTeamMember(team, member -> {
                success(member, "talkwith.chat.teams.message.demoted_to_member", playerComp);
                if (member.getUniqueID()
                    .equals(target)) {
                    PacketHandler.INSTANCE.sendTo(TeamNetwork.createTeamInfoSyncPacket(target), member);
                }
            });
            if (adminAction) {
                success(
                    admin,
                    "talkwith.chat.teams.admin.message.demoted_to_member",
                    playerComp,
                    colorChatComponent(EnumChatFormatting.GOLD, team.getTeamName()));
            }
        }
    }

    public static void onMergeRequest(EntityPlayer player, Team source, Team target) {

        TeamManager.addPendingMergeRequest(source, target);

        ChatComponentText sourceComponent = colorChatComponent(EnumChatFormatting.GOLD, source.getTeamName());
        ChatComponentText targetComponent = colorChatComponent(EnumChatFormatting.GOLD, target.getTeamName());
        success(player, "talkwith.chat.teams.message.merge_request_sent", targetComponent);

        // Notify all online owners of the target team
        ChatComponentTranslation notification = new ChatComponentTranslation(
            "talkwith.chat.teams.message.merge_request_received",
            sourceComponent,
            colorChatComponent(
                EnumChatFormatting.YELLOW,
                TeamCommandsUtils.getCommandRoot() + " merge accept \"" + source.getTeamName() + "\""),
            colorChatComponent(
                EnumChatFormatting.YELLOW,
                TeamCommandsUtils.getCommandRoot() + " merge deny \"" + source.getTeamName() + "\""));
        notification.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        Set<UUID> owners = target.getOwners();
        TeamManager.forEachOnlineTeamMember(target, member -> {
            if (owners.contains(member.getUniqueID())) {
                member.addChatMessage(notification);
            }
        });
    }

    public static void onMergeCancel(EntityPlayer player, Team source, Team target) {
        TeamManager.removePendingMergeRequest(source, target);

        ChatComponentText targetComponent = colorChatComponent(EnumChatFormatting.GOLD, target.getTeamName());
        success(player, "talkwith.chat.teams.message.merge_request_cancelled", targetComponent);
    }

    public static void onMergeAccept(Team source, Team target, boolean adminAction, @Nullable ICommandSender admin) {
        ChatComponentText sourceComponent = colorChatComponent(EnumChatFormatting.GOLD, source.getTeamName());
        ChatComponentText targetComponent = colorChatComponent(EnumChatFormatting.GOLD, target.getTeamName());

        if (!adminAction) {
            TeamManager.removePendingMergeRequest(source, target);
        }
        TeamManager.mergeTeams(target, source);

        ChatComponentTranslation notification = new ChatComponentTranslation(
            "talkwith.chat.teams.message.merge_complete",
            sourceComponent,
            targetComponent);
        notification.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);

        PacketTeamDataSync dataPacket = TeamNetwork.createCompleteTeamDataSyncPacket(target);
        TeamManager.forEachOnlineTeamMember(target, member -> {
            PacketHandler.INSTANCE.sendTo(TeamNetwork.createTeamInfoSyncPacket(member.getUniqueID()), member);
            PacketHandler.INSTANCE.sendTo(dataPacket, member);
            member.addChatMessage(notification);
        });

        if (adminAction) {
            success(admin, "talkwith.chat.teams.admin.message.merged", sourceComponent, targetComponent);
        }
    }

    public static void onMergeDeny(EntityPlayer player, Team source, Team target) {
        TeamManager.removePendingMergeRequest(source, target);

        ChatComponentText sourceComponent = colorChatComponent(EnumChatFormatting.GOLD, source.getTeamName());
        success(player, "talkwith.chat.teams.message.merge_denied", sourceComponent);
    }

    public static void onDisband(Team team, boolean adminAction, @Nullable ICommandSender admin) {
        List<UUID> members = new ArrayList<>(team.getMembers());
        String teamName = team.getTeamName();

        TeamManager.disbandTeam(team);

        ChatComponentTranslation notice = new ChatComponentTranslation(
            adminAction ? "talkwith.chat.teams.admin.message.team_disbanded"
                : "talkwith.chat.teams.message.team_disbanded",
            colorChatComponent(EnumChatFormatting.GOLD, teamName));
        notice.getChatStyle()
            .setColor(EnumChatFormatting.RED);

        for (UUID uuid : members) {
            String name = ServerPlayerUtils.getPlayerName(uuid);
            Team newTeam = TeamManager.createTeam(name, uuid);
            TeamManager.transferTeamData(team, newTeam, uuid, TeamDataTransferReason.JoinedNewTeam);
            newTeam.markDirty();
            TeamManager.forEachOnlineTeamMember(newTeam, member -> {
                PacketHandler.INSTANCE.sendTo(TeamNetwork.createTeamInfoSyncPacket(member.getUniqueID()), member);
                TeamNetwork.sendPlayerAllTeamData(member, newTeam);
                member.addChatMessage(notice);
            });
        }
        if (adminAction) {
            success(
                admin,
                "talkwith.chat.teams.admin.message.disbanded",
                colorChatComponent(EnumChatFormatting.GOLD, teamName));
        }
    }
}
