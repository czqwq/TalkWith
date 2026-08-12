package com.czqwq.talkwith.util;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;

public final class CommandUtils {

    public static void success(ICommandSender sender, String transKey, Object... args) {
        ChatComponentTranslation msg = new ChatComponentTranslation(transKey, args);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.GREEN);
        sender.addChatMessage(msg);
    }

    public static void error(ICommandSender sender, String transKey, Object... args) {
        ChatComponentTranslation msg = new ChatComponentTranslation(transKey, args);
        msg.getChatStyle()
            .setColor(EnumChatFormatting.RED);
        sender.addChatMessage(msg);
    }

    public static ChatComponentText colorChatComponent(EnumChatFormatting format, String string) {
        ChatComponentText newComponent = new ChatComponentText(string);
        newComponent.getChatStyle()
            .setColor(format);
        return newComponent;
    }
}
