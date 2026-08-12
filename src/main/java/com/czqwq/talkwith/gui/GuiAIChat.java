package com.czqwq.talkwith.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IGuiAction;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.TalkWith;
import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.client.SessionClient;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketSessionMessage;
import com.czqwq.talkwith.util.TextUtils;

public class GuiAIChat extends CustomModularScreen {

    /** Timeout for the "AI is thinking" indicator when no reply arrives. */
    private static final long THINKING_TIMEOUT_MS = 60_000L;

    final List<String> lines = new ArrayList<>();
    boolean isThinking;
    int thinkingTick;
    long thinkingSince;
    int scrollOffset;

    public GuiAIChat() {
        super(TalkWith.MODID);
        this.lines.addAll(ClientProxy.chatHistory);
    }

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        int W = Math.max(300, getScreenWidth() - 20);
        int H = Math.max(200, getScreenHeight() - 30);
        int CHAT_W = W - 14;
        int CHAT_H = H - 100;

        ModularPanel panel = ModularPanel.defaultPanel("ai_chat", W, H);
        // disableHoverThemeBackground: otherwise the theme's opaque hover background
        // replaces this panel background when the mouse is over the GUI.
        panel.background(new Rectangle().color(0xCC16213e, 0xCC16213e, 0xCC1a1a2e, 0xCC1a1a2e))
            .disableHoverThemeBackground(true);
        panel.padding(6);
        ClientProxy.activeGui = this;

        // Title bar — dynamic so it picks up the session id (set after construction).
        TextWidget<?> titleBg = new TextWidget<>("");
        titleBg.pos(0, 0)
            .size(W, 14);
        titleBg.background(new Rectangle().color(0x881a1a2e))
            .disableHoverThemeBackground(true);
        panel.child(titleBg);
        TextWidget<?> title = new TextWidget<>(IKey.dynamic(this::getTitleText));
        title.left(6)
            .top(3)
            .color(0xFFf0a500);
        panel.child(title);

        // Chat area
        ChatAreaWidget chat = new ChatAreaWidget();
        chat.pos(0, 16)
            .size(CHAT_W, CHAT_H);
        panel.child(chat);

        // Input row
        StringValue inputValue = new StringValue("");
        TextFieldWidget input = new TextFieldWidget();
        input.value(inputValue);
        // Without this, the typed text only lands in inputValue when the field loses
        // focus (TextFieldWidget.onRemoveFocus). The Enter listener below reads
        // inputValue BEFORE the key reaches the field, so it would always see the
        // stale value and never send. autoUpdateOnChange keeps the value live.
        input.autoUpdateOnChange(true);
        input.pos(0, H - 30)
            .size(CHAT_W - 56, 20);
        input.setFocusOnGuiOpen(true);
        panel.child(input);
        ButtonWidget<?> sendBtn = new ButtonWidget<>();
        sendBtn.child(new TextWidget<>(StatCollector.translateToLocal("talkwith.gui.send")).center());
        sendBtn.right(6)
            .top(H - 30)
            .size(50, 20);
        sendBtn.onMouseTapped(btn -> {
            if (sendMessage(inputValue.getStringValue())) inputValue.setValue("");
            return true;
        });
        panel.child(sendBtn);

        // Enter-to-send: gui action listeners run before widget key handling.
        panel.listenGuiAction((IGuiAction.KeyPressed) (typedChar, keyCode) -> {
            if ((keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) && input.isFocused()) {
                if (sendMessage(inputValue.getStringValue())) {
                    // Clear BOTH the binding and the field's internal text. The Enter key
                    // still propagates to the focused field right after this listener, whose
                    // RETURN handler calls removeFocus() — and onRemoveFocus() commits the
                    // field's text back into inputValue. Clearing the text first makes that
                    // commit write "" instead of the just-sent message, so the field empties.
                    inputValue.setValue("");
                    input.setText("");
                    // Re-focus on the next tick so the player can keep typing.
                    ClientProxy.scheduleOnMainThread(() -> context.focus(input));
                }
                return true;
            }
            return false;
        });

        // Top buttons — text labels instead of emoji glyphs (unrenderable without Angelica).
        int btnY = H - 52;
        addTopButton(
            panel,
            0,
            btnY,
            50,
            IKey.lang("talkwith.gui.btn.history"),
            () -> GuiSubPanels.openHistoryPanel(panel, this));
        addTopButton(
            panel,
            52,
            btnY,
            52,
            IKey.lang("talkwith.gui.btn.settings"),
            () -> GuiSubPanels.openSettingsPanel(panel, this));
        addTopButton(
            panel,
            106,
            btnY,
            46,
            IKey.dynamicKey(
                () -> IKey.lang(ClientProxy.isSingleOverride ? "talkwith.gui.btn.single" : "talkwith.gui.btn.multi")),
            () -> {
                if (ClientProxy.currentSessionId == null) {
                    TextUtils.error(StatCollector.translateToLocal("talkwith.switch.not_in_session"));
                    return;
                }
                SessionClient.toggleSingleOverride();
            });

        return panel;
    }

    private void addTopButton(ModularPanel panel, int x, int y, int w, IKey label, Runnable action) {
        ButtonWidget<?> btn = new ButtonWidget<>();
        btn.child(new TextWidget<>(label).center());
        btn.pos(x, y)
            .size(w, 18);
        btn.onMouseTapped(b -> {
            action.run();
            return true;
        });
        panel.child(btn);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        thinkingTick++;
    }

    private String getTitleText() {
        String id = ClientProxy.currentSessionId;
        return id != null ? (id.length() > 8 ? id.substring(0, 8) + ".." : id)
            : StatCollector.translateToLocal("talkwith.gui.ai_chat");
    }

    /** @return {@code true} when the message was actually sent. */
    private boolean sendMessage(String text) {
        if (text == null || text.trim()
            .isEmpty()) return false;
        addLine("§e" + StatCollector.translateToLocal("talkwith.gui.you") + ": §f" + text.trim());
        isThinking = true;
        thinkingSince = System.currentTimeMillis();
        scrollOffset = 0;
        if (ClientProxy.currentSessionId != null && !ClientProxy.isSingleOverride) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionMessage(ClientProxy.currentSessionId, text.trim()));
        } else {
            doClientAICall(text.trim());
        }
        return true;
    }

    private void doClientAICall(String text) {
        ClientProxy.clientSession.addMessage("user", text);
        AIClient.sendAsync(
            ClientProxy.clientSession.getMessages(SessionClient.resolvePromptText()),
            reply -> ClientProxy.scheduleOnMainThread(() -> {
                ClientProxy.clientSession.addMessage("assistant", reply);
                for (String l : TextUtils
                    .buildAIReplyLines(StatCollector.translateToLocal("talkwith.chat.ai_prefix"), reply)) addLine(l);
                isThinking = false;
            }),
            err -> ClientProxy.scheduleOnMainThread(() -> {
                addLine(StatCollector.translateToLocal("talkwith.chat.error_prefix") + err);
                isThinking = false;
            }));
    }

    public void appendReply(String playerName, String playerMsg, String aiReply) {
        syncLines();
        Minecraft mc = Minecraft.getMinecraft();
        if (playerName != null && mc.thePlayer != null && playerName.equals(mc.thePlayer.getCommandSenderName())) {
            isThinking = false;
        }
    }

    public void appendError(String errorMsg) {
        syncLines();
        isThinking = false;
    }

    public void injectAndSend(String text) {
        addLine("§e" + StatCollector.translateToLocal("talkwith.gui.you") + ": §f" + text);
        isThinking = true;
        thinkingSince = System.currentTimeMillis();
        scrollOffset = 0;
        doClientAICall(text);
    }

    public void addLine(String line) {
        ClientProxy.addToChatHistory(line);
        lines.add(line);
    }

    /** Clears the GUI-side line cache (the shared history is cleared by the caller). */
    public void clearLines() {
        lines.clear();
    }

    public void syncLines() {
        // Compare content, not just size: equal sizes can still diverge if history was
        // replaced (e.g. historyOnly replay) while the GUI stayed open.
        List<String> hist = ClientProxy.chatHistory;
        if (lines.size() != hist.size() || !lines.equals(hist)) {
            lines.clear();
            lines.addAll(hist);
        }
    }

    @Override
    public void onClose() {
        super.onClose();
        if (ClientProxy.activeGui == this) ClientProxy.activeGui = null;
    }

    private static int getScreenWidth() {
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaledWidth();
    }

    private static int getScreenHeight() {
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaledHeight();
    }

    private class ChatAreaWidget extends Widget<ChatAreaWidget> implements Interactable {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            // Clip to the widget area so long/unwrapped lines cannot overflow over the
            // input row, the top buttons, or off-screen.
            Stencil.applyAtZero(getArea(), context);
            try {
                int lh = Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT + 2;
                List<String> d = getDisplay();
                int maxVisible = Math.max(1, (getArea().height - 4) / lh);
                int maxOffset = Math.max(0, d.size() - maxVisible);
                if (scrollOffset > maxOffset) scrollOffset = maxOffset;
                if (scrollOffset < 0) scrollOffset = 0;
                int end = d.size() - 1 - scrollOffset;
                int y = getArea().height - 4;
                for (int i = end; i >= 0 && y > 0; i--) {
                    String l = d.get(i);
                    int c = 0xFFCCC8B8;
                    if (l.startsWith("§b") || l.startsWith("§f§b")) c = 0xFF88ccff;
                    else if (l.startsWith("§e") || l.startsWith("§f§e")) c = 0xFFf0a500;
                    else if (l.startsWith("§c")) c = 0xFFe94560;
                    y -= lh;
                    Minecraft.getMinecraft().fontRenderer.drawString(l, 2, y, c, false);
                }
                if (scrollOffset > 0) {
                    String hint = StatCollector.translateToLocalFormatted("talkwith.gui.scroll_hint", scrollOffset);
                    Minecraft.getMinecraft().fontRenderer.drawString(hint, 2, 0, 0xFFf0a500, false);
                }
            } finally {
                Stencil.remove();
            }
        }

        @Override
        public boolean onMouseScroll(UpOrDown scrollDirection, int amount) {
            scrollOffset += scrollDirection.isUp() ? -amount : amount;
            return true;
        }

        private List<String> getDisplay() {
            List<String> d = new ArrayList<>(lines);
            if (isThinking) {
                if (System.currentTimeMillis() - thinkingSince > THINKING_TIMEOUT_MS) {
                    isThinking = false;
                } else {
                    StringBuilder dots = new StringBuilder();
                    for (int d2 = 0; d2 < (thinkingTick / 10) % 4; d2++) dots.append('.');
                    d.add("§7" + StatCollector.translateToLocal("talkwith.gui.thinking") + dots);
                }
            }
            return d;
        }
    }
}
