package com.czqwq.talkwith.teams;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.apache.commons.lang3.tuple.Pair;

import com.czqwq.talkwith.TalkWith;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent;

public class TeamManagerClient {

    private static volatile Team TEAM;
    private static volatile TeamRole currentRole;

    /**
     * Data payloads received before the corresponding info packet. Applied once the
     * info packet creates the client team.
     */
    private static final List<Pair<String, NBTTagCompound>> PENDING_DATA = new ArrayList<>();

    @SubscribeEvent
    public void onDisconnect(ClientDisconnectionFromServerEvent event) {
        TEAM = null;
        currentRole = null;
        synchronized (PENDING_DATA) {
            PENDING_DATA.clear();
        }
    }

    public static void onTeamInfoSyncPacket(UUID uuid, String name, TeamRole role) {
        if (TEAM != null && TEAM.getTeamId()
            .equals(uuid)) {
            TEAM.renameTeam(name);
        } else {
            TEAM = new Team(name, uuid, true);
            TEAM.initializeData(
                TeamDataRegistry.getRegisteredKeys()
                    .toArray(new String[0]));
        }
        currentRole = role;
        synchronized (PENDING_DATA) {
            for (Pair<String, NBTTagCompound> pair : PENDING_DATA) {
                applyData(pair, true);
            }
            PENDING_DATA.clear();
        }
    }

    public static void onTeamDataSyncPacket(boolean complete, List<Pair<String, NBTTagCompound>> data) {
        if (data == null || data.isEmpty()) return;
        if (TEAM == null) {
            synchronized (PENDING_DATA) {
                PENDING_DATA.addAll(data);
            }
            return;
        }
        for (Pair<String, NBTTagCompound> pair : data) {
            applyData(pair, complete);
        }
    }

    private static void applyData(Pair<String, NBTTagCompound> pair, boolean complete) {
        ITeamData d = TEAM.getData(pair.getLeft());
        if (d instanceof INetworkTeamData) {
            ((INetworkTeamData) d).fromPacketTag(pair.getRight(), complete);
        } else {
            TalkWith.LOG.error("Invalid team data on client: {}", pair.getLeft());
        }
    }

    public static Team getTeam() {
        return TEAM;
    }

    public static TeamRole getRole() {
        return currentRole;
    }
}
