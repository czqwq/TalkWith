package com.czqwq.talkwith.teams;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Team data holding the shared AI session id the team is currently associated with.
 * The id is empty when the team has no session.
 */
public class TeamSessionData implements INetworkTeamData {

    public static final String KEY = "session";

    private String sessionId = "";

    public String getSessionId() {
        return sessionId;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setString("sessionId", sessionId);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        sessionId = tag.getString("sessionId");
    }

    @Override
    public void mergeData(Team consumed, Team surviving, ITeamData oldTeamData) {
        if (oldTeamData instanceof TeamSessionData && sessionId.isEmpty()) {
            sessionId = ((TeamSessionData) oldTeamData).sessionId;
        }
    }

    @Override
    public void markSyncedToClient() {}

    // --- Static helpers ---

    public static String getSessionId(Team team) {
        ITeamData data = team.getData(KEY);
        return data instanceof TeamSessionData ? ((TeamSessionData) data).sessionId : "";
    }

    /** Sets the team's session id and syncs it to all online team members. */
    public static void setSessionId(Team team, String sessionId) {
        ITeamData data = team.getData(KEY);
        if (!(data instanceof TeamSessionData)) return;
        TeamSessionData sessionData = (TeamSessionData) data;
        if (sessionId.equals(sessionData.sessionId)) return;
        sessionData.sessionId = sessionId;
        team.markDirty();
        TeamNetwork.syncTeamData(team);
    }

    /** Clears the team's session id if it matches the given id and syncs the change. */
    public static void clearSessionId(Team team, String sessionId) {
        ITeamData data = team.getData(KEY);
        if (!(data instanceof TeamSessionData)) return;
        TeamSessionData sessionData = (TeamSessionData) data;
        if (!sessionData.sessionId.equals(sessionId)) return;
        sessionData.sessionId = "";
        team.markDirty();
        TeamNetwork.syncTeamData(team);
    }
}
