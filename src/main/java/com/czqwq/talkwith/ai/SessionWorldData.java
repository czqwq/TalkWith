package com.czqwq.talkwith.ai;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

import com.czqwq.talkwith.ServerEventHandler;
import com.czqwq.talkwith.TalkWith;

/**
 * Persists all active {@link SharedSession} data inside the world save directory at
 * {@code <world>/data/talkwith_sessions.dat}.
 *
 * <p>
 * Saves are triggered by calling {@link #markDirty()} (or the static {@link #save()}), which
 * schedules the data to be written on the next world-save tick. The full session state
 * (settings, conversation history, recent messages) is stored as JSON strings nested inside
 * NBT compound tags so that the existing {@link SessionPersistence} (de)serializer can be
 * reused without duplication.
 *
 * <p>
 * <b>Loading:</b> call {@link #restore()} from a {@code WorldEvent.Load} handler once the
 * overworld (dimension 0) is available. If the world data file does not yet exist (e.g. first
 * run, or migration from an older version) {@link SessionPersistence#loadAll()} is called as a
 * one-time fallback to import any JSON files from {@code config/talkwith/sessions/}.
 */
public class SessionWorldData extends WorldSavedData {

    public static final String DATA_NAME = "talkwith_sessions";

    /** NBT list element type: 10 = NBTTagCompound */
    private static final int NBT_COMPOUND = 10;

    /**
     * Maps playerUuid → sessionId for players who disconnected while inside a session.
     * Consumed on reconnect to restore the player to their previous session automatically.
     * Persisted inside the world save alongside session data.
     */
    public static final Map<UUID, String> playerSessionMap = new ConcurrentHashMap<>();

    /**
     * Players who had {@code /talkwith switch single} active when they disconnected.
     * Persisted here and consumed on reconnect to restore single-override state.
     * Includes currently-online players between saves (written to NBT on world save).
     */
    public static final Set<UUID> singleOverrideSet = new CopyOnWriteArraySet<>();

    public SessionWorldData(String name) {
        super(name);
    }

    // -------------------------------------------------------------------------
    // WorldSavedData contract
    // -------------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        // Clear existing sessions — this is only called once on world load.
        SharedSession.sessions.clear();
        playerSessionMap.clear();
        singleOverrideSet.clear();

        if (nbt.hasKey("sessions")) {
            NBTTagList list = nbt.getTagList("sessions", NBT_COMPOUND);
            int loaded = 0;
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound entry = list.getCompoundTagAt(i);
                String json = entry.getString("data");
                if (json == null || json.isEmpty()) continue;
                SharedSession session = SessionPersistence.fromJson(json);
                if (session != null) {
                    SharedSession.sessions.put(session.sessionId, session);
                    loaded++;

                    // Rebuild playerSessionMap from the "players" sub-tag as a fallback
                    // for crash recovery (when onPlayerLogout never fired for these players).
                    if (entry.hasKey("players")) {
                        NBTTagList playersList = entry.getTagList("players", NBT_COMPOUND);
                        for (int j = 0; j < playersList.tagCount(); j++) {
                            String uuidStr = playersList.getCompoundTagAt(j)
                                .getString("uuid");
                            if (uuidStr != null && !uuidStr.isEmpty()) {
                                try {
                                    UUID pUuid = UUID.fromString(uuidStr);
                                    // putIfAbsent: the explicit playerSessions map takes precedence
                                    playerSessionMap.putIfAbsent(pUuid, session.sessionId);
                                } catch (IllegalArgumentException ignored) {}
                            }
                        }
                    }
                }
            }
            if (loaded > 0) {
                TalkWith.LOG.info("[TalkWith] Restored " + loaded + " session(s) from world save data.");
            }
        }

        // Restore player→session mapping for reconnect restoration
        if (nbt.hasKey("playerSessions")) {
            NBTTagList psList = nbt.getTagList("playerSessions", NBT_COMPOUND);
            for (int i = 0; i < psList.tagCount(); i++) {
                NBTTagCompound entry = psList.getCompoundTagAt(i);
                String uuidStr = entry.getString("uuid");
                String sid = entry.getString("sid");
                if (uuidStr != null && !uuidStr.isEmpty() && sid != null && !sid.isEmpty()) {
                    try {
                        playerSessionMap.put(UUID.fromString(uuidStr), sid);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        // Restore singleModeOverride state for offline players
        if (nbt.hasKey("singleOverride")) {
            NBTTagList soList = nbt.getTagList("singleOverride", NBT_COMPOUND);
            for (int i = 0; i < soList.tagCount(); i++) {
                String uuidStr = soList.getCompoundTagAt(i)
                    .getString("uuid");
                if (uuidStr != null && !uuidStr.isEmpty()) {
                    try {
                        singleOverrideSet.add(UUID.fromString(uuidStr));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (SharedSession session : SharedSession.sessions.values()) {
            String json = SessionPersistence.toJson(session);
            if (json != null) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setString("data", json);
                // Persist the current player set alongside the session JSON so that
                // playerSessionMap can be rebuilt on load (see readFromNBT).
                NBTTagList sessionPlayers = new NBTTagList();
                for (UUID pUuid : session.players) {
                    NBTTagCompound pe = new NBTTagCompound();
                    pe.setString("uuid", pUuid.toString());
                    sessionPlayers.appendTag(pe);
                }
                entry.setTag("players", sessionPlayers);
                list.appendTag(entry);
            }
        }
        nbt.setTag("sessions", list);

        // Persist player→session mapping
        NBTTagList psList = new NBTTagList();
        for (Map.Entry<UUID, String> entry : playerSessionMap.entrySet()) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString(
                "uuid",
                entry.getKey()
                    .toString());
            e.setString("sid", entry.getValue());
            psList.appendTag(e);
        }
        nbt.setTag("playerSessions", psList);

        // Persist singleModeOverride: union of offline set and currently-online players
        java.util.Set<UUID> allSingleOverride = new java.util.HashSet<>();
        allSingleOverride.addAll(singleOverrideSet);
        allSingleOverride.addAll(ServerEventHandler.singleModeOverride);
        NBTTagList soList = new NBTTagList();
        for (UUID uuid : allSingleOverride) {
            NBTTagCompound e = new NBTTagCompound();
            e.setString("uuid", uuid.toString());
            soList.appendTag(e);
        }
        nbt.setTag("singleOverride", soList);
    }

    // -------------------------------------------------------------------------
    // Static helpers
    // -------------------------------------------------------------------------

    /**
     * Gets (or creates) the {@link SessionWorldData} from the overworld's map storage.
     * Returns {@code null} if the server or overworld is not yet available.
     */
    public static SessionWorldData get() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null || server.worldServers.length == 0) return null;
        net.minecraft.world.WorldServer world = server.worldServers[0];
        if (world == null) return null;
        MapStorage storage = world.mapStorage;
        if (storage == null) return null;
        SessionWorldData data = (SessionWorldData) storage.loadData(SessionWorldData.class, DATA_NAME);
        if (data == null) {
            data = new SessionWorldData(DATA_NAME);
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    /**
     * Marks the world data as dirty so it will be flushed on the next world save.
     * Call this after any session mutation (create, delete, AI reply, setting change).
     */
    public static void save() {
        SessionWorldData data = get();
        if (data != null) data.markDirty();
    }

    /**
     * Loads sessions from world save data.
     *
     * <p>
     * Must be called after the overworld (dimension 0) is available —
     * i.e. from a {@code WorldEvent.Load} handler.
     */
    public static void restore() {
        SessionWorldData data = get();
        if (data == null) {
            TalkWith.LOG.warn("[TalkWith] World not available for SessionWorldData; no sessions restored.");
            return;
        }
        // readFromNBT was already invoked by loadData() above (if the file existed).
    }
}
