package com.czqwq.talkwith.command;

import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.client.SessionClient;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.gui.GuiAISettings;
import com.czqwq.talkwith.gui.GuiChatHistory;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketJoinSession;
import com.czqwq.talkwith.network.PacketSessionControl;
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

    /**
     * Opens {@link GuiAIChat} if it is not already open and the GUI mode is not vanilla.
     * Also switches from vanilla mode to default mode so the GUI actually receives messages.
     * Used by both {@code /talkwith open} and the {@code "gui"} chat shortcut.
     *
     * <p>
     * The {@link Minecraft#displayGuiScreen} call is deferred to the next client tick via
     * {@link ClientProxy#scheduleOnMainThread}. This is necessary because this method is
     * invoked from within {@code ClientCommandHandler} while the chat screen's key-handler
     * is still executing: without the deferral the chat screen's subsequent
     * {@code mc.displayGuiScreen(null)} call would immediately close the GUI we just opened.
     * </p>
     */
    public static void openGui() {
        if (ClientProxy.useVanillaGui()) {
            // Silently switch to default mode so the GUI works correctly
            Config.guiMode = "default";
            Config.save();
            TextUtils.info(StatCollector.translateToLocal("talkwith.gui.switched_default"));
        }
        // Defer opening to the next tick so the chat screen finishes closing first.
        ClientProxy.scheduleOnMainThread(() -> {
            if (ClientProxy.activeGui == null) {
                ClientGUI.open(new GuiAIChat());
            }
        });
    }

    private static boolean serverFeatureAvailable() {
        return SessionClient.serverFeatureAvailable();
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
                // Always show the current GUI mode
                TextUtils.info(
                    StatCollector.translateToLocalFormatted(
                        "talkwith.status.gui_mode",
                        StatCollector.translateToLocal(
                            ClientProxy.useVanillaGui() ? "talkwith.gui.mode.vanilla" : "talkwith.gui.mode.default")));
            }
            case "history" -> {
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.history.usage"));
                    return;
                }
                if (args[1].equalsIgnoreCase("clear")) {
                    ClientProxy.chatHistory.clear();
                    if (ClientProxy.activeGui instanceof GuiAIChat) {
                        ((GuiAIChat) ClientProxy.activeGui).clearLines();
                    }
                    SessionClient.clearHistory();
                } else if (args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("show")) {
                    if (SessionClient.isMultiMode()) {
                        TextUtils.info(
                            StatCollector
                                .translateToLocalFormatted("talkwith.history.show", ClientProxy.chatHistory.size()));
                    } else {
                        TextUtils.info(
                            StatCollector
                                .translateToLocalFormatted("talkwith.history.show", ClientProxy.clientSession.size()));
                    }
                } else if (args[1].equalsIgnoreCase("open")) {
                    ClientProxy.scheduleOnMainThread(() -> {
                        if (ClientProxy.activeGui instanceof GuiAIChat) {
                            ClientGUI.open(new GuiChatHistory((GuiAIChat) ClientProxy.activeGui));
                        } else {
                            ClientGUI.open(new GuiChatHistory(null));
                        }
                    });
                } else {
                    TextUtils.error(StatCollector.translateToLocalFormatted("talkwith.history.unknown", args[1]));
                }
            }
            case "settings" -> {
                ClientProxy.scheduleOnMainThread(() -> ClientGUI.open(new GuiAISettings()));
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
            case "gui" -> handleGui(sender, args);
            case "open" -> openGui();
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
     * All routing lives in {@link SessionClient}.
     */
    private void handleConfigKey(ICommandSender sender, String[] args) {
        switch (args[1].toLowerCase()) {
            case "baseurl" -> {
                if (args.length < 3) {
                    SessionClient.showBaseUrl();
                } else {
                    SessionClient.applyBaseUrl(args[2]);
                }
            }
            case "keyset" -> {
                if (args.length < 3) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.config.keyset.usage"));
                    return;
                }
                SessionClient.applyApiKey(args[2]);
            }
            case "model" -> {
                if (args.length < 3) {
                    SessionClient.showModel();
                } else {
                    SessionClient.applyModel(args[2]);
                }
            }
            case "prompt_file" -> {
                if (args.length < 3) {
                    SessionClient.showPromptFile();
                } else {
                    SessionClient.applyPromptFile(args[2]);
                }
            }
            case "list_prompts" -> SessionClient.listPrompts();
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
                // Server sends PacketOpenGui("") on success, which clears currentSessionId
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
                // Server sends PacketOpenGui("") on success, which handles state cleanup
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("leave", ""));
            }
            case "list" -> PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("list", ""));
            case "info" -> PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("info", ""));
            case "history" -> {
                if (args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
                    PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("history_clear", ""));
                } else {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.history_usage"));
                }
            }
            default -> TextUtils.info(StatCollector.translateToLocal("talkwith.command.session_usage"));
        }
    }

    // -------------------------------------------------------------------------
    // /talkwith gui [default|vanilla]
    // -------------------------------------------------------------------------

    private void handleGui(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            // No sub-command: open the GUI (same as /talkwith open)
            openGui();
            return;
        }
        switch (args[1].toLowerCase()) {
            case "default" -> {
                Config.guiMode = "default";
                Config.save();
                TextUtils.info(StatCollector.translateToLocal("talkwith.gui.switched_default"));
                // If the player is in a session and the GUI is not open, open it.
                // Defer to the next tick for the same reason as openGui() (see its Javadoc).
                if (ClientProxy.currentSessionId != null && ClientProxy.activeGui == null) {
                    ClientProxy.scheduleOnMainThread(() -> {
                        if (ClientProxy.activeGui == null) {
                            ClientGUI.open(new GuiAIChat());
                        }
                    });
                }
            }
            case "vanilla" -> {
                Config.guiMode = "vanilla";
                Config.save();
                TextUtils.info(StatCollector.translateToLocal("talkwith.gui.switched_vanilla"));
                // Close GuiAIChat if it is currently open
                if (ClientProxy.activeGui != null) {
                    ClientGUI.close();
                }
            }
            case "open" -> openGui();
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
                "settings",
                "session",
                "gui",
                "open");
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
                    return getListOfStringsMatchingLastWord(args, "clear", "show", "open");
                case "session":
                    return getListOfStringsMatchingLastWord(
                        args,
                        "server",
                        "delete",
                        "join",
                        "leave",
                        "list",
                        "info",
                        "history");
                case "gui":
                    return getListOfStringsMatchingLastWord(args, "default", "vanilla", "open");
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
