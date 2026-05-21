package com.czqwq.talkwith.gui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Mouse;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.TalkWith;
import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketSessionMessage;

public class GuiAIChat extends GuiScreen {

    /** Height of the input field. */
    private static final int INPUT_HEIGHT = 20;
    /** Vertical padding above the input field. */
    private static final int INPUT_PAD = 2;
    /** Horizontal margin. */
    private static final int MARGIN = 4;

    /**
     * Cached reflection handle for {@code FontRenderer.unicodeFlag}.
     * The field is private in GTNH's Forge build, so we must use reflection to
     * force Unicode glyph page rendering for non-ASCII text.
     */
    private static final Field UNICODE_FLAG_FIELD;

    static {
        Field f = null;
        try {
            f = FontRenderer.class.getDeclaredField("unicodeFlag");
            f.setAccessible(true);
        } catch (Exception e) {
            TalkWith.LOG.warn("Could not access FontRenderer.unicodeFlag via reflection: " + e.getMessage());
        }
        UNICODE_FLAG_FIELD = f;
    }

    private boolean getUnicodeFlag() {
        if (UNICODE_FLAG_FIELD == null) return false;
        try {
            return UNICODE_FLAG_FIELD.getBoolean(fontRendererObj);
        } catch (Exception e) {
            return false;
        }
    }

    private void setUnicodeFlag(boolean value) {
        if (UNICODE_FLAG_FIELD == null) return;
        try {
            UNICODE_FLAG_FIELD.setBoolean(fontRendererObj, value);
        } catch (Exception e) {
            // ignore – rendering will fall back to whatever the font renderer defaults to
        }
    }

    private final List<String> lines = new ArrayList<>();
    private GuiTextField inputField;
    private final String initialText;
    private boolean isThinking = false;
    private int thinkingTick = 0;
    public String currentSessionId = null;
    /**
     * Scroll offset in lines. 0 = bottom (most recent). Positive values scroll up.
     * Clamped during draw to the valid range.
     */
    private int scrollOffset = 0;

    public GuiAIChat(String initialText) {
        this.initialText = initialText;
        this.currentSessionId = ClientProxy.currentSessionId;
    }

    @Override
    public void initGui() {
        // Input field sits at the very bottom, full width minus margins
        inputField = new GuiTextField(
            fontRendererObj,
            MARGIN,
            height - INPUT_HEIGHT - INPUT_PAD,
            width - MARGIN * 2,
            INPUT_HEIGHT);
        inputField.setMaxStringLength(512);
        inputField.setFocused(true);
        // Register this as the active GUI so packet handlers can push messages into it
        ClientProxy.activeGui = this;
        if (initialText != null && !initialText.isEmpty()) {
            inputField.setText(initialText);
            sendMessage();
        }
    }

    @Override
    public void onGuiClosed() {
        if (ClientProxy.activeGui == this) {
            ClientProxy.activeGui = null;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Enable the Unicode glyph pages for this entire draw pass so that
        // Chinese and other non-ASCII characters render correctly instead of
        // appearing as squares or garbage.
        boolean savedUnicode = getUnicodeFlag();
        setUnicodeFlag(true);
        try {
            drawScreenInternal(mouseX, mouseY, partialTicks);
        } finally {
            setUnicodeFlag(savedUnicode);
        }
    }

    private void drawScreenInternal(int mouseX, int mouseY, float partialTicks) {
        int lineHeight = fontRendererObj.FONT_HEIGHT + 2;

        // Dynamically compute how many lines fit above the input field
        int inputTop = height - INPUT_HEIGHT - INPUT_PAD * 2;
        int maxVisibleLines = Math.max(1, (inputTop - 4) / lineHeight);

        // Gather the lines to display (thinking indicator counts as one line)
        List<String> display = buildDisplayLines(lineHeight);

        // Clamp scrollOffset to valid range
        int maxScroll = Math.max(0, display.size() - maxVisibleLines);
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        // Determine which slice of lines to show
        // scrollOffset 0 = show bottom maxVisibleLines; higher = scroll up
        int bottomIdx = display.size() - scrollOffset;
        int topIdx = Math.max(0, bottomIdx - maxVisibleLines);
        int visibleCount = bottomIdx - topIdx;

        // Compute the bounding box of just the visible content area
        int chatAreaHeight = visibleCount * lineHeight;

        // Y coordinate where the chat text area starts (above the input row)
        int chatAreaBottom = inputTop - 2; // small gap between chat and input
        int chatAreaTop = chatAreaBottom - chatAreaHeight;

        // Draw the entire pane: semi-transparent dark background (vanilla-like)
        if (visibleCount > 0) {
            drawRect(0, chatAreaTop - 2, width, chatAreaBottom, 0x90000000);
        }
        // Draw slightly more opaque background for input area
        drawRect(0, inputTop - 2, width, height, 0xB0000000);
        // Separator line between chat and input
        drawRect(0, inputTop - 2, width, inputTop - 1, 0xFF555555);

        // Render visible lines from bottom up
        int y = chatAreaBottom - lineHeight;
        for (int i = bottomIdx - 1; i >= topIdx; i--) {
            fontRendererObj.drawStringWithShadow(display.get(i), MARGIN, y, 0xFFFFFF);
            y -= lineHeight;
        }

        // Scroll indicator: show "▲ N more" when scrolled up
        if (scrollOffset > 0) {
            String scrollHint = StatCollector.translateToLocalFormatted("talkwith.gui.scroll_hint", scrollOffset);
            fontRendererObj.drawStringWithShadow(scrollHint, MARGIN, chatAreaTop - lineHeight - 2, 0xAAAAAA);
        }

        inputField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * Build the list of display lines, wrapping long lines and appending the
     * thinking indicator when applicable.
     */
    private List<String> buildDisplayLines(int lineHeight) {
        List<String> display = new ArrayList<>();
        for (String line : lines) {
            display.addAll(wrapLine(line, width - MARGIN * 2));
        }
        if (isThinking) {
            StringBuilder dotBuilder = new StringBuilder();
            int dotCount = (thinkingTick / 10) % 4;
            for (int d = 0; d < dotCount; d++) dotBuilder.append('.');
            display.add("§7" + StatCollector.translateToLocal("talkwith.gui.thinking") + dotBuilder);
        }
        return display;
    }

    @SuppressWarnings("unchecked")
    private List<String> wrapLine(String text, int maxWidth) {
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            List<String> single = new ArrayList<>();
            single.add(text);
            return single;
        }
        return fontRendererObj.listFormattedStringToWidth(text, maxWidth);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) { // ESC
            mc.displayGuiScreen(null);
        } else if (keyCode == 28) { // ENTER
            sendMessage();
        } else {
            inputField.textboxKeyTyped(typedChar, keyCode);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        inputField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private void sendMessage() {
        String text = inputField.getText()
            .trim();
        if (text.isEmpty()) return;

        lines.add("§e" + StatCollector.translateToLocal("talkwith.gui.you") + ": §f" + text);
        inputField.setText("");
        isThinking = true;

        if (currentSessionId != null) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionMessage(currentSessionId, text));
            // isThinking stays true until appendReply / appendError is called
        } else {
            doClientAICall(text);
        }
    }

    /**
     * Adds {@code text} to the local client session and fires an async AI request.
     * Shared implementation used by both {@link #sendMessage()} and {@link #injectAndSend(String)}.
     */
    private void doClientAICall(String text) {
        ClientProxy.clientSession.addMessage("user", text);
        AIClient.sendAsync(
            ClientProxy.clientSession.getMessages(Config.loadPromptFromFile(Config.clientPromptFile)),
            reply -> ClientProxy.scheduleOnMainThread(() -> {
                ClientProxy.clientSession.addMessage("assistant", reply);
                lines.add(StatCollector.translateToLocal("talkwith.chat.ai_prefix") + reply);
                isThinking = false;
                scrollToBottom();
            }),
            err -> ClientProxy.scheduleOnMainThread(() -> {
                lines.add(StatCollector.translateToLocal("talkwith.chat.error_prefix") + err);
                isThinking = false;
            }));
    }

    /**
     * Called by {@link com.czqwq.talkwith.network.PacketSessionBroadcast.Handler} when the server
     * broadcasts a completed AI reply for this session. Displays the exchange and clears the thinking
     * indicator.
     */
    public void appendReply(String playerName, String playerMsg, String aiReply) {
        lines.add("§e[" + playerName + "]: §f" + playerMsg);
        lines.add(StatCollector.translateToLocal("talkwith.chat.ai_prefix") + aiReply);
        isThinking = false;
        scrollToBottom();
    }

    /**
     * Called by {@link com.czqwq.talkwith.network.PacketSessionBroadcast.Handler} when the server
     * broadcasts an AI error for this session. Displays the error and clears the thinking indicator.
     */
    public void appendError(String errorMsg) {
        lines.add(StatCollector.translateToLocal("talkwith.chat.error_prefix") + errorMsg);
        isThinking = false;
    }

    /**
     * Injects a user message and fires an async AI request. Used when the server routes a
     * {@code >} command back to the client for local AI processing (single mode via chat).
     */
    public void injectAndSend(String text) {
        lines.add("§e" + StatCollector.translateToLocal("talkwith.gui.you") + ": §f" + text);
        isThinking = true;
        doClientAICall(text);
    }

    /** Resets the scroll offset so the most recent lines are visible. */
    private void scrollToBottom() {
        scrollOffset = 0;
    }

    @Override
    public void updateScreen() {
        thinkingTick++;
        inputField.updateCursorCounter();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            // Scroll up (wheel > 0) increases offset (shows older lines)
            scrollOffset += wheel > 0 ? 1 : -1;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
