package com.czqwq.talkwith;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

import com.czqwq.talkwith.util.KeyCipher;

public class Config {

    public static String baseUrl = "https://api.openai.com";
    /** In-memory plaintext API key (used by the AI service). On disk it is encrypted with {@link #apiKeyPass}. */
    public static String apiKey = "";
    /**
     * The user-entered short passphrase used to encrypt {@link #apiKey} at rest
     * (AES + PBKDF2 via {@link com.czqwq.talkwith.util.KeyCipher}). Persisted so the key
     * decrypts automatically on the next launch ("remember key" choice). Empty = legacy
     * plaintext storage.
     */
    public static String apiKeyPass = "";
    /**
     * True when the stored API key is encrypted but the last load() could not decrypt it
     * (wrong passphrase). While set, {@link #apiKey} is unusable, the GUI shows a warning
     * instead of the plaintext, and save() preserves the stored ciphertext instead of
     * overwriting it. Cleared once a correct passphrase or a fresh key is applied.
     */
    public static boolean apiKeyDecryptFailed = false;
    public static String model = "gpt-3.5-turbo";
    /** Filename (relative to {@link #configDir}) for the client's active prompt. */
    public static String clientPromptFile = "system_prompt.json";

    // --- Team system ---
    /** Root name for TalkWith Team commands. */
    public static String teamCommandRoot = "tw_team";
    /** The public-facing name for the team system. */
    public static String teamSystemName = "TalkWithTeams";
    /** Max token budget for AI model context window. */
    public static int maxTokens = 4096;
    public static int maxHistory = 20;
    public static int timeout = 30;
    /**
     * Client GUI mode: {@code "default"} opens {@link com.czqwq.talkwith.gui.GuiAIChat},
     * {@code "vanilla"} routes all AI I/O through the vanilla chat HUD.
     * Persisted in {@code talkwith.cfg} so the preference survives game restarts.
     * Applied to {@link com.czqwq.talkwith.ClientProxy#useVanillaGui} during client preInit.
     */
    public static String guiMode = "default";

    static File configFile;
    static Configuration config;
    /** The {@code config/talkwith/} directory — available after {@link #init}. */
    public static File configDir;

    public static void init(File file) {
        configFile = file;
        configDir = file.getParentFile();
        config = new Configuration(file);
        load();
    }

    public static void load() {
        config.load();
        baseUrl = config.getString("baseUrl", "api", "https://api.openai.com", "OpenAI-compatible API base URL");
        model = config.getString("model", "api", "gpt-3.5-turbo", "Model name");
        timeout = config.getInt("timeout", "api", 30, 1, 300, "Request timeout in seconds");
        maxTokens = config.getInt("maxTokens", "api", 4096, 256, 128000, "Max token budget for context window");
        apiKeyPass = config.getString("apiKeyPass", "auth", "", "Passphrase to encrypt apiKey at rest (remembered)");
        apiKey = decryptStoredKey(
            config.getString("apiKey", "auth", "", "API key (encrypted with apiKeyPass; keep secret!)"),
            apiKeyPass);
        maxHistory = config.getInt("maxHistory", "chat", 20, 1, 200, "Max conversation history pairs");
        guiMode = config.getString(
            "guiMode",
            "client",
            "default",
            "Client GUI mode: default (GuiAIChat) or vanilla (vanilla chat HUD)");
        clientPromptFile = config.getString(
            "promptFile",
            "chat",
            "system_prompt.json",
            "Prompt JSON file for client mode (file in config/talkwith/)");
        teamCommandRoot = config.getString("teamCommandRoot", "teams", "tw_team", "Root command name for team system");
        teamSystemName = config
            .getString("teamSystemName", "teams", "TalkWithTeams", "Public-facing name for team system");
        if (config.hasChanged()) {
            config.save();
        }
    }

    public static void save() {
        // Defensive: every property below is created by load(), but guard against null so
        // save() never NPEs if it is ever called before load(). Call order: init -> load -> save.
        setProperty("api", "baseUrl", baseUrl);
        setProperty("api", "model", model);
        setProperty("api", "timeout", timeout);
        setProperty("api", "maxTokens", maxTokens);
        if (apiKeyDecryptFailed) {
            // The stored key is encrypted but the passphrase is wrong, so we never had the
            // plaintext. Preserve the ciphertext instead of overwriting it with the empty
            // apiKey (which apiKeyForStorage() would otherwise write as plaintext "").
            setProperty("auth", "apiKey", getStoredApiKeyRaw());
        } else {
            setProperty("auth", "apiKey", apiKeyForStorage());
        }
        setProperty("auth", "apiKeyPass", apiKeyPass);
        setProperty("chat", "maxHistory", maxHistory);
        setProperty("chat", "promptFile", clientPromptFile);
        setProperty("client", "guiMode", guiMode);
        setProperty("teams", "teamCommandRoot", teamCommandRoot);
        setProperty("teams", "teamSystemName", teamSystemName);
        if (config.hasChanged()) {
            config.save();
        }
    }

    /** Writes a property only if it exists (created by {@link #load()}), skipping it otherwise. */
    private static void setProperty(String category, String key, Object value) {
        net.minecraftforge.common.config.Property prop = config.getCategory(category)
            .get(key);
        if (prop == null) return;
        if (value instanceof Integer) {
            prop.set((Integer) value);
        } else if (value instanceof Boolean) {
            prop.set((Boolean) value);
        } else if (value instanceof Double) {
            prop.set((Double) value);
        } else {
            prop.set(String.valueOf(value));
        }
    }

    /** Returns the encrypted-at-rest form of {@link #apiKey} using {@link #apiKeyPass}. */
    private static String apiKeyForStorage() {
        if (apiKeyPass.isEmpty()) {
            // Empty passphrase = plaintext storage. Not silently: warn so it is never
            // downgraded from encrypted to plaintext without the user noticing.
            if (!apiKey.isEmpty()) {
                TalkWith.LOG.warn("[TalkWith] Storing the API key as plaintext (no encryption passphrase set).");
            }
            return apiKey;
        }
        try {
            return KeyCipher.encrypt(apiKey, apiKeyPass);
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to encrypt API key", e);
            return apiKey;
        }
    }

    /** The raw on-disk apiKey value (the ciphertext when encrypted, plaintext for legacy configs). */
    public static String getStoredApiKeyRaw() {
        if (config == null) return "";
        net.minecraftforge.common.config.Property p = config.getCategory("auth")
            .get("apiKey");
        return p != null && p.getString() != null ? p.getString() : "";
    }

    /** True when the apiKey on disk is stored encrypted (so the GUI must not show the plaintext). */
    public static boolean isStoredApiKeyEncrypted() {
        return KeyCipher.isEncrypted(getStoredApiKeyRaw());
    }

    /**
     * Decrypts the stored key into {@link #apiKey}. Legacy plaintext passes through unchanged.
     * On a wrong passphrase, sets {@link #apiKeyDecryptFailed} and logs instead of silently
     * clearing — the GUI then prompts the user to re-enter the encryption key.
     */
    private static String decryptStoredKey(String stored, String passphrase) {
        if (!KeyCipher.isEncrypted(stored)) {
            apiKeyDecryptFailed = false;
            return stored;
        }
        try {
            String decrypted = KeyCipher.decrypt(stored, passphrase);
            apiKeyDecryptFailed = false;
            return decrypted;
        } catch (Exception e) {
            TalkWith.LOG.error(
                "[TalkWith] 无法解密 API 密钥(加密密钥口令错误?)—— 请重新输入加密密钥. "
                    + "The stored key is preserved and will not be overwritten.",
                e);
            apiKeyDecryptFailed = true;
            return ""; // unusable in memory, but the ciphertext is kept via getStoredApiKeyRaw()
        }
    }

    // ---------------------------------------------------------------------------
    // Prompt filename sanitization
    // ---------------------------------------------------------------------------

    /**
     * Ensures {@code filename} is a bare filename with no directory components.
     * Preserves the {@code .json} extension requirement by appending it if missing.
     * Prompt file storage itself lives in {@link com.czqwq.talkwith.prompt.PromptStore}.
     */
    public static String sanitizePromptFilename(String filename) {
        if (filename == null) filename = "system_prompt.json";
        // Strip any leading path components
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (slash >= 0) filename = filename.substring(slash + 1);
        if (filename.isEmpty()) filename = "system_prompt.json";
        if (!filename.endsWith(".json")) filename = filename + ".json";
        return filename;
    }
}
