package com.czqwq.talkwith.command;

import java.util.Arrays;
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

    /**
     * Returns true when server-side features are available: either the dedicated server has
     * the mod installed (signalled by the handshake packet) or we are running on an integrated
     * server (singleplayer or LAN opened-to-LAN), where the mod is always present.
     */
    private static boolean serverFeatureAvailable() {
        return ClientProxy.serverHasMod || Minecraft.getMinecraft()
            .isIntegratedServerRunning();
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            // Schedule on next tick so the chat GUI has time to close first
            ClientProxy.scheduleOnMainThread(
                () -> Minecraft.getMinecraft()
                    .displayGuiScreen(new GuiAIChat("")));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "baseurl" -> {
                if (args.length < 2) {
                    TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.baseurl.show", Config.baseUrl));
                    return;
                }
                Config.baseUrl = args[1];
                Config.save();
                TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.baseurl.set", Config.baseUrl));
                TextUtils.info(StatCollector.translateToLocal("talkwith.api.pinging"));
                ApiPinger.ping();
            }
            case "keyset" -> {
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith keyset <key>");
                    return;
                }
                Config.apiKey = args[1];
                Config.save();
                TextUtils.info(StatCollector.translateToLocal("talkwith.api.key_updated"));
                TextUtils.info(StatCollector.translateToLocal("talkwith.api.pinging"));
                ApiPinger.ping();
            }
            case "model" -> {
                if (args.length < 2) {
                    TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.model.show", Config.model));
                    return;
                }
                Config.model = args[1];
                Config.save();
                TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.model.set", Config.model));
            }
            case "system_prompt" -> {
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith system_prompt <...words>");
                    return;
                }
                Config.systemPrompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Config.save();
                TextUtils.info(StatCollector.translateToLocal("talkwith.system_prompt.set"));
            }
            case "reload" -> {
                Config.load();
                TextUtils.info(StatCollector.translateToLocal("talkwith.config.reloaded"));
            }
            case "history" -> {
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith history <clear|show>");
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
            case "join" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.join_usage"));
                    return;
                }
                String sessionId = args[1];
                ClientProxy.currentSessionId = sessionId;
                PacketHandler.INSTANCE.sendToServer(new PacketJoinSession(sessionId));
            }
            case "single" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                ClientProxy.currentSessionId = null;
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("single", ""));
            }
            // --- Server session management (requires server to have TalkWith) ---
            case "share" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.share_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("share", args[1]));
            }
            case "mute" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.mute_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("mute", args[1]));
            }
            case "unmute" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.unmute_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("unmute", args[1]));
            }
            case "kick" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.kick_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("kick", args[1]));
            }
            case "cooldown" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.session.cooldown_usage"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("cooldown", args[1]));
            }
            case "close" -> {
                if (!serverFeatureAvailable()) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.server.no_mod"));
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("close", ""));
            }
            default -> TextUtils
                .error(StatCollector.translateToLocalFormatted("talkwith.unknown_sub", getCommandUsage(sender)));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(
                args,
                "baseurl",
                "keyset",
                "model",
                "system_prompt",
                "reload",
                "history",
                "join",
                "single",
                "share",
                "mute",
                "unmute",
                "kick",
                "cooldown",
                "close");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            return getListOfStringsMatchingLastWord(args, "clear", "show");
        }
        return null;
    }
}
