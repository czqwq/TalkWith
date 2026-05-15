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
 * Persists server-side {@link SharedSession} data (conversation history + settings) to
 * {@code config/talkwith/sessions/<sessionId>.json} so that AI context survives server restarts.
 *
 * <p>
 * Sessions are automatically restored on server start via {@link #loadAll()}. Players need to
 * rejoin manually after a restart, but the full conversation history is immediately available.
 */
public class SessionPersistence {

    private static File sessionsDir;
    private static final Gson GSON = new Gson();

    public static void init(File dir) {
        sessionsDir = dir;
        sessionsDir.mkdirs();
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    /**
     * Writes a session's full state to disk. Called after each successful AI reply so the file is
     * always up-to-date. The write is lightweight (small JSON) and acceptable on the async thread.
     */
    public static void save(SharedSession session) {
        if (sessionsDir == null) return;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("sessionId", session.sessionId);
            obj.addProperty("ownerUuid", session.ownerUuid.toString());
            obj.addProperty("ownerName", session.ownerName);
            obj.addProperty("ownerBaseUrl", session.ownerBaseUrl);
            obj.addProperty("ownerApiKey", session.ownerApiKey);
            obj.addProperty("sessionModel", session.sessionModel);
            obj.addProperty("cooldown", session.cooldown);

            // Full conversation history
            JsonArray histArray = new JsonArray();
            for (ChatMessage msg : session.session.getHistory()) {
                JsonObject m = new JsonObject();
                m.addProperty("role", msg.role);
                m.addProperty("content", msg.content);
                histArray.add(m);
            }
            obj.add("history", histArray);

            // Recent exchanges (used for join-time history sync)
            JsonArray recentArray = new JsonArray();
            for (String[] entry : session.recentMessages) {
                JsonArray e = new JsonArray();
                e.add(new com.google.gson.JsonPrimitive(entry[0]));
                e.add(new com.google.gson.JsonPrimitive(entry[1]));
                e.add(new com.google.gson.JsonPrimitive(entry[2]));
                recentArray.add(e);
            }
            obj.add("recentMessages", recentArray);

            File file = new File(sessionsDir, session.sessionId + ".json");
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), "UTF-8"));
            try {
                writer.write(GSON.toJson(obj));
            } finally {
                writer.close();
            }
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to save session " + session.sessionId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /** Removes the on-disk file for a session that has been closed or deleted. */
    public static void delete(String sessionId) {
        if (sessionsDir == null) return;
        File file = new File(sessionsDir, sessionId + ".json");
        if (file.exists() && !file.delete()) {
            TalkWith.LOG.warn("[TalkWith] Could not delete session file: " + file.getPath());
        }
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    /**
     * Scans {@code sessions/} and restores all previously saved sessions into
     * {@link SharedSession#sessions}. Called once during server startup.
     *
     * <p>
     * Restored sessions have an empty {@code players} set — members must rejoin after a restart.
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
                SharedSession session = readSession(file);
                if (session != null) {
                    SharedSession.sessions.put(session.sessionId, session);
                    loaded++;
                }
            } catch (Exception e) {
                TalkWith.LOG.error("[TalkWith] Failed to load session from " + file.getName(), e);
            }
        }
        if (loaded > 0) {
            TalkWith.LOG.info("[TalkWith] Restored " + loaded + " session(s) from disk.");
        }
    }

    private static SharedSession readSession(File file) throws Exception {
        String raw = readFileUtf8(file);
        JsonObject obj = GSON.fromJson(raw, JsonObject.class);

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

        // Restore history
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

        // Restore recent messages
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
    }

    private static String readFileUtf8(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }
}
