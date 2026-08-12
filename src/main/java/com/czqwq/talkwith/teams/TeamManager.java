package com.czqwq.talkwith.teams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.czqwq.talkwith.TalkWith;
import com.czqwq.talkwith.util.ServerPlayerUtils;

public class TeamManager {

    private static final List<Team> TEAMS = new ArrayList<>();
    private static final Map<UUID, Team> TEAM_MAP = new HashMap<>();
    private static final Map<UUID, Team> PLAYER_TEAM_CACHE = new HashMap<>();
    private static final Map<UUID, Set<Team>> PENDING_INVITES = new HashMap<>();
    // keyed by target team, value is the set of source teams requesting to merge into it
    private static final Map<Team, Set<Team>> PENDING_MERGE_REQUESTS = new HashMap<>();

    private static final Set<UUID> REMOVED_TEAMS = new HashSet<>();

    public static boolean isTeamNameValid(String name) {
        return isTeamNameValid(name, null);
    }

    public static boolean isTeamNameValid(String name, Team self) {
        if (name == null || name.isEmpty()) return false;
        for (Team team : TEAMS) {
            if (team == self) continue;
            if (team.getTeamName()
                .equals(name)) {
                return false;
            }
        }
        return true;
    }

    public static Team getTeamByPlayer(UUID playerUuid) {
        Team foundTeam = PLAYER_TEAM_CACHE.get(playerUuid);
        if (foundTeam != null && foundTeam.isMember(playerUuid) && TEAM_MAP.containsKey(foundTeam.getTeamId())) {
            return foundTeam;
        }
        // Cache miss
        for (Team team : TEAMS) {
            if (team.isMember(playerUuid)) {
                PLAYER_TEAM_CACHE.put(playerUuid, team);
                return team;
            }
        }
        PLAYER_TEAM_CACHE.remove(playerUuid);
        TalkWith.LOG
            .debug("Unable to find team for player {} ({})", playerUuid, ServerPlayerUtils.getPlayerName(playerUuid));
        return null;
    }

    public static Team getTeamByName(String teamName) {
        for (Team team : TEAMS) {
            if (team.getTeamName()
                .equals(teamName)) {
                return team;
            }
        }
        return null;
    }

    /**
     * Returns the player's current team, creating a solo team for them if they are not in one.
     */
    public static Team getOrCreateTeam(String playerName, UUID playerUuid) {
        Team existing = getTeamByPlayer(playerUuid);
        if (existing != null) return existing;

        return createTeam(playerName, playerUuid);
    }

    public static Team createTeam(String playerName, UUID playerUuid) {
        String baseName = playerName + "'s Team";
        String teamName = baseName;
        int suffix = 2;
        while (!isTeamNameValid(teamName)) {
            teamName = baseName + " (" + suffix++ + ")";
        }
        Team team = new Team(teamName, UUID.randomUUID());
        team.initializeData(
            TeamDataRegistry.getRegisteredKeys()
                .toArray(new String[0]));
        team.addOwner(playerUuid);
        PLAYER_TEAM_CACHE.put(playerUuid, team);
        TEAMS.add(team);
        TEAM_MAP.put(team.getTeamId(), team);
        team.markDirty();
        return team;
    }

    /**
     * Merges the consumed team into the surviving team. All members of the consumed team become members of the
     * surviving team. {@link ITeamData#mergeData} is called on all registered data types. The consumed team is
     * disbanded afterward.
     */
    public static void mergeTeams(Team surviving, Team consumed) {
        if (surviving.getTeamId()
            .equals(consumed.getTeamId())) {
            TalkWith.LOG.error("Cannot merge team into itself.");
            return;
        }
        for (UUID uuid : consumed.getMembers()) {
            PLAYER_TEAM_CACHE.put(uuid, surviving);
            surviving.addMember(uuid);
            // Members of the consumed team may still hold a stale invite to the surviving team.
            removePendingInvite(uuid, surviving);
        }

        for (String dataKey : TeamDataRegistry.getRegisteredKeys()) {
            ITeamData survivingData = surviving.getData(dataKey);
            ITeamData consumedData = consumed.getData(dataKey);
            if (survivingData != null && consumedData != null) {
                survivingData.mergeData(consumed, surviving, consumedData);
            }
        }

        TEAMS.remove(consumed);
        TEAM_MAP.remove(consumed.getTeamId());
        PENDING_MERGE_REQUESTS.remove(consumed);
        PENDING_MERGE_REQUESTS.forEach((target, sourceSet) -> sourceSet.remove(consumed));
        surviving.markDirty();
        consumed.markRemoved();

        PENDING_INVITES.forEach((player, teamSet) -> teamSet.remove(consumed));
    }

    /**
     * Removes a team from all registries, cleans up its pending invites/merge requests and
     * marks it as removed so the save system deletes its file.
     */
    public static void disbandTeam(Team team) {
        TEAMS.remove(team);
        TEAM_MAP.remove(team.getTeamId());
        removeAllMergeRequestsForTeam(team);
        removeAllInvitesForTeam(team);
        team.markRemoved();
    }

    public static void transferTeamData(Team prevTeam, Team newTeam, UUID playerId, TeamDataTransferReason reason) {
        for (Entry<String, ITeamData> entry : newTeam.getAllDataEntries()) {
            entry.getValue()
                .transferData(prevTeam, newTeam, playerId, prevTeam.getData(entry.getKey()), reason);
        }
    }

    @SuppressWarnings("unchecked")
    public static void forEachOnlineTeamMember(Team team, Consumer<EntityPlayerMP> consumer) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        Set<UUID> members = team.getMembers();
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            if (obj instanceof EntityPlayerMP) {
                EntityPlayerMP playerEntity = (EntityPlayerMP) obj;
                if (members.contains(playerEntity.getUniqueID())) {
                    consumer.accept(playerEntity);
                }
            }
        }
    }

    public static void addPendingInvite(UUID uuid, Team team) {
        PENDING_INVITES.computeIfAbsent(uuid, k -> new HashSet<>())
            .add(team);
    }

    public static Set<Team> getPendingInvites(UUID uuid) {
        return PENDING_INVITES.getOrDefault(uuid, Collections.emptySet());
    }

    public static void removePendingInvite(UUID uuid, Team team) {
        Set<Team> invites = PENDING_INVITES.get(uuid);
        if (invites == null) return;
        invites.remove(team);
        if (invites.isEmpty()) PENDING_INVITES.remove(uuid);
    }

    public static void removeAllPendingInvites(UUID uuid) {
        PENDING_INVITES.remove(uuid);
    }

    public static void addPendingMergeRequest(Team source, Team target) {
        PENDING_MERGE_REQUESTS.computeIfAbsent(target, k -> new HashSet<>())
            .add(source);
    }

    public static Set<Team> getPendingMergeRequests(Team target) {
        return PENDING_MERGE_REQUESTS.getOrDefault(target, Collections.emptySet());
    }

    public static void removePendingMergeRequest(Team source, Team target) {
        Set<Team> mergeRequests = PENDING_MERGE_REQUESTS.get(target);
        if (mergeRequests == null) return;
        mergeRequests.remove(source);
        if (mergeRequests.isEmpty()) PENDING_MERGE_REQUESTS.remove(target);
    }

    public static boolean hasPendingMergeRequest(Team source, Team target) {
        Set<Team> mergeRequests = PENDING_MERGE_REQUESTS.get(target);
        return mergeRequests != null && mergeRequests.contains(source);
    }

    /**
     * Adds a team, but only if the same team (same name, same owners and same members) does not already exist.
     *
     * @param team Team to be added.
     * @return True if a new team was added, false if this is a duplication of an existing team.
     *
     *         This fixes an issue where the same team would be saved twice to the NBT data after world reload in
     *         singleplayer.
     */
    public static boolean addTeamDeduplicated(Team team) {
        for (Team otherTeam : TEAMS) {
            if (team.getTeamName()
                .equals(otherTeam.getTeamName())
                && team.getOwners()
                    .equals(otherTeam.getOwners())
                && team.getMembers()
                    .equals(otherTeam.getMembers())) {
                return false;
            }
        }
        TEAMS.add(team);
        TEAM_MAP.put(team.getTeamId(), team);
        return true;
    }

    public static void clear() {
        TEAMS.clear();
        TEAM_MAP.clear();
        REMOVED_TEAMS.clear();
        PLAYER_TEAM_CACHE.clear();
        PENDING_INVITES.clear();
        PENDING_MERGE_REQUESTS.clear();
    }

    // --- Public mutators for TeamActions ---

    public static void addRemovedTeam(UUID id) {
        REMOVED_TEAMS.add(id);
    }

    public static void cachePlayerTeam(UUID uuid, Team team) {
        PLAYER_TEAM_CACHE.put(uuid, team);
    }

    public static void removeAllMergeRequestsForTeam(Team team) {
        PENDING_MERGE_REQUESTS.remove(team);
        PENDING_MERGE_REQUESTS.values()
            .forEach(set -> set.remove(team));
        PENDING_MERGE_REQUESTS.values()
            .removeIf(Set::isEmpty);
    }

    public static void removeAllInvitesForTeam(Team team) {
        PENDING_INVITES.values()
            .forEach(set -> set.remove(team));
        PENDING_INVITES.values()
            .removeIf(Set::isEmpty);
    }

    /** Returns an unmodifiable snapshot for save iteration. */
    public static List<Team> getTeamsSnapshot() {
        return new ArrayList<>(TEAMS);
    }

    public static Set<UUID> getRemovedTeamsSnapshot() {
        return new HashSet<>(REMOVED_TEAMS);
    }

    public static void clearRemovedTeams() {
        REMOVED_TEAMS.clear();
    }
}
