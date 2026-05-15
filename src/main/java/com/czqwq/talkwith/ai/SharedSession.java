package com.czqwq.talkwith.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

public class SharedSession {

    public static final Map<String, SharedSession> sessions = new ConcurrentHashMap<>();

    /** Maximum number of recent exchanges kept for history sync on join. */
    private static final int MAX_RECENT = 5;

    public final String sessionId;
    /** Mutable — can be updated when ownership is transferred. */
    public volatile UUID ownerUuid;
    public volatile String ownerName;
    public volatile long lastReplyTime = 0;
    public final Set<UUID> players = new CopyOnWriteArraySet<>();
    public final Set<UUID> mutedPlayers = new CopyOnWriteArraySet<>();
    public final ChatSession session = new ChatSession();
    public volatile int cooldown;
    public volatile String ownerBaseUrl;
    public volatile String ownerApiKey;
    public volatile String sessionModel;

    /**
     * Ring-buffer of recent [playerName, playerMsg, aiReply] triples.
     * Sent to players who join mid-session so they see recent context.
     */
    public final List<String[]> recentMessages = new CopyOnWriteArrayList<>();

    public SharedSession(UUID ownerUuid, String ownerName, String baseUrl, String apiKey, String model) {
        this.sessionId = UUID.randomUUID()
            .toString();
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.ownerBaseUrl = baseUrl;
        this.ownerApiKey = apiKey;
        this.sessionModel = model != null ? model : "";
        this.cooldown = com.czqwq.talkwith.Config.replyCooldown;
        players.add(ownerUuid);
    }

    /**
     * Restore constructor — used by {@link SessionPersistence} when loading sessions from disk.
     * The {@code players} set is intentionally left empty; members must rejoin after a restart.
     */
    public SharedSession(String sessionId, UUID ownerUuid, String ownerName, String baseUrl, String apiKey,
        String model, int cooldown) {
        this.sessionId = sessionId;
        this.ownerUuid = ownerUuid;
        this.ownerName = ownerName;
        this.ownerBaseUrl = baseUrl;
        this.ownerApiKey = apiKey;
        this.sessionModel = model != null ? model : "";
        this.cooldown = cooldown;
        // players intentionally empty — members must rejoin after server restart
    }

    /** Record a completed exchange for history sync. */
    public void addRecentMessage(String playerName, String playerMsg, String aiReply) {
        recentMessages.add(new String[] { playerName, playerMsg, aiReply });
        while (recentMessages.size() > MAX_RECENT) {
            recentMessages.remove(0);
        }
    }

    public boolean isCooldownActive() {
        return System.currentTimeMillis() - lastReplyTime < cooldown * 1000L;
    }

    public boolean isMuted(UUID uuid) {
        return mutedPlayers.contains(uuid);
    }

    public boolean hasPlayer(UUID uuid) {
        return players.contains(uuid);
    }
}
