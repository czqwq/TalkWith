package com.czqwq.talkwith.ai;

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
 * The {@link #toJson}/{@link #fromJson} helpers are used by {@link SessionWorldData} to store
 * session state as JSON strings inside the world save NBT.
 */
public class SessionPersistence {

    private static final Gson GSON = new Gson();

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
            obj.addProperty("lastActivity", session.lastActivity);

            JsonArray modsArray = new JsonArray();
            for (UUID uuid : session.moderators) {
                modsArray.add(new com.google.gson.JsonPrimitive(uuid.toString()));
            }
            obj.add("moderators", modsArray);

            JsonArray joinReqArray = new JsonArray();
            for (UUID uuid : session.joinRequests) {
                joinReqArray.add(new com.google.gson.JsonPrimitive(uuid.toString()));
            }
            obj.add("joinRequests", joinReqArray);

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
            if (obj.has("lastActivity")) {
                session.lastActivity = obj.get("lastActivity")
                    .getAsLong();
            }

            if (obj.has("moderators")) {
                for (JsonElement el : obj.getAsJsonArray("moderators")) {
                    try {
                        session.moderators.add(UUID.fromString(el.getAsString()));
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            if (obj.has("joinRequests")) {
                for (JsonElement el : obj.getAsJsonArray("joinRequests")) {
                    try {
                        session.joinRequests.add(UUID.fromString(el.getAsString()));
                    } catch (IllegalArgumentException ignored) {}
                }
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
}
