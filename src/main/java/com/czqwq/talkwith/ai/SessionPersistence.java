package com.czqwq.talkwith.ai;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.czqwq.talkwith.TalkWith;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Handles (de)serialization of {@link SharedSession} to/from JSON.
 *
 * <p>
 * The primary save location is now {@link SessionWorldData} (world save).
 * This class is retained for:
 * <ul>
 * <li>Migration from older versions (JSON files in {@code config/talkwith/sessions/}).</li>
 * <li>The {@link #toJson}/{@link #fromJson} helpers used by {@link SessionWorldData}.</li>
 * </ul>
 *
 * <p>
 * {@link #save} and {@link #delete} are preserved for emergency/manual use;
 * the main code path uses {@link SessionWorldData#markDirty()} instead.
 */
public class SessionPersistence {

    private static File sessionsDir;
    private static final Gson GSON = new Gson();

    public static void init(File dir) {
        sessionsDir = dir;
        sessionsDir.mkdirs();
    }

    // -------------------------------------------------------------------------
    // JSON serialization helpers — used by SessionWorldData
    // -------------------------------------------------------------------------

    /** Serializes a {@link SharedSession} to a JSON string. Returns null on error. */
    public static String toJson(SharedSession session) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("sessionId", session.sessionId);
            obj.addProperty("sessionName", session.sessionName != null ? session.sessionName : "");
            obj.addProperty("ownerUuid", session.ownerUuid.toString());
            obj.addProperty("ownerName", session.ownerName);
            obj.addProperty("ownerBaseUrl", session.ownerBaseUrl);
            obj.addProperty("ownerApiKey", session.ownerApiKey);
            obj.addProperty("sessionModel", session.sessionModel);
            obj.addProperty(
                "sessionPromptFile",
                session.sessionPromptFile != null ? session.sessionPromptFile : "system_prompt.json");
            obj.addProperty("cooldown", session.cooldown);
            obj.addProperty("sessionMaxHistory", session.sessionMaxHistory);
            obj.addProperty("isPublic", session.isPublic);

            JsonArray histArray = new JsonArray();
            for (ChatMessage msg : session.session.getHistory()) {
                JsonObject m = new JsonObject();
                m.addProperty("role", msg.role);
                m.addProperty("content", msg.content);
                histArray.add(m);
            }
            obj.add("history", histArray);

            JsonArray recentArray = new JsonArray();
            for (String[] entry : session.recentMessages) {
                JsonArray e = new JsonArray();
                e.add(new com.google.gson.JsonPrimitive(entry[0]));
                e.add(new com.google.gson.JsonPrimitive(entry[1]));
                e.add(new com.google.gson.JsonPrimitive(entry[2]));
                recentArray.add(e);
            }
            obj.add("recentMessages", recentArray);
            return GSON.toJson(obj);
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to serialize session " + session.sessionId, e);
            return null;
        }
    }

    /**
     * Deserializes a JSON string into a {@link SharedSession}.
     * Returns null if the JSON is invalid or missing required fields.
     * Backward-compatible: optional fields default gracefully.
     */
    public static SharedSession fromJson(String json) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null) return null;

            String sessionId = obj.get("sessionId")
                .getAsString();
            UUID ownerUuid = UUID.fromString(
                obj.get("ownerUuid")
                    .getAsString());
            String ownerName = obj.get("ownerName")
                .getAsString();
            String ownerBaseUrl = obj.get("ownerBaseUrl")
                .getAsString();
            String ownerApiKey = obj.get("ownerApiKey")
                .getAsString();
            String sessionModel = obj.get("sessionModel")
                .getAsString();
            int cooldown = obj.get("cooldown")
                .getAsInt();

            SharedSession session = new SharedSession(
                sessionId,
                ownerUuid,
                ownerName,
                ownerBaseUrl,
                ownerApiKey,
                sessionModel,
                cooldown);

            // Optional fields (added in later versions)
            if (obj.has("sessionName") && !obj.get("sessionName")
                .getAsString()
                .isEmpty()) {
                session.sessionName = obj.get("sessionName")
                    .getAsString();
            }
            if (obj.has("sessionPromptFile") && !obj.get("sessionPromptFile")
                .getAsString()
                .isEmpty()) {
                session.sessionPromptFile = obj.get("sessionPromptFile")
                    .getAsString();
            }
            if (obj.has("sessionMaxHistory")) {
                session.sessionMaxHistory = obj.get("sessionMaxHistory")
                    .getAsInt();
            }
            if (obj.has("isPublic")) {
                session.isPublic = obj.get("isPublic")
                    .getAsBoolean();
            }

            if (obj.has("history")) {
                List<ChatMessage> history = new ArrayList<>();
                for (JsonElement el : obj.getAsJsonArray("history")) {
                    JsonObject m = el.getAsJsonObject();
                    history.add(
                        new ChatMessage(
                            m.get("role")
                                .getAsString(),
                            m.get("content")
                                .getAsString()));
                }
                session.session.loadHistory(history);
            }

            if (obj.has("recentMessages")) {
                for (JsonElement el : obj.getAsJsonArray("recentMessages")) {
                    JsonArray e = el.getAsJsonArray();
                    session.recentMessages.add(
                        new String[] { e.get(0)
                            .getAsString(),
                            e.get(1)
                                .getAsString(),
                            e.get(2)
                                .getAsString() });
                }
            }

            return session;
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to deserialize session JSON", e);
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // File-based save/delete — kept for emergency/manual use and migration
    // -------------------------------------------------------------------------

    /**
     * Writes a session's full state to {@code config/talkwith/sessions/<id>.json}.
     * Prefer {@link SessionWorldData#markDirty()} for normal operation.
     */
    public static void save(SharedSession session) {
        if (sessionsDir == null) return;
        String json = toJson(session);
        if (json == null) return;
        try {
            File file = new File(sessionsDir, session.sessionId + ".json");
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
                writer.write(json);
            }
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to save session file " + session.sessionId, e);
        }
    }

    /** Removes the on-disk file for a session that has been closed. */
    public static void delete(String sessionId) {
        if (sessionsDir == null) return;
        File file = new File(sessionsDir, sessionId + ".json");
        if (file.exists() && !file.delete()) {
            TalkWith.LOG.warn("[TalkWith] Could not delete session file: " + file.getPath());
        }
    }

    // -------------------------------------------------------------------------
    // Migration: load all JSON files (used on first run with new world-save system)
    // -------------------------------------------------------------------------

    /**
     * Scans {@code config/talkwith/sessions/} and loads any saved sessions.
     * Used only for one-time migration to {@link SessionWorldData}.
     */
    public static void loadAll() {
        if (sessionsDir == null || !sessionsDir.exists()) return;
        File[] files = sessionsDir.listFiles(new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".json");
            }
        });
        if (files == null) return;

        int loaded = 0;
        for (File file : files) {
            try {
                String raw = readFileUtf8(file);
                SharedSession session = fromJson(raw);
                if (session != null) {
                    SharedSession.sessions.put(session.sessionId, session);
                    loaded++;
                }
            } catch (Exception e) {
                TalkWith.LOG.error("[TalkWith] Failed to load session from " + file.getName(), e);
            }
        }
        if (loaded > 0) {
            TalkWith.LOG.info("[TalkWith] Migrated " + loaded + " session(s) from JSON files.");
        }
    }

    private static String readFileUtf8(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
        }
        return sb.toString();
    }
}
