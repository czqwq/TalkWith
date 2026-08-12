package com.czqwq.talkwith.teams;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Team {

    private String teamName;
    private final UUID teamId;
    private final boolean clientSide;
    private final Set<UUID> owners = new HashSet<>();
    private final Set<UUID> officers = new HashSet<>();
    private final Set<UUID> members = new HashSet<>();
    private final Map<String, ITeamData> teamData = new HashMap<>();

    public static final int MAX_TEAM_NAME_LENGTH = 32;

    private TeamSaveStatus status = TeamSaveStatus.CLEAN;

    Team(String teamName, UUID teamId) {
        this(teamName, teamId, false);
    }

    public Team(String teamName, UUID teamId, boolean clientSide) {
        this.teamName = teamName;
        this.teamId = teamId;
        this.clientSide = clientSide;
    }

    // --- Getters ---

    public String getTeamName() {
        return teamName;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public boolean isClientSide() {
        return clientSide;
    }

    public TeamSaveStatus getStatus() {
        return status;
    }

    // --- Name ---

    public boolean renameTeam(String newName) {
        if (clientSide) {
            teamName = newName;
            return true;
        }

        if (TeamManager.isTeamNameValid(newName, this)) {
            this.teamName = newName;
            markDirty();
            return true;
        }
        return false;
    }

    // --- Role checks ---

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    public boolean isOfficer(UUID player) {
        return officers.contains(player);
    }

    public boolean isOwner(UUID player) {
        return owners.contains(player);
    }

    public TeamRole getRole(UUID player) {
        if (owners.contains(player)) {
            return TeamRole.OWNER;
        }
        if (officers.contains(player)) {
            return TeamRole.OFFICER;
        }
        return TeamRole.MEMBER;
    }

    // --- Add members ---

    public void addMember(UUID uuid) {
        if (members.add(uuid)) markDirty();
    }

    public void addOfficer(UUID uuid) {
        if (!officers.add(uuid)) return;
        // officers are also always members
        members.add(uuid);
        markDirty();
    }

    public void addOwner(UUID uuid) {
        if (!owners.add(uuid)) return;
        // owners are also always members and officers
        officers.add(uuid);
        members.add(uuid);
        markDirty();
    }

    // --- Remove members ---

    public void removeMember(UUID uuid) {
        members.remove(uuid);
        officers.remove(uuid);
        owners.remove(uuid);
        markDirty();
    }

    public void removeOfficer(UUID uuid) {
        owners.remove(uuid);
        officers.remove(uuid);
        markDirty();
    }

    public void removeOwner(UUID uuid) {
        owners.remove(uuid);
        markDirty();
    }

    // --- Save status ---

    public void markDirty() {
        if (!clientSide && status == TeamSaveStatus.CLEAN) {
            status = TeamSaveStatus.DIRTY;
        }
    }

    protected void markRemoved() {
        if (!clientSide) {
            status = TeamSaveStatus.REMOVED;
            TeamManager.addRemovedTeam(this.getTeamId());
        }
    }

    protected void markClean() {
        if (!clientSide && status == TeamSaveStatus.DIRTY) {
            status = TeamSaveStatus.CLEAN;
        }
    }

    /** Marks a freshly loaded (server-side) team as clean so it is not needlessly re-saved. */
    void markCleanOnLoad() {
        if (!clientSide) {
            status = TeamSaveStatus.CLEAN;
        }
    }

    // --- Get member sets ---

    public Set<UUID> getMembers() {
        return Collections.unmodifiableSet(members);
    }

    public Set<UUID> getOfficers() {
        return Collections.unmodifiableSet(officers);
    }

    public Set<UUID> getOwners() {
        return Collections.unmodifiableSet(owners);
    }

    // --- Team data ---

    void initializeData(String... keys) {
        for (String key : keys) {
            if (!teamData.containsKey(key)) {
                ITeamData data = TeamDataRegistry.construct(key);
                if (data != null) {
                    // Only add INetworkTeamData for the client-side team
                    if (data instanceof INetworkTeamData || !clientSide) {
                        teamData.put(key, data);
                    }
                }
            }
        }
    }

    void putData(String key, ITeamData data) {
        teamData.put(key, data);
    }

    public ITeamData getData(String key) {
        return teamData.get(key);
    }

    public Set<Map.Entry<String, ITeamData>> getAllDataEntries() {
        return teamData.entrySet();
    }

    // --- Disband check ---

    public boolean canBeDisbanded() {
        return members.size() > 1;
    }

    public boolean playerCannotAcceptInvites(UUID player) {
        return owners.contains(player) && owners.size() == 1 && members.size() > 1;
    }
}
