package com.czqwq.talkwith.teams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.event.world.WorldEvent;

import com.czqwq.talkwith.TalkWith;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Loads/saves team data from/to {@code <world>/talkwith/team/*.nbt}.
 * Registered once in {@code CommonProxy.preInit} so it catches the first
 * {@code WorldEvent.Load} on integrated servers as well.
 */
public class TeamDataSaver {

    private static final Gson GSON = new Gson();
    private static final String REMOVED_FILE = "removed.txt";

    private File saveDir;

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world.isRemote || event.world.provider.dimensionId != 0) return;
        TeamManager.clear();
        // <world>/talkwith/team/ — shared "talkwith" world folder (team NBT + session prompts).
        saveDir = new File(
            new File(
                event.world.getSaveHandler()
                    .getWorldDirectory(),
                "talkwith"),
            "team");
        saveDir.mkdirs();
        loadFromFiles();
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world.isRemote || event.world.provider.dimensionId != 0) return;
        if (saveDir != null) {
            saveToFiles();
            TeamManager.clear();
            saveDir = null;
        }
    }

    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (event.world.isRemote || event.world.provider.dimensionId != 0) return;
        if (saveDir != null) {
            saveToFiles();
        }
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    private void loadFromFiles() {
        TeamManager.clear();
        if (!saveDir.exists()) {
            // saveDir is created with mkdirs() on world load, so this only fires in odd cases.
            TalkWith.LOG.debug("[TalkWith] Team save directory does not exist yet: " + saveDir);
            return;
        }
        Set<String> removed = loadRemovedIds();
        File[] files = saveDir.listFiles((dir, name) -> name.endsWith(".nbt") || name.endsWith(".json"));
        if (files == null) return;
        for (File file : files) {
            if (!file.isFile()) continue;
            try {
                Team team = file.getName()
                    .endsWith(".json") ? loadLegacyJson(file) : loadNbtFile(file);
                // Teams marked as removed must not resurrect after a crash.
                if (removed.contains(
                    team.getTeamId()
                        .toString())) {
                    Files.deleteIfExists(file.toPath());
                    continue;
                }
                TeamManager.addTeamDeduplicated(team);
            } catch (Exception e) {
                TalkWith.LOG.error("Unable to load team {}", file.getName(), e);
            }
        }
        deleteRemovedFile();
    }

    private Team loadNbtFile(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            NBTTagCompound nbt = CompressedStreamTools.readCompressed(in);
            return loadFromNBT(nbt);
        }
    }

    private Team loadLegacyJson(File file) throws Exception {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            JsonObject obj = GSON.fromJson(reader, JsonObject.class);
            NBTTagCompound nbt = (NBTTagCompound) JsonToNBT.func_150315_a(GSON.toJson(obj));
            return loadFromNBT(nbt);
        }
    }

    private Team loadFromNBT(NBTTagCompound teamTag) {
        String teamName = teamTag.getString("TeamName");
        UUID uuid = UUID.fromString(teamTag.getString("UUID"));

        Team team = new Team(teamName, uuid);

        // Owners
        NBTTagList ownersList = teamTag.getTagList("Owners", Constants.NBT.TAG_STRING);
        for (int j = 0; j < ownersList.tagCount(); j++) {
            team.addOwner(UUID.fromString(ownersList.getStringTagAt(j)));
        }

        // Officers
        NBTTagList officersList = teamTag.getTagList("Officers", Constants.NBT.TAG_STRING);
        for (int j = 0; j < officersList.tagCount(); j++) {
            team.addOfficer(UUID.fromString(officersList.getStringTagAt(j)));
        }

        // Members
        NBTTagList membersList = teamTag.getTagList("Members", Constants.NBT.TAG_STRING);
        for (int j = 0; j < membersList.tagCount(); j++) {
            team.addMember(UUID.fromString(membersList.getStringTagAt(j)));
        }

        NBTTagCompound teamDataTag = teamTag.getCompoundTag("TeamData");
        for (String key : TeamDataRegistry.getRegisteredKeys()) {
            try {
                ITeamData data = TeamDataRegistry.construct(key);
                if (data != null && teamDataTag.hasKey(key)) {
                    data.readFromNBT(teamDataTag.getCompoundTag(key));
                }
                team.putData(key, data);
            } catch (Exception ex) {
                TalkWith.LOG.error("Error while loading TeamData {} for team {}", key, uuid, ex);
            }
        }
        team.markCleanOnLoad();
        return team;
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    private void saveToFiles() {
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            TalkWith.LOG.error("Unable to save all teams, unable to create directory: " + saveDir);
            return;
        }

        // Persist the tombstone BEFORE deleting files so a crash between the two
        // operations cannot resurrect a disbanded team on the next load.
        Set<UUID> removed = TeamManager.getRemovedTeamsSnapshot();
        if (!removed.isEmpty()) {
            writeRemovedIds(removed);
        }

        for (Team team : TeamManager.getTeamsSnapshot()) {
            TeamSaveStatus status = team.getStatus();
            if (status == TeamSaveStatus.CLEAN) continue;
            String fileName = team.getTeamId()
                .toString() + ".nbt";
            if (status == TeamSaveStatus.DIRTY) {
                try {
                    File saveFile = new File(saveDir, fileName);
                    try (FileOutputStream out = new FileOutputStream(saveFile)) {
                        CompressedStreamTools.writeCompressed(writeToNBT(team), out);
                    }
                    team.markClean();
                } catch (Exception e) {
                    TalkWith.LOG.error("Unable to save team {} ({})", team.getTeamId(), team.getTeamName(), e);
                }
            }
        }

        for (UUID removedTeam : removed) {
            for (String ext : new String[] { ".nbt", ".json" }) {
                try {
                    Files.deleteIfExists(new File(saveDir, removedTeam + ext).toPath());
                } catch (IOException e) {
                    TalkWith.LOG.error("Unable to delete team {}{}", removedTeam, ext, e);
                }
            }
        }
        TeamManager.clearRemovedTeams();
        deleteRemovedFile();
    }

    private void writeRemovedIds(Set<UUID> removed) {
        try {
            StringBuilder sb = new StringBuilder();
            for (UUID id : removed) {
                sb.append(id.toString())
                    .append('\n');
            }
            Files.write(
                new File(saveDir, REMOVED_FILE).toPath(),
                sb.toString()
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            TalkWith.LOG.error("Unable to write removed teams file", e);
        }
    }

    private Set<String> loadRemovedIds() {
        Set<String> removed = new HashSet<>();
        File file = new File(saveDir, REMOVED_FILE);
        if (!file.exists()) return removed;
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (!line.isEmpty()) removed.add(line);
            }
        } catch (Exception e) {
            TalkWith.LOG.error("Unable to read removed teams file", e);
        }
        return removed;
    }

    private void deleteRemovedFile() {
        try {
            Files.deleteIfExists(new File(saveDir, REMOVED_FILE).toPath());
        } catch (IOException ignored) {}
    }

    private NBTTagCompound writeToNBT(Team team) {
        NBTTagCompound teamTag = new NBTTagCompound();
        teamTag.setString("TeamName", team.getTeamName());
        teamTag.setString(
            "UUID",
            team.getTeamId()
                .toString());

        // Owners
        NBTTagList ownersList = new NBTTagList();
        for (UUID owner : team.getOwners()) {
            ownersList.appendTag(new NBTTagString(owner.toString()));
        }
        teamTag.setTag("Owners", ownersList);

        // Officers
        NBTTagList officersList = new NBTTagList();
        for (UUID officer : team.getOfficers()) {
            officersList.appendTag(new NBTTagString(officer.toString()));
        }
        teamTag.setTag("Officers", officersList);

        // Members
        NBTTagList membersList = new NBTTagList();
        for (UUID member : team.getMembers()) {
            membersList.appendTag(new NBTTagString(member.toString()));
        }
        teamTag.setTag("Members", membersList);

        // Team Data
        NBTTagCompound dataTag = new NBTTagCompound();
        for (Map.Entry<String, ITeamData> entry : team.getAllDataEntries()) {
            try {
                NBTTagCompound entryTag = new NBTTagCompound();
                entry.getValue()
                    .writeToNBT(entryTag);
                dataTag.setTag(entry.getKey(), entryTag);
            } catch (Exception ex) {
                TalkWith.LOG.error(
                    "Error while saving TeamData {} for team {}",
                    entry.getKey(),
                    team.getTeamId()
                        .toString(),
                    ex);
            }
        }
        teamTag.setTag("TeamData", dataTag);

        return teamTag;
    }
}
