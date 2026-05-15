package com.czqwq.talkwith.command;

import java.util.Arrays;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;

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
        return "/talkwith [baseurl|keyset|model|system_prompt|reload|history|join|single|share|mute|unmute|kick|cooldown|close]";
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
                    TextUtils.error("Usage: /talkwith baseurl <url>");
                    return;
                }
                Config.baseUrl = args[1];
                Config.save();
                TextUtils.info("Base URL set to: " + Config.baseUrl);
                TextUtils.info("Pinging API...");
                ApiPinger.ping();
            }
            case "keyset" -> {
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith keyset <key>");
                    return;
                }
                Config.apiKey = args[1];
                Config.save();
                TextUtils.info("API key updated.");
                TextUtils.info("Pinging API...");
                ApiPinger.ping();
            }
            case "model" -> {
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith model <name>");
                    return;
                }
                Config.model = args[1];
                Config.save();
                TextUtils.info("Model set to: " + Config.model);
            }
            case "system_prompt" -> {
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith system_prompt <...words>");
                    return;
                }
                Config.systemPrompt = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Config.save();
                TextUtils.info("System prompt updated.");
            }
            case "reload" -> {
                Config.load();
                TextUtils.info("Configuration reloaded.");
            }
            case "history" -> {
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith history <clear|show>");
                    return;
                }
                if (args[1].equalsIgnoreCase("clear")) {
                    ClientProxy.clientSession.clear();
                    TextUtils.info("Chat history cleared.");
                } else if (args[1].equalsIgnoreCase("show")) {
                    TextUtils.info("History: " + ClientProxy.clientSession.size() + " messages.");
                } else {
                    TextUtils.error("Unknown history sub-command: " + args[1]);
                }
            }
            case "join" -> {
                if (!ClientProxy.serverHasMod) {
                    TextUtils.error("Server does not have TalkWith installed.");
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith join <sessionId>");
                    return;
                }
                String sessionId = args[1];
                ClientProxy.currentSessionId = sessionId;
                PacketHandler.INSTANCE.sendToServer(new PacketJoinSession(sessionId));
            }
            case "single" -> {
                if (!ClientProxy.serverHasMod) {
                    TextUtils.error("Server does not have TalkWith installed.");
                    return;
                }
                ClientProxy.currentSessionId = null;
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("single", ""));
            }
            // --- Server session management (requires server to have TalkWith) ---
            case "share" -> {
                if (!ClientProxy.serverHasMod) {
                    TextUtils.error("Server does not have TalkWith installed.");
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith share <playerName>");
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("share", args[1]));
            }
            case "mute" -> {
                if (!ClientProxy.serverHasMod) {
                    TextUtils.error("Server does not have TalkWith installed.");
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith mute <playerName>");
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("mute", args[1]));
            }
            case "unmute" -> {
                if (!ClientProxy.serverHasMod) {
                    TextUtils.error("Server does not have TalkWith installed.");
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith unmute <playerName>");
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("unmute", args[1]));
            }
            case "kick" -> {
                if (!ClientProxy.serverHasMod) {
                    TextUtils.error("Server does not have TalkWith installed.");
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith kick <playerName>");
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("kick", args[1]));
            }
            case "cooldown" -> {
                if (!ClientProxy.serverHasMod) {
                    TextUtils.error("Server does not have TalkWith installed.");
                    return;
                }
                if (args.length < 2) {
                    TextUtils.error("Usage: /talkwith cooldown <seconds>");
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("cooldown", args[1]));
            }
            case "close" -> {
                if (!ClientProxy.serverHasMod) {
                    TextUtils.error("Server does not have TalkWith installed.");
                    return;
                }
                PacketHandler.INSTANCE.sendToServer(new PacketSessionControl("close", ""));
            }
            default -> TextUtils.error("Unknown sub-command. " + getCommandUsage(sender));
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
