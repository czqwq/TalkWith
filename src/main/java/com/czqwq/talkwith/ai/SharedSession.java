package com.czqwq.talkwith.ai;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class SharedSession {

    public static final Map<String, SharedSession> sessions = new ConcurrentHashMap<>();

    public final String sessionId;
    public final UUID ownerUuid;
    public final String ownerName;
    public volatile long lastReplyTime = 0;
    public final Set<UUID> players = new CopyOnWriteArraySet<>();
    public final Set<UUID> mutedPlayers = new CopyOnWriteArraySet<>();
    public final ChatSession session = new ChatSession();
    public volatile int cooldown;
    public volatile String ownerBaseUrl;
    public volatile String ownerApiKey;
    public volatile String sessionModel;

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
