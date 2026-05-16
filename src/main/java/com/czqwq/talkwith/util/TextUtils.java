package com.czqwq.talkwith.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

public class TextUtils {

    public static final String PREFIX = "§a[TalkWith]§r ";
    public static final String PREFIX_ERROR = "§c[TalkWith]§r ";

    /** Safety limit for a single chat segment — MC wraps visually, but very long strings can cause issues. */
    private static final int MAX_LINE_LENGTH = 240;

    /** First printable ASCII character — used to filter raw control characters from AI output. */
    private static final char MIN_PRINTABLE_CHAR = 0x20;

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

    /**
     * Sends an AI reply to local chat, properly handling multi-line responses and
     * stripping raw control characters (e.g. bare LF/CR) that would appear as
     * placeholder text ("LF", "CR") in Minecraft 1.7.10's FontRenderer.
     *
     * <p>
     * The first segment is shown with {@code linePrefix}; continuation lines
     * are indented so the reader can follow the full reply.
     * </p>
     *
     * @param linePrefix prefix for the first message, e.g. {@code "§b[AI]: §r"}
     * @param reply      raw AI reply (may contain {@code \n}, {@code \r\n}, etc.)
     */
    public static void sendAIReply(String linePrefix, String reply) {
        if (reply == null || reply.isEmpty()) return;

        // Normalize all line-ending variants to \n
        String normalized = reply.replace("\r\n", "\n")
            .replace("\r", "\n");

        // Strip control characters that Minecraft cannot render (keep \n for splitting,
        // keep printable chars >= 0x20 including MC §-codes)
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '\n' || c >= MIN_PRINTABLE_CHAR) {
                sb.append(c);
            }
        }

        // Split on newlines
        String[] parts = sb.toString()
            .split("\n", -1);
        boolean firstMsg = true;
        for (String line : parts) {
            if (line.isEmpty()) continue; // skip blank lines

            // Chunk excessively long single lines at word boundaries
            while (line.length() > MAX_LINE_LENGTH) {
                int cut = MAX_LINE_LENGTH;
                int space = line.lastIndexOf(' ', cut);
                if (space > cut / 2) cut = space;
                String chunk = line.substring(0, cut)
                    .trim();
                if (!chunk.isEmpty()) {
                    addChatMessage(firstMsg ? linePrefix + chunk : "§7  §r" + chunk);
                    firstMsg = false;
                }
                line = line.substring(cut)
                    .trim();
            }

            if (!line.isEmpty()) {
                addChatMessage(firstMsg ? linePrefix + line : "§7  §r" + line);
                firstMsg = false;
            }
        }
    }
}
