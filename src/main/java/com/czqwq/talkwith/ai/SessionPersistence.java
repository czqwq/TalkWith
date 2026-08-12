package com.czqwq.talkwith.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.TalkWith;
import com.czqwq.talkwith.util.KeyCipher;
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
            // Encrypt the owner key with the server's config passphrase at rest; the in-memory
            // session always keeps the plaintext for API calls.
            obj.addProperty("ownerApiKey", encryptForStorage(session.ownerApiKey));
            obj.addProperty("sessionModel", session.sessionModel);
            obj.addProperty(
                "sessionPromptFile",
                session.sessionPromptFile != null ? session.sessionPromptFile : "system_prompt.json");
            obj.addProperty("sessionMaxHistory", session.sessionMaxHistory);
            obj.addProperty("lastActivity", session.lastActivity);

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

            JsonElement sessionIdEl = obj.get("sessionId");
            if (sessionIdEl == null || sessionIdEl.isJsonNull())
                throw new IllegalArgumentException("Missing required field: sessionId");
            String sessionId = sessionIdEl.getAsString();

            JsonElement ownerUuidEl = obj.get("ownerUuid");
            if (ownerUuidEl == null || ownerUuidEl.isJsonNull())
                throw new IllegalArgumentException("Missing required field: ownerUuid");
            UUID ownerUuid = UUID.fromString(ownerUuidEl.getAsString());

            // Core fields default gracefully instead of NPE-ing the whole session on a
            // corrupt/older save (previously a single missing key dropped the entire session).
            String ownerName = obj.has("ownerName") ? obj.get("ownerName")
                .getAsString() : "";
            String ownerBaseUrl = obj.has("ownerBaseUrl") ? obj.get("ownerBaseUrl")
                .getAsString() : "";
            String ownerApiKey = obj.has("ownerApiKey") ? decryptForStorage(
                obj.get("ownerApiKey")
                    .getAsString())
                : "";
            String sessionModel = obj.has("sessionModel") ? obj.get("sessionModel")
                .getAsString() : "";
            SharedSession session = new SharedSession(
                sessionId,
                ownerUuid,
                ownerName,
                ownerBaseUrl,
                ownerApiKey,
                sessionModel);

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
            if (obj.has("lastActivity")) {
                session.lastActivity = obj.get("lastActivity")
                    .getAsLong();
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

    /** Encrypts the owner API key at rest using the server config passphrase. */
    private static String encryptForStorage(String plain) {
        if (Config.apiKeyPass.isEmpty()) return plain != null ? plain : "";
        try {
            return KeyCipher.encrypt(plain != null ? plain : "", Config.apiKeyPass);
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to encrypt session API key", e);
            return plain != null ? plain : "";
        }
    }

    /** Decrypts a stored owner API key; legacy plaintext passes through unchanged. */
    private static String decryptForStorage(String stored) {
        if (!KeyCipher.isEncrypted(stored)) return stored;
        try {
            return KeyCipher.decrypt(stored, Config.apiKeyPass);
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to decrypt session API key", e);
            return "";
        }
    }
}
