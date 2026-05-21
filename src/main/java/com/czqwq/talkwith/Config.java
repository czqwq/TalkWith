package com.czqwq.talkwith;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.config.Configuration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class Config {

    private static final Gson GSON = new Gson();
    public static String baseUrl = "https://api.openai.com";
    public static String apiKey = "";
    public static String model = "gpt-3.5-turbo";
    /**
     * In-memory cache of the currently loaded client prompt.
     * Updated whenever {@link #clientPromptFile} is changed or config is reloaded.
     * Client-side only — server sessions use {@link #loadPromptFromFile(String)} directly.
     */
    public static String systemPrompt = "You are a helpful assistant in Minecraft.";
    /** Filename (relative to {@link #configDir}) for the client's active prompt. */
    public static String clientPromptFile = "system_prompt.json";
    public static int maxHistory = 20;
    public static int timeout = 30;
    public static boolean saveHistory = false;
    public static int replyCooldown = 10;

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
        apiKey = config.getString("apiKey", "auth", "", "API key (keep secret!)");
        maxHistory = config.getInt("maxHistory", "chat", 20, 1, 200, "Max conversation history pairs");
        replyCooldown = config.getInt("replyCooldown", "chat", 10, 0, 3600, "Cooldown between AI replies (seconds)");
        saveHistory = config.getBoolean("saveHistory", "session", false, "Save conversation history across sessions");
        clientPromptFile = config.getString(
            "promptFile",
            "chat",
            "system_prompt.json",
            "Prompt JSON file for client mode (file in config/talkwith/)");
        if (config.hasChanged()) {
            config.save();
        }
        // Backward compat: migrate old system_prompt.txt if needed
        migrateLegacyTxtPrompt();
        systemPrompt = loadPromptFromFile(clientPromptFile);
    }

    public static void save() {
        config.getCategory("api")
            .get("baseUrl")
            .set(baseUrl);
        config.getCategory("api")
            .get("model")
            .set(model);
        config.getCategory("api")
            .get("timeout")
            .set(timeout);
        config.getCategory("auth")
            .get("apiKey")
            .set(apiKey);
        config.getCategory("chat")
            .get("maxHistory")
            .set(maxHistory);
        config.getCategory("chat")
            .get("replyCooldown")
            .set(replyCooldown);
        config.getCategory("chat")
            .get("promptFile")
            .set(clientPromptFile);
        config.getCategory("session")
            .get("saveHistory")
            .set(saveHistory);
        if (config.hasChanged()) {
            config.save();
        }
    }

    // ---------------------------------------------------------------------------
    // Prompt file helpers — used by both client and server
    // ---------------------------------------------------------------------------

    /**
     * Loads the {@code prompt} field from {@code <configDir>/<filename>}.
     * If the file does not exist, it is created with default content and the
     * default prompt string is returned. Filename must not contain path
     * separators (validated by the caller or by {@link #sanitizePromptFilename}).
     */
    public static String loadPromptFromFile(String filename) {
        if (configDir == null) return "You are a helpful assistant in Minecraft.";
        if (filename == null || filename.isEmpty()) filename = "system_prompt.json";
        // Safety: strip any path traversal
        filename = sanitizePromptFilename(filename);
        File file = new File(configDir, filename);
        if (!file.exists()) {
            String defaultPrompt = "You are a helpful assistant in Minecraft.";
            writePromptFile(file, defaultPrompt);
            return defaultPrompt;
        }
        try {
            String raw = readFileUtf8(file);
            JsonObject obj = GSON.fromJson(raw, JsonObject.class);
            return obj.get("prompt")
                .getAsString();
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to read prompt file: " + filename, e);
            return "You are a helpful assistant in Minecraft.";
        }
    }

    /**
     * Returns the names of all {@code *.json} files directly inside
     * {@link #configDir} (not in sub-directories).
     */
    public static List<String> listPromptFiles() {
        List<String> result = new ArrayList<>();
        if (configDir == null) return result;
        File[] files = configDir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isFile() && f.getName()
                .endsWith(".json")) {
                result.add(f.getName());
            }
        }
        return result;
    }

    /**
     * Ensures {@code filename} is a bare filename with no directory components.
     * Preserves the {@code .json} extension requirement by appending it if missing.
     */
    public static String sanitizePromptFilename(String filename) {
        // Strip any leading path components
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (slash >= 0) filename = filename.substring(slash + 1);
        if (filename.isEmpty()) filename = "system_prompt.json";
        if (!filename.endsWith(".json")) filename = filename + ".json";
        return filename;
    }

    /** Writes a single-key {@code {"prompt": "..."}} JSON file. */
    public static void writePromptFile(File file, String prompt) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("prompt", prompt);
            String json = GSON.toJson(obj);
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
                writer.write(json);
            }
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to write prompt file: " + file.getName(), e);
        }
    }

    /** One-time migration: if {@code system_prompt.txt} exists and {@code system_prompt.json} doesn't, convert it. */
    private static void migrateLegacyTxtPrompt() {
        if (configDir == null) return;
        File jsonFile = new File(configDir, "system_prompt.json");
        File txtFile = new File(configDir, "system_prompt.txt");
        if (!jsonFile.exists() && txtFile.exists()) {
            try {
                String prompt = readFileUtf8(txtFile);
                writePromptFile(jsonFile, prompt);
            } catch (Exception e) {
                TalkWith.LOG.error("[TalkWith] Failed to migrate system_prompt.txt", e);
            }
        }
    }

    public static String readFileUtf8(File file) throws Exception {
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
