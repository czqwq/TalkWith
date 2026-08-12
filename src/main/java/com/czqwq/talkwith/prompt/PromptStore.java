package com.czqwq.talkwith.prompt;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.TalkWith;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Prompt file storage.
 * <ul>
 * <li><b>Server / session-scoped</b> (AI data passes through the server): prompts live at
 * {@code <world>/talkwith/<sessionId>/prompt/<name>.json}, so every team session has its own
 * independent prompt directory.</li>
 * <li><b>Client local</b> (AI runs locally, data never touches the server): prompts live at
 * {@code .minecraft/talkwith/prompt/<name>.json} — no session id, hence no nesting.</li>
 * </ul>
 * Server methods must only be called from the server thread; local methods from the client
 * render thread. The legacy {@code config/talkwith/system_prompt.{json,txt}} files are migrated
 * into the client local directory once on first use.
 */
public final class PromptStore {

    private static final Gson GSON = new Gson();
    private static final String DEFAULT_PROMPT = "You are a helpful assistant in Minecraft.";
    private static final String DEFAULT_PROMPT_FILE = "system_prompt.json";

    private static boolean migrated;

    private PromptStore() {}

    // -------------------------------------------------------------------------
    // Server: session-scoped prompts at <world>/talkwith/<sessionId>/prompt/
    // -------------------------------------------------------------------------

    private static File sessionPromptDir(String sessionId) {
        if (MinecraftServer.getServer() == null || MinecraftServer.getServer().worldServers == null
            || MinecraftServer.getServer().worldServers[0] == null) {
            return null;
        }
        File worldDir = MinecraftServer.getServer().worldServers[0].getSaveHandler()
            .getWorldDirectory();
        return new File(new File(worldDir, "talkwith"), sessionId + File.separator + "prompt");
    }

    /** Lists {@code *.json} prompt files in the given session's prompt directory. */
    public static List<String> listSession(String sessionId) {
        List<String> result = new ArrayList<>();
        File dir = sessionPromptDir(sessionId);
        if (dir == null) return result;
        File[] files = dir.listFiles();
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
     * Returns the prompt text for {@code name} in the given session. Creates the file with a
     * default prompt if it does not exist (so a typo surfaces at set time, not at request time).
     */
    public static String readSession(String sessionId, String name) {
        File dir = sessionPromptDir(sessionId);
        if (dir == null) return DEFAULT_PROMPT;
        String filename = Config.sanitizePromptFilename(name);
        return readFromDir(dir, filename);
    }

    /** Writes a prompt file in the given session's directory (creates directories as needed). */
    public static void writeSession(String sessionId, String name, String content) {
        File dir = sessionPromptDir(sessionId);
        if (dir == null) return;
        String filename = Config.sanitizePromptFilename(name);
        writeToDir(dir, filename, content);
    }

    /** Removes the entire prompt directory for a deleted session. */
    public static void deleteSessionDir(String sessionId) {
        File dir = sessionPromptDir(sessionId);
        if (dir == null) return;
        File sessionRoot = dir.getParentFile();
        if (sessionRoot != null) {
            deleteRecursively(sessionRoot);
        }
    }

    // -------------------------------------------------------------------------
    // Client local: prompts at .minecraft/talkwith/prompt/
    // -------------------------------------------------------------------------

    private static File localPromptDir() {
        if (Minecraft.getMinecraft() == null) return null;
        return new File(new File(Minecraft.getMinecraft().mcDataDir, "talkwith"), "prompt");
    }

    /** Lists {@code *.json} prompt files in the client local prompt directory. */
    public static List<String> listLocal() {
        migrateLegacyIfNeeded();
        List<String> result = new ArrayList<>();
        File dir = localPromptDir();
        if (dir == null) return result;
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (f.isFile() && f.getName()
                .endsWith(".json")) {
                result.add(f.getName());
            }
        }
        return result;
    }

    /** Reads a prompt file from the client local directory, creating a default if missing. */
    public static String readLocal(String name) {
        migrateLegacyIfNeeded();
        File dir = localPromptDir();
        if (dir == null) return DEFAULT_PROMPT;
        String filename = Config.sanitizePromptFilename(name);
        return readFromDir(dir, filename);
    }

    /** Writes a prompt file into the client local directory. */
    public static void writeLocal(String name, String content) {
        File dir = localPromptDir();
        if (dir == null) return;
        String filename = Config.sanitizePromptFilename(name);
        writeToDir(dir, filename, content);
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static String readFromDir(File dir, String filename) {
        File file = new File(dir, filename);
        if (!file.exists()) {
            writeToDir(dir, filename, DEFAULT_PROMPT);
            return DEFAULT_PROMPT;
        }
        try {
            String raw = readFileUtf8(file);
            JsonObject obj = GSON.fromJson(raw, JsonObject.class);
            return obj.get("prompt")
                .getAsString();
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to read prompt file: " + filename, e);
            return DEFAULT_PROMPT;
        }
    }

    private static void writeToDir(File dir, String filename, String content) {
        if (!dir.exists() && !dir.mkdirs()) {
            TalkWith.LOG.error("[TalkWith] Unable to create prompt directory: " + dir);
            return;
        }
        File file = new File(dir, filename);
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("prompt", content != null ? content : "");
            String json = GSON.toJson(obj);
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), "UTF-8"))) {
                writer.write(json);
            }
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to write prompt file: " + file.getName(), e);
        }
    }

    /**
     * One-time migration from the legacy {@code config/talkwith/} location to the client local
     * {@code .minecraft/talkwith/prompt/} directory. Copies {@code system_prompt.json} (or the
     * older {@code system_prompt.txt}) if the new directory has no default prompt yet.
     */
    private static void migrateLegacyIfNeeded() {
        if (migrated || Config.configDir == null) return;
        migrated = true;
        File newDir = localPromptDir();
        if (newDir == null) return;
        File newDefault = new File(newDir, DEFAULT_PROMPT_FILE);
        if (newDefault.exists()) return;
        File legacyJson = new File(Config.configDir, DEFAULT_PROMPT_FILE);
        File legacyTxt = new File(Config.configDir, "system_prompt.txt");
        try {
            if (legacyJson.exists()) {
                copyFile(legacyJson, newDefault);
            } else if (legacyTxt.exists()) {
                writeToDir(newDir, DEFAULT_PROMPT_FILE, readFileUtf8(legacyTxt));
            }
        } catch (Exception e) {
            TalkWith.LOG.error("[TalkWith] Failed to migrate legacy prompt file", e);
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        if (!dst.getParentFile()
            .exists()
            && !dst.getParentFile()
                .mkdirs()) {
            throw new IllegalStateException("Cannot create directory " + dst.getParentFile());
        }
        try (java.io.InputStream in = new FileInputStream(src); java.io.OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
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
