package com.czqwq.talkwith.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

public class TextUtils {
    public static final String PREFIX = "§a[TalkWith]§r ";
    public static final String PREFIX_ERROR = "§c[TalkWith]§r ";

    public static void addChatMessage(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(msg));
        }
    }

    public static void info(String msg) {
        addChatMessage(PREFIX + msg);
    }

    public static void error(String msg) {
        addChatMessage(PREFIX_ERROR + msg);
    }
}
