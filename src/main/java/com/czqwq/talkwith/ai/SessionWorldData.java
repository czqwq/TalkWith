package com.czqwq.talkwith.ai;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

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
        if (!nbt.hasKey("sessions")) return;

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
            }
        }
        if (loaded > 0) {
            TalkWith.LOG.info("[TalkWith] Restored " + loaded + " session(s) from world save data.");
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
                list.appendTag(entry);
            }
        }
        nbt.setTag("sessions", list);
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
     * Loads sessions from world save data. If the world data is empty (first run / migration),
     * falls back to {@link SessionPersistence#loadAll()} to import legacy JSON files, then
     * immediately marks the world data dirty so the migration is persisted on the next save.
     *
     * <p>
     * Must be called after the overworld (dimension 0) is available —
     * i.e. from a {@code WorldEvent.Load} handler.
     */
    public static void restore() {
        SessionWorldData data = get();
        if (data == null) {
            // World not available — fall back to JSON (e.g. integrated server edge cases)
            TalkWith.LOG.warn("[TalkWith] World not available for SessionWorldData; falling back to JSON files.");
            SessionPersistence.loadAll();
            return;
        }
        // readFromNBT was already invoked by loadData() above (if the file existed).
        if (SharedSession.sessions.isEmpty()) {
            // Either a brand-new world or first run after upgrading — migrate JSON files.
            SessionPersistence.loadAll();
            if (!SharedSession.sessions.isEmpty()) {
                data.markDirty();
                TalkWith.LOG.info(
                    "[TalkWith] Migrated " + SharedSession.sessions.size()
                        + " session(s) from JSON to world save data.");
            }
        }
    }
}
