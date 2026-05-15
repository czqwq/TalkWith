package com.czqwq.talkwith;

import java.io.File;
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

    public static void init(File file) {
        configFile = file;
        config = new Configuration(file);
        load();
    }

    public static void load() {
        config.load();
        baseUrl = config.getString("baseUrl", "api", "https://api.openai.com", "OpenAI-compatible API base URL");
        model = config.getString("model", "api", "gpt-3.5-turbo", "Model name");
        timeout = config.getInt("timeout", "api", 30, 1, 300, "Request timeout in seconds");
        apiKey = config.getString("apiKey", "auth", "", "API key (keep secret!)");
        systemPrompt = config.getString("systemPrompt", "chat", "You are a helpful assistant in Minecraft.", "System prompt");
        maxHistory = config.getInt("maxHistory", "chat", 20, 1, 200, "Max conversation history pairs");
        replyCooldown = config.getInt("replyCooldown", "chat", 10, 0, 3600, "Cooldown between AI replies (seconds)");
        saveHistory = config.getBoolean("saveHistory", "session", false, "Save conversation history across sessions");
        if (config.hasChanged()) {
            config.save();
        }
    }

    public static void save() {
        config.getCategory("api").get("baseUrl").set(baseUrl);
        config.getCategory("api").get("model").set(model);
        config.getCategory("api").get("timeout").set(timeout);
        config.getCategory("auth").get("apiKey").set(apiKey);
        config.getCategory("chat").get("systemPrompt").set(systemPrompt);
        config.getCategory("chat").get("maxHistory").set(maxHistory);
        config.getCategory("chat").get("replyCooldown").set(replyCooldown);
        config.getCategory("session").get("saveHistory").set(saveHistory);
        if (config.hasChanged()) {
            config.save();
        }
    }
}
