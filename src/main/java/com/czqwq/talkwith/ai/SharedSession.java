package com.czqwq.talkwith.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

public class SharedSession {

    public static final Map<String, SharedSession> sessions = new ConcurrentHashMap<>();

    /** Maximum number of recent exchanges kept for history sync on join. */
    private static final int MAX_RECENT = 5;

    public final String sessionId;
    /** Optional human-readable name for the session (used for join-by-name). */
    public volatile String sessionName = "";
    /** Filename (in config/talkwith/) of the JSON prompt file used for this session. */
    public volatile String sessionPromptFile = "system_prompt.json";
    /** Mutable — can be updated when ownership is transferred. */
    public volatile UUID ownerUuid;
    public volatile String ownerName;
    public volatile long lastReplyTime = 0;
    /** Unix timestamp (ms) of the last AI reply. Used to sort the session list. */
    public volatile long lastActivity = 0;
    public final Set<UUID> players = new CopyOnWriteArraySet<>();
    public final Set<UUID> mutedPlayers = new CopyOnWriteArraySet<>();
    /** Players granted moderator privileges by the owner. */
    public final Set<UUID> moderators = new CopyOnWriteArraySet<>();
    /** Pending join requests for private sessions. Approved via {@code /talkwith session accept}. */
    public final Set<UUID> joinRequests = new CopyOnWriteArraySet<>();
    public final ChatSession session = new ChatSession();
    public volatile int cooldown;
    public volatile String ownerBaseUrl;
    public volatile String ownerApiKey;
    public volatile String sessionModel;
    /** Per-session max history pair count. 0 means use the global {@link com.czqwq.talkwith.Config#maxHistory}. */
    public volatile int sessionMaxHistory = 0;
    /** If false, only players in {@link #invitedPlayers} may join. Defaults to true (public). */
    public volatile boolean isPublic = true;
    /** Pending invitations for private sessions. Consumed (removed) when the player joins. */
    public final Set<UUID> invitedPlayers = new CopyOnWriteArraySet<>();
    /**
     * Guards against concurrent AI requests for the same session.
     * Set to {@code true} when an AI request is in-flight; cleared in the reply/error callback.
     * Use {@code compareAndSet(false, true)} before dispatching a new request.
     */
    public final AtomicBoolean isProcessing = new AtomicBoolean(false);

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

    public boolean isMod(UUID uuid) {
        return moderators.contains(uuid);
    }

    public boolean isOwnerOrMod(UUID uuid) {
        return ownerUuid.equals(uuid) || moderators.contains(uuid);
    }

    public boolean hasPlayer(UUID uuid) {
        return players.contains(uuid);
    }
}
