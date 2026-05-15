package com.czqwq.talkwith;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static String baseUrl = "https://api.openai.com";
    public static String apiKey = "";
    public static String model = "gpt-3.5-turbo";
    public static String systemPrompt = "You are a helpful assistant in Minecraft.";
    public static int maxHistory = 20;
    public static int timeout = 30;
    public static boolean saveHistory = false;
    public static int replyCooldown = 10;

    static File configFile;
    static Configuration config;
    /** Separate plain-text file for the system prompt (avoids Forge cfg parser issues with Unicode). */
    static File systemPromptFile;

    public static void init(File file) {
        configFile = file;
        systemPromptFile = new File(file.getParentFile(), "system_prompt.txt");
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
        if (config.hasChanged()) {
            config.save();
        }
        systemPrompt = loadSystemPrompt();
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
        config.getCategory("session")
            .get("saveHistory")
            .set(saveHistory);
        if (config.hasChanged()) {
            config.save();
        }
        saveSystemPrompt(systemPrompt);
    }

    // ---------------------------------------------------------------------------
    // system_prompt.txt helpers
    // ---------------------------------------------------------------------------

    private static String loadSystemPrompt() {
        if (systemPromptFile == null || !systemPromptFile.exists()) {
            return "You are a helpful assistant in Minecraft.";
        }
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(systemPromptFile), "UTF-8"));
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
        } catch (Exception e) {
            TalkWith.LOG.error("Failed to read system_prompt.txt", e);
            return "You are a helpful assistant in Minecraft.";
        }
    }

    private static void saveSystemPrompt(String prompt) {
        if (systemPromptFile == null) return;
        try {
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(systemPromptFile), "UTF-8"));
            try {
                writer.write(prompt);
            } finally {
                writer.close();
            }
        } catch (Exception e) {
            TalkWith.LOG.error("Failed to write system_prompt.txt", e);
        }
    }
}
