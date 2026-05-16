package com.czqwq.talkwith.util;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

public class TextUtils {

    public static final String PREFIX = "§a[TalkWith]§r ";
    public static final String PREFIX_ERROR = "§c[TalkWith]§r ";

    /**
     * Approximate visual-width limit for a single chat line.
     * Minecraft's default chat box is ~320 "GUI pixels" wide;
     * ASCII chars average ~6px, so 53 ASCII units ≈ 318px.
     * CJK/full-width chars are double-width and count as 2 units here.
     */
    private static final int MAX_VISUAL_WIDTH = 53;

    /** First printable ASCII character — used to filter raw control characters from AI output. */
    private static final char MIN_PRINTABLE_CHAR = 0x20;

    public static void addChatMessage(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(msg));
        }
    }

    /**
     * Computes the approximate visual width of a string for Minecraft's chat box.
     * <ul>
     * <li>§-format codes (two-char sequences) count as 0 width.</li>
     * <li>CJK Unified Ideographs, Hangul, fullwidth forms, etc. count as 2 units.</li>
     * <li>All other characters count as 1 unit.</li>
     * </ul>
     * One "unit" ≈ 6 GUI pixels (the average ASCII glyph width).
     */
    static int visualWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++; // skip the format-code character
                continue;
            }
            // CJK Unified Ideographs, Hangul, fullwidth/wide Latin, etc.
            w += isWideChar(c) ? 2 : 1;
        }
        return w;
    }

    private static boolean isWideChar(char c) {
        return (c >= 0x1100 && c <= 0x115F) // Hangul Jamo
            || (c >= 0x2E80 && c <= 0x303F) // CJK Radicals / Kangxi
            || (c >= 0x3040 && c <= 0x33FF) // Japanese kana + misc CJK
            || (c >= 0x3400 && c <= 0x4DBF) // CJK Extension A
            || (c >= 0x4E00 && c <= 0x9FFF) // CJK Unified Ideographs
            || (c >= 0xA000 && c <= 0xA4CF) // Yi
            || (c >= 0xAC00 && c <= 0xD7AF) // Hangul Syllables
            || (c >= 0xF900 && c <= 0xFAFF) // CJK Compatibility Ideographs
            || (c >= 0xFE10 && c <= 0xFE1F) // Vertical forms
            || (c >= 0xFE30 && c <= 0xFE4F) // CJK Compatibility Forms
            || (c >= 0xFF00 && c <= 0xFF60) // Fullwidth Latin / Halfwidth Katakana
            || (c >= 0xFFE0 && c <= 0xFFE6); // Fullwidth signs
    }

    /**
     * Sends an informational message to chat.
     * If the formatted line is wider than the chat box (e.g. a Chinese status line
     * that uses double-width CJK glyphs), the message is split at " | " separators
     * so each piece fits on its own line without Minecraft having to wrap mid-§code.
     */
    public static void info(String msg) {
        String full = PREFIX + msg;
        if (visualWidth(full) <= MAX_VISUAL_WIDTH) {
            addChatMessage(full);
            return;
        }
        // Split at pipe separators (status messages use " | " as field separators)
        String[] parts = msg.split(" \\| ");
        if (parts.length <= 1) {
            addChatMessage(full); // no separators found — send as-is
            return;
        }
        for (String part : parts) {
            addChatMessage(PREFIX + part);
        }
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

            // Chunk excessively long lines using visual width so CJK double-width
            // characters are counted correctly (each CJK char = 2 width units).
            while (visualWidth(line) > MAX_VISUAL_WIDTH) {
                int cut = findVisualCut(line, MAX_VISUAL_WIDTH);
                // Prefer to break at a space so we don't cut mid-word
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

    /**
     * Returns the character index at which the cumulative visual width first
     * reaches (or would exceed) {@code maxWidth}.
     */
    private static int findVisualCut(String s, int maxWidth) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++;
                continue;
            }
            w += isWideChar(c) ? 2 : 1;
            if (w > maxWidth) return i;
        }
        return s.length();
    }
}
