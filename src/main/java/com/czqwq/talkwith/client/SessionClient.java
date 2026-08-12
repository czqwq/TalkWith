package com.czqwq.talkwith.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketPromptWrite;
import com.czqwq.talkwith.network.PacketSessionControl;
import com.czqwq.talkwith.prompt.PromptStore;
import com.czqwq.talkwith.util.ApiPinger;
import com.czqwq.talkwith.util.KeyCipher;
import com.czqwq.talkwith.util.TextUtils;

/**
 * Client facade for session-related remote actions. Routes each action to the
 * server session (multi mode) or to the local config (single mode) depending on
 * {@link #isMultiMode()}. Used by the command layer and the GUI.
 */
public final class SessionClient {

    private SessionClient() {}

    public static boolean serverFeatureAvailable() {
        return ClientProxy.serverHasMod || Minecraft.getMinecraft()
            .isIntegratedServerRunning();
    }

    /** True when the player is in a server session in multi mode. */
    public static boolean isMultiMode() {
        return ClientProxy.currentSessionId != null && !ClientProxy.isSingleOverride && serverFeatureAvailable();
    }

    public static void showBaseUrl() {
        if (isMultiMode()) {
            TextUtils.info(StatCollector.translateToLocal("talkwith.config.multi.view_hint"));
        } else {
            TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.config.baseurl.show", Config.baseUrl));
        }
    }

    public static void applyBaseUrl(String url) {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("setting_baseurl", url));
        } else {
            Config.baseUrl = url;
            Config.save();
            TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.baseurl.set", Config.baseUrl));
            TextUtils.info(StatCollector.translateToLocal("talkwith.api.pinging"));
            ApiPinger.ping();
        }
    }

    public static void applyApiKey(String key) {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("setting_apikey", key));
        } else {
            Config.apiKey = key;
            // The user supplied a fresh key, so any previous decrypt failure (wrong
            // passphrase) is resolved by replacement — allow save() to write the new key.
            Config.apiKeyDecryptFailed = false;
            Config.save();
            TextUtils.info(StatCollector.translateToLocal("talkwith.api.key_updated"));
            TextUtils.info(StatCollector.translateToLocal("talkwith.api.pinging"));
            ApiPinger.ping();
        }
    }

    /**
     * Sets the passphrase used to encrypt the client config API key at rest (AES + PBKDF2).
     * Always client-local; an empty passphrase reverts to plaintext storage.
     * <ul>
     * <li>If the stored key could not be decrypted (wrong passphrase), the newly entered
     * passphrase is first tried against the ciphertext to recover the plaintext before
     * re-encrypting — a wrong one is rejected instead of silently clearing the key.</li>
     * <li>An empty passphrase is not silently applied: the user is warned that the key will
     * be stored as plaintext.</li>
     * </ul>
     */
    public static void applyPassphrase(String passphrase) {
        String p = passphrase == null ? "" : passphrase;

        // Recovery: decrypt failed on load because the old passphrase was wrong. Try the
        // newly entered one against the preserved ciphertext before re-encrypting.
        if (Config.apiKeyDecryptFailed && !p.isEmpty() && Config.isStoredApiKeyEncrypted()) {
            try {
                Config.apiKey = KeyCipher.decrypt(Config.getStoredApiKeyRaw(), p);
                Config.apiKeyDecryptFailed = false;
            } catch (Exception e) {
                TextUtils.error(StatCollector.translateToLocal("talkwith.api.passphrase_wrong"));
                return; // keep the ciphertext intact; do not change the passphrase
            }
        }

        if (p.isEmpty() && !Config.apiKey.isEmpty()) {
            // Never silently downgrade an encrypted key to plaintext.
            TextUtils.error(StatCollector.translateToLocal("talkwith.api.passphrase_empty_warning"));
        }

        Config.apiKeyPass = p;
        Config.save();
        TextUtils.info(StatCollector.translateToLocal("talkwith.api.passphrase_updated"));
    }

    public static void showModel() {
        if (isMultiMode()) {
            TextUtils.info(StatCollector.translateToLocal("talkwith.config.multi.view_hint"));
        } else {
            TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.config.model.show", Config.model));
        }
    }

    public static void applyModel(String model) {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("setting_model", model));
        } else {
            Config.model = model;
            Config.save();
            TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.model.set", Config.model));
        }
    }

    public static void showPromptFile() {
        if (isMultiMode()) {
            TextUtils.info(StatCollector.translateToLocal("talkwith.config.multi.view_hint"));
        } else {
            TextUtils.info(
                StatCollector.translateToLocalFormatted("talkwith.config.prompt_file.show", Config.clientPromptFile));
        }
    }

    public static void applyPromptFile(String filename) {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("cfg_prompt_file", filename));
        } else {
            String sanitized = Config.sanitizePromptFilename(filename);
            // Ensure the file exists (creates a default) before activating it.
            PromptStore.readLocal(sanitized);
            Config.clientPromptFile = sanitized;
            Config.save();
            TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.config.prompt_file.set", sanitized));
        }
    }

    /**
     * Resolves the active prompt text for a local (non-session) AI call. Reads the client-local
     * prompt directory {@code .minecraft/talkwith/prompt/}.
     */
    public static String resolvePromptText() {
        return PromptStore.readLocal(Config.clientPromptFile);
    }

    /** Requests the session prompt list (multi mode). In single mode the caller reads the local list directly. */
    public static void requestPromptList() {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("prompt_list", ""));
        }
    }

    /** Requests a prompt file's content from the session (multi mode). */
    public static void readPrompt(String name) {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("prompt_read", name));
        }
    }

    /** Writes a prompt file into the session prompt directory (multi mode, owner only). */
    public static void writePrompt(String name, String content) {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketPromptWrite(name, content));
        }
    }

    public static void listPrompts() {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("cfg_list_prompts", ""));
            return;
        }
        List<String> files = PromptStore.listLocal();
        if (files.isEmpty()) {
            TextUtils.info(StatCollector.translateToLocal("talkwith.config.prompts_list_empty"));
        } else {
            TextUtils
                .info(StatCollector.translateToLocalFormatted("talkwith.config.prompts_list_header", files.size()));
            for (String f : files) {
                TextUtils.info("  §7- §f" + f);
            }
        }
    }

    /** Clears the conversation history of the active mode (session or local). */
    public static void clearHistory() {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("history_clear", ""));
        } else {
            ClientProxy.clientSession.clear();
            TextUtils.info(StatCollector.translateToLocal("talkwith.history.cleared"));
        }
    }

    /** Requests the current session's AI settings from the server to seed the settings GUI (multi mode). */
    public static void requestSessionSettings() {
        if (isMultiMode()) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("setting_get", ""));
        }
    }

    /** Toggles the single-mode override for the current session and informs the server. */
    public static void toggleSingleOverride() {
        ClientProxy.isSingleOverride = !ClientProxy.isSingleOverride;
        PacketHandler.INSTANCE.sendToServer(
            new PacketSessionControl(ClientProxy.isSingleOverride ? "switch_single" : "switch_multi", ""));
    }
}
