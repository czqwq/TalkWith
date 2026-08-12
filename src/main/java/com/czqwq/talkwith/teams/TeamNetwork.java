package com.czqwq.talkwith.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import org.apache.commons.lang3.tuple.Pair;

import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.teams.PacketTeamDataSync;
import com.czqwq.talkwith.network.teams.PacketTeamInfoSync;
import com.czqwq.talkwith.util.ServerPlayerUtils;

public class TeamNetwork {

    public static void sendPlayerAllTeamData(EntityPlayerMP player, Team team) {
        PacketHandler.INSTANCE.sendTo(createTeamInfoSyncPacket(player.getUniqueID()), player);
        PacketHandler.INSTANCE.sendTo(createCompleteTeamDataSyncPacket(team), player);
    }

    protected static PacketTeamInfoSync createTeamInfoSyncPacket(UUID player) {
        Team playerTeam = TeamManager.getTeamByPlayer(player);
        if (playerTeam == null) {
            // Every player should have a team after login; create a solo team as a fallback.
            playerTeam = TeamManager.getOrCreateTeam(ServerPlayerUtils.getPlayerName(player), player);
        }
        return new PacketTeamInfoSync(playerTeam.getTeamId(), playerTeam.getTeamName(), playerTeam.getRole(player));
    }

    protected static PacketTeamDataSync createCompleteTeamDataSyncPacket(Team team) {
        List<Pair<String, NBTTagCompound>> list = new ArrayList<>();
        for (Entry<String, ITeamData> entry : team.getAllDataEntries()) {
            if (entry.getValue() instanceof INetworkTeamData) {
                INetworkTeamData networkTeamData = (INetworkTeamData) entry.getValue();
                NBTTagCompound tag = new NBTTagCompound();
                networkTeamData.toPacketTag(tag, true);
                list.add(Pair.of(entry.getKey(), tag));
            }
        }
        return new PacketTeamDataSync(list);
    }

    /**
     * Sends the full {@link INetworkTeamData} payload of {@code team} to all of its online members.
     */
    public static void syncTeamData(Team team) {
        PacketTeamDataSync packet = createCompleteTeamDataSyncPacket(team);
        TeamManager.forEachOnlineTeamMember(team, player -> PacketHandler.INSTANCE.sendTo(packet, player));
    }
}
