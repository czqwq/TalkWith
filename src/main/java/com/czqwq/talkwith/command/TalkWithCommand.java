package com.czqwq.talkwith.command;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketJoinSession;
import com.czqwq.talkwith.network.PacketSessionControl;
import com.czqwq.talkwith.util.ApiPinger;
import com.czqwq.talkwith.util.TextUtils;

public class TalkWithCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "talkwith";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return StatCollector.translateToLocal("talkwith.command.usage");
    }

    private static boolean serverFeatureAvailable() {
        return ClientProxy.serverHasMod || Minecraft.getMinecraft()
            .isIntegratedServerRunning();
    }

    /**
     * Returns {@code true} when the player is currently in a server session in multi mode.
     * Used by {@link #handleConfigKey} to route config changes to the session vs. local Config.
     */
    private static boolean isMultiMode() {
        return ClientProxy.currentSessionId != null && !ClientProxy.isSingleOverride && serverFeatureAvailable();
    }

    // -------------------------------------------------------------------------
    // Main dispatch
    // -------------------------------------------------------------------------

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            TextUtils.info(getCommandUsage(sender));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "config" -> {
                if (args.length < 2) {
                    TextUtils.info(StatCollector.translateToLocal("talkwith.config.usage"));
                    return;
                }
                handleConfig(sender, args);
            }
            case "status" -> {
                if (ClientProxy.currentSessionId != null) {
                    if (!serverFeatureAvailable()) {
                        // Fallback if server lost mod somehow
                        TextUtils.info(
                            StatCollector.translateToLocalFormatted(
                                "talkwith.status.session_basic",
                                ClientProxy.currentSessionId));
                    } else {
                        // Query server for live session details
                        PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("status_info", ""));
                    }
                } else {
                    TextUtils.info(
                        StatCollector.translateToLocalFormatted(
                            "talkwith.status.client",
                            Config.baseUrl,
                            Config.model,
                            Config.clientPromptFile));
                }
                // Show takeover state if active
                if (ClientProxy.isTakeover) {
                    TextUtils.info(
                        StatCollector
                            .translateToLocalFormatted("talkwith.status.takeover", ClientProxy.takeoverChatMode));
                }
                // Always show the current GUI mode
                TextUtils.info(
                    StatCollector.translateToLocalFormatted(
                        "talkwith.status.gui_mode",
                        StatCollector.translateToLocal(
                            ClientProxy.useVanillaGui ? "talkwith.gui.mode.vanilla" : "talkwith.gui.mode.default")));
            }
            case "history" -> {
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.history.usage"));
                    return;
                }
                if (args[1].equalsIgnoreCase("clear")) {
                    ClientProxy.clientSession.clear();
                    TextUtils.info(StatCollector.translateToLocal("talkwith.history.cleared"));
                } else if (args[1].equalsIgnoreCase("show")) {
                    TextUtils.info(
                        StatCollector
                            .translateToLocalFormatted("talkwith.history.show", ClientProxy.clientSession.size()));
                } else {
                    TextUtils.error(StatCollector.translateToLocalFormatted("talkwith.history.unknown", args[1]));
                }
            }
            case "session" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.info(StatCollector.translateToLocal("talkwith.command.session_usage"));
                    return;
                }
                handleSession(sender, args);
            }
            case "switch" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.switch.usage"));
                    return;
                }
                if (ClientProxy.currentSessionId == null) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.switch.not_in_session"));
                    return;
                }
                if (args[1].equalsIgnoreCase("single")) {
                    ClientProxy.isSingleOverride = true;
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("switch_single", ""));
                } else if (args[1].equalsIgnoreCase("multi")) {
                    ClientProxy.isSingleOverride = false;
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("switch_multi", ""));
                } else {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.switch.usage"));
                }
            }
            case "takeover" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                // Optimistically toggle client state then confirm with server
                ClientProxy.isTakeover = !ClientProxy.isTakeover;
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("takeover_toggle", ""));
            }
            case "chat" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.chat.usage"));
                    return;
                }
                String mode = args[1].toLowerCase();
                if (!mode.equals("group") && !mode.equals("public") && !mode.equals("ai")) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.chat.usage"));
                    return;
                }
                ClientProxy.takeoverChatMode = mode;
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("chat_mode", mode));
            }
            case "gui" -> handleGui(sender, args);
            default -> TextUtils
                .error(StatCollector.translateToLocalFormatted("talkwith.unknown_sub", getCommandUsage(sender)));
        }
    }

    // -------------------------------------------------------------------------
    // /talkwith config <key> [value] (auto-routes to single or multi session)
    // -------------------------------------------------------------------------

    private void handleConfig(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            TextUtils.info(StatCollector.translateToLocal("talkwith.config.usage"));
            return;
        }
        if (args[1].equalsIgnoreCase("reload")) {
            Config.load();
            TextUtils.info(StatCollector.translateToLocal("talkwith.config.reloaded"));
            return;
        }
        handleConfigKey(sender, args);
    }

    /**
     * Handles {@code /talkwith config <key> [value]}.
     *
     * <p>
     * If the player is currently in a server session and has NOT activated the single-mode
     * override, the command targets that session's settings (multi mode). Otherwise the command
     * modifies the player's own local (single-mode) settings stored in {@link Config}.
     */
    private void handleConfigKey(ICommandSender sender, String[] args) {
        boolean isMulti = isMultiMode();

        switch (args[1].toLowerCase()) {
            case "baseurl" -> {
                if (args.length < 3) {
                    if (isMulti) {
                        TextUtils.info(StatCollector.translateToLocal("talkwith.config.multi.view_hint"));
                    } else {
                        TextUtils.info(
                            StatCollector.translateToLocalFormatted("talkwith.config.baseurl.show", Config.baseUrl));
                    }
                    return;
                }
                if (isMulti) {
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("setting_baseurl", args[2]));
                } else {
                    Config.baseUrl = args[2];
                    Config.save();
                    TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.baseurl.set", Config.baseUrl));
                    TextUtils.info(StatCollector.translateToLocal("talkwith.api.pinging"));
                    ApiPinger.ping();
                }
            }
            case "keyset" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.config.keyset.usage"));
                    return;
                }
                if (isMulti) {
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("setting_apikey", args[2]));
                } else {
                    Config.apiKey = args[2];
                    Config.save();
                    TextUtils.info(StatCollector.translateToLocal("talkwith.api.key_updated"));
                    TextUtils.info(StatCollector.translateToLocal("talkwith.api.pinging"));
                    ApiPinger.ping();
                }
            }
            case "model" -> {
                if (args.length < 3) {
                    if (isMulti) {
                        TextUtils.info(StatCollector.translateToLocal("talkwith.config.multi.view_hint"));
                    } else {
                        TextUtils
                            .info(StatCollector.translateToLocalFormatted("talkwith.config.model.show", Config.model));
                    }
                    return;
                }
                if (isMulti) {
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("setting_model", args[2]));
                } else {
                    Config.model = args[2];
                    Config.save();
                    TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.model.set", Config.model));
                }
            }
            case "prompt_file" -> {
                if (args.length < 3) {
                    if (isMulti) {
                        TextUtils.info(StatCollector.translateToLocal("talkwith.config.multi.view_hint"));
                    } else {
                        TextUtils.info(
                            StatCollector.translateToLocalFormatted(
                                "talkwith.config.prompt_file.show",
                                Config.clientPromptFile));
                    }
                    return;
                }
                if (isMulti) {
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("cfg_prompt_file", args[2]));
                } else {
                    String filename = Config.sanitizePromptFilename(args[2]);
                    Config.loadPromptFromFile(filename);
                    Config.clientPromptFile = filename;
                    Config.save();
                    TextUtils
                        .info(StatCollector.translateToLocalFormatted("talkwith.config.prompt_file.set", filename));
                }
            }
            case "list_prompts" -> {
                if (isMulti) {
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("cfg_list_prompts", ""));
                } else {
                    java.util.List<String> files = Config.listPromptFiles();
                    if (files.isEmpty()) {
                        TextUtils.info(StatCollector.translateToLocal("talkwith.config.prompts_list_empty"));
                    } else {
                        TextUtils.info(
                            StatCollector
                                .translateToLocalFormatted("talkwith.config.prompts_list_header", files.size()));
                        for (String f : files) {
                            TextUtils.info("  §7- §f" + f);
                        }
                    }
                }
            }
            default -> TextUtils.info(StatCollector.translateToLocal("talkwith.config.usage"));
        }
    }

    // -------------------------------------------------------------------------
    // /talkwith session <sub> [...]
    // -------------------------------------------------------------------------

    private void handleSession(ICommandSender sender, String[] args) {
        switch (args[1].toLowerCase()) {
            case "server" -> {
                if (args.length < 3) {
                    TextUtils.info(StatCollector.translateToLocal("talkwith.command.session_usage"));
                    return;
                }
                if (args[2].equalsIgnoreCase("create")) {
                    // Name is mandatory: /talkwith session server create <name>
                    if (args.length < 4 || args[3].trim()
                        .isEmpty()) {
                        TextUtils.error(StatCollector.translateToLocal("talkwith.session.name_required"));
                        return;
                    }
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("server_create", args[3]));
                } else {
                    TextUtils.info(StatCollector.translateToLocal("talkwith.command.session_usage"));
                }
            }
            case "delete" -> {
                ClientProxy.currentSessionId = null;
                ClientProxy.isSingleOverride = false;
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("delete", ""));
            }
            case "join" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.join_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketJoinSession(args[2]));
            }
            case "leave" -> {
                ClientProxy.isSingleOverride = false;
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("leave", ""));
            }
            case "list" -> PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("list", ""));
            case "info" -> PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("info", ""));
            case "invite" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.invite_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("invite", args[2]));
            }
            case "kick" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.kick_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("kick", args[2]));
            }
            case "mute" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.mute_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("mute", args[2]));
            }
            case "unmute" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.unmute_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("unmute", args[2]));
            }
            case "cooldown" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.cooldown_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("cooldown", args[2]));
            }
            case "history" -> {
                if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("history_clear", ""));
                } else {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.history_usage"));
                }
            }
            case "rename" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.rename_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("rename", args[2]));
            }
            case "setmod" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.setmod_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("setmod", args[2]));
            }
            case "removemod" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.removemod_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("removemod", args[2]));
            }
            case "request" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.request_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("request", args[2]));
            }
            case "accept" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.accept_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("accept", args[2]));
            }
            case "deny" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.deny_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("deny", args[2]));
            }
            case "public" -> PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("set_public", ""));
            case "private" -> PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("set_private", ""));
            case "transfer" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.transfer_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("transfer", args[2]));
            }
            default -> TextUtils.info(StatCollector.translateToLocal("talkwith.command.session_usage"));
        }
    }

    // -------------------------------------------------------------------------
    // /talkwith gui [default|vanilla]
    // -------------------------------------------------------------------------

    private void handleGui(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            // Show current mode
            TextUtils.info(
                StatCollector.translateToLocal(
                    ClientProxy.useVanillaGui ? "talkwith.gui.mode.vanilla" : "talkwith.gui.mode.default"));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "default" -> {
                ClientProxy.useVanillaGui = false;
                Config.guiMode = "default";
                Config.save();
                TextUtils.info(StatCollector.translateToLocal("talkwith.gui.switched_default"));
                // If the player is in a session and the GUI is not open, open it
                if (ClientProxy.currentSessionId != null && ClientProxy.activeGui == null) {
                    Minecraft.getMinecraft()
                        .displayGuiScreen(new GuiAIChat(""));
                }
            }
            case "vanilla" -> {
                ClientProxy.useVanillaGui = true;
                Config.guiMode = "vanilla";
                Config.save();
                TextUtils.info(StatCollector.translateToLocal("talkwith.gui.switched_vanilla"));
                // Close GuiAIChat if it is currently open
                if (ClientProxy.activeGui != null) {
                    Minecraft.getMinecraft()
                        .displayGuiScreen(null);
                }
            }
            default -> TextUtils.error(StatCollector.translateToLocal("talkwith.gui.usage"));
        }
    }

    // -------------------------------------------------------------------------
    // Tab completion
    // -------------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(
                args,
                "config",
                "status",
                "history",
                "session",
                "switch",
                "takeover",
                "chat",
                "gui");
        }
        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "config":
                    return getListOfStringsMatchingLastWord(
                        args,
                        "baseurl",
                        "keyset",
                        "model",
                        "prompt_file",
                        "list_prompts",
                        "reload");
                case "history":
                    return getListOfStringsMatchingLastWord(args, "clear", "show");
                case "session":
                    return getListOfStringsMatchingLastWord(
                        args,
                        "server",
                        "delete",
                        "join",
                        "leave",
                        "list",
                        "info",
                        "invite",
                        "kick",
                        "mute",
                        "unmute",
                        "cooldown",
                        "history",
                        "rename",
                        "setmod",
                        "removemod",
                        "request",
                        "accept",
                        "deny",
                        "public",
                        "private",
                        "transfer");
                case "switch":
                    return getListOfStringsMatchingLastWord(args, "single", "multi");
                case "chat":
                    return getListOfStringsMatchingLastWord(args, "group", "public", "ai");
                case "gui":
                    return getListOfStringsMatchingLastWord(args, "default", "vanilla");
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("config")) {
                // All config sub-keys are at depth 2 now; no further completion needed
            }
            if (args[0].equalsIgnoreCase("session")) {
                if (args[1].equalsIgnoreCase("server")) {
                    return getListOfStringsMatchingLastWord(args, "create");
                }
                if (args[1].equalsIgnoreCase("history")) {
                    return getListOfStringsMatchingLastWord(args, "clear");
                }
            }
        }
        return null;
    }
}
