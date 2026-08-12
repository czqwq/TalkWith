package com.czqwq.talkwith.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IFocusedWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.PagedWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.client.SessionClient;
import com.czqwq.talkwith.network.PacketPromptData;
import com.czqwq.talkwith.prompt.PromptStore;

public class GuiSubPanels {

    /**
     * One handler per parent panel — {@code PanelManager} keeps a single panel per name, so
     * creating a fresh handler on every open just logs an error and drops the new panel.
     */
    private static final Map<ModularPanel, IPanelHandler> SETTINGS_HANDLERS = new WeakHashMap<>();
    private static final Map<ModularPanel, IPanelHandler> HISTORY_HANDLERS = new WeakHashMap<>();

    /**
     * Editable state backing the settings panel. Widgets bind to these values; the whole
     * state is re-seeded from {@link Config} (and, in multi mode, from the server session)
     * on every open. Kept per parent panel so a cached panel never shows stale values.
     */
    static final class SettingsState {

        final StringValue model = new StringValue("");
        final StringValue baseUrl = new StringValue("");
        final StringValue apiKey = new StringValue("");
        /** 加密密钥（口令）—— 见 util/KeyCipher；输入后用于加密 API key 落盘。 */
        final StringValue passphrase = new StringValue("");
        String activePrompt = "";
        /** Prompt page state — file name being edited and the multiline content editor. */
        final StringValue promptFileName = new StringValue("system_prompt.json");
        PromptEditor editor;
        List<String> promptList = new ArrayList<>();
    }

    private static final Map<ModularPanel, SettingsState> SETTINGS_STATE = new WeakHashMap<>();

    /**
     * Placeholder shown in the API key field when the stored key is encrypted, so the
     * plaintext is never displayed. The Apply handler treats an untouched field that still
     * contains this exact string as "user did not change the key" and skips re-applying it.
     */
    private static final String API_KEY_MASK = "••••••••••";
    /**
     * The settings state backing whatever settings UI is currently visible (sub-panel on
     * {@link GuiAIChat} or the standalone {@link GuiAISettings} screen). Async replies
     * ({@link #onSessionSettingsReceived}, {@link #onPromptData}) update this state so the
     * visible screen reflects server values — the standalone screen is not in
     * {@link #SETTINGS_STATE}, so it would otherwise miss updates.
     */
    private static SettingsState currentSettingsState;

    // --- Settings sub-panel ---

    public static void openSettingsPanel(ModularPanel parent, GuiAIChat chatGui) {
        SettingsState st = SETTINGS_STATE.get(parent);
        if (st == null) {
            st = new SettingsState();
            SETTINGS_STATE.put(parent, st);
        }
        seedSettingsState(st);

        IPanelHandler handler = SETTINGS_HANDLERS.get(parent);
        if (handler == null) {
            final SettingsState state = st;
            handler = IPanelHandler.simple(parent, (pp, pl) -> buildSettingsPanel(state), true);
            SETTINGS_HANDLERS.put(parent, handler);
        }
        handler.openPanel();
    }

    /**
     * Refreshes the settings state from {@link Config} on every open. In multi mode this is
     * the local fallback; the real session values arrive asynchronously via
     * {@link #onSessionSettingsReceived()} after a {@code setting_get} request. The prompt
     * list is refreshed here (not just at panel build) so a cached panel never shows stale
     * files when it is re-opened.
     */
    static void seedSettingsState(SettingsState st) {
        st.model.setStringValue(Config.model);
        st.baseUrl.setStringValue(Config.baseUrl);
        // Never show the decrypted plaintext when the stored key is encrypted — seed a
        // masked placeholder instead. The real key is only re-applied if the user edits
        // the field (see the Apply handler). Plaintext legacy keys still show as-is.
        st.apiKey.setStringValue(Config.isStoredApiKeyEncrypted() ? API_KEY_MASK : Config.apiKey);
        st.passphrase.setStringValue(Config.apiKeyPass);
        st.activePrompt = Config.clientPromptFile;
        refreshPromptList(st);
        // Re-open path: the cached panel already has an editor, so reload the active prompt
        // into it every time the settings screen opens (buildPromptPage covers first open).
        loadActivePrompt(st);
        if (SessionClient.isMultiMode()) {
            SessionClient.requestSessionSettings();
        }
    }

    /**
     * Called on the client thread when a {@code setting_get} reply arrives. Updates the
     * open settings panel with the server session's real values so a stale Apply cannot
     * silently revert config that was changed elsewhere.
     */
    public static void onSessionSettingsReceived() {
        ClientProxy.SessionSettings ss = ClientProxy.sessionSettings;
        for (SettingsState st : visibleSettingsStates()) {
            st.model.setStringValue(ss.model);
            st.baseUrl.setStringValue(ss.baseUrl);
            if (ss.promptFile != null && !ss.promptFile.isEmpty()) {
                st.activePrompt = ss.promptFile;
            }
            // The API key is intentionally NOT auto-filled: the server never sends it back.
        }
    }

    /** Called on the client thread when a prompt management reply arrives (multi mode). */
    public static void onPromptData(PacketPromptData pkt) {
        for (SettingsState st : visibleSettingsStates()) {
            if (pkt.isList) {
                st.promptList = new ArrayList<>(pkt.names);
            } else {
                st.activePrompt = pkt.name;
                if (st.editor != null) st.editor.text(pkt.content);
            }
        }
    }

    /** Returns the active visible state plus all cached sub-panel states (deduplicated). */
    private static List<SettingsState> visibleSettingsStates() {
        List<SettingsState> all = new ArrayList<>();
        if (currentSettingsState != null) all.add(currentSettingsState);
        for (SettingsState st : SETTINGS_STATE.values()) {
            if (!all.contains(st)) all.add(st);
        }
        return all;
    }

    public static ModularPanel buildSettingsPanel(SettingsState st) {
        return buildSettingsPanel(null, st);
    }

    public static ModularPanel buildSettingsPanel(IPanelHandler backHandler, SettingsState st) {
        currentSettingsState = st;
        int W = Math.max(320, screenW() - 80);
        int H = Math.max(200, screenH() - 80);
        int SW = Math.min(100, W / 4);
        int cx = SW + 10, cw = W - cx - 8;

        // Dialog is non-draggable by default — without this, clicking any button
        // starts a panel drag (ModularGuiContext.onHoveredClick) that swallows the
        // press and the release, so onMouseTapped never fires (buttons appear dead).
        // disableHoverThemeBackground: the panel background override would otherwise
        // be replaced by the theme's opaque hover background when the mouse is over it.
        // Fully opaque (0xFF) so the world/chat behind the sub-panel is hidden.
        ModularPanel panel = new Dialog<>("settings").setDisablePanelsBelow(false)
            .size(W, H);
        panel.background(new Rectangle().color(0xFF16213e, 0xFF16213e, 0xFF1a1a2e, 0xFF1a1a2e))
            .disableHoverThemeBackground(true);
        panel.padding(6);

        // Title
        TextWidget<?> tb = new TextWidget<>("");
        tb.pos(0, 0)
            .size(W, 14);
        tb.background(new Rectangle().color(0x881a1a2e))
            .disableHoverThemeBackground(true);
        panel.child(tb);
        TextWidget<?> ti = new TextWidget<>(IKey.lang("talkwith.settings.title"));
        ti.left(6)
            .top(3)
            .color(0xFFf0a500);
        panel.child(ti);

        // Sidebar
        TextWidget<?> sb = new TextWidget<>("");
        sb.pos(0, 16)
            .size(SW + 6, H - 16);
        sb.background(new Rectangle().color(0x33111122))
            .disableHoverThemeBackground(true);
        panel.child(sb);

        // PagedWidget for LLM / Prompt tabs. Height keeps the content above the back button
        // (bottom at H-26): pager ends at 20 + (H-46) = H-26.
        PagedWidget<?> pager = new PagedWidget<>();
        int pagerH = H - 46;
        pager.pos(cx, 20)
            .size(cw, pagerH);
        pager.addPage(buildLLMPage(cw, pagerH, st));
        pager.addPage(buildPromptPage(cw, pagerH, st));
        panel.child(pager);

        // Tab buttons
        ButtonWidget<?> llmBtn = new ButtonWidget<>();
        llmBtn.child(new TextWidget<>(IKey.lang("talkwith.settings.tab.llm")).center());
        llmBtn.pos(2, 20)
            .size(SW + 2, 22);
        llmBtn.onMouseTapped(btn -> {
            pager.setPage(0);
            return true;
        });
        panel.child(llmBtn);

        ButtonWidget<?> promptBtn = new ButtonWidget<>();
        promptBtn.child(new TextWidget<>(IKey.lang("talkwith.settings.tab.prompt")).center());
        promptBtn.pos(2, 44)
            .size(SW + 2, 22);
        promptBtn.onMouseTapped(btn -> {
            pager.setPage(1);
            return true;
        });
        panel.child(promptBtn);

        // Divider
        TextWidget<?> sep = new TextWidget<>("");
        sep.pos(SW + 6, 18)
            .size(1, H - 24);
        sep.background(new Rectangle().color(0x33f0a500))
            .disableHoverThemeBackground(true);
        panel.child(sep);

        // Back button
        ButtonWidget<?> back = new ButtonWidget<>();
        back.child(new TextWidget<>(IKey.lang("talkwith.gui.back")).center());
        back.right(6)
            .bottom(6)
            .size(80, 20);
        back.onMouseTapped(btn -> {
            if (backHandler != null) backHandler.closePanel();
            else panel.closeIfOpen();
            return true;
        });
        panel.child(back);

        return panel;
    }

    private static Flow buildLLMPage(int cw, int pagerH, SettingsState st) {
        return Flow.column()
            .width(cw)
            .height(pagerH) // bounded to the pager so it can never overlap the back button
            .childPadding(2)
            .child(new TextWidget<>(IKey.lang("talkwith.settings.llm.model")).color(0xFFf0a500))
            .child(
                new TextFieldWidget().value(st.model)
                    .width(cw)
                    .height(16))
            .child(
                new TextWidget<>(IKey.str(StatCollector.translateToLocal("talkwith.settings.api_url_label")))
                    .color(0xFFf0a500))
            .child(
                new TextFieldWidget().value(st.baseUrl)
                    .width(cw)
                    .height(16))
            .child(new TextWidget<>(IKey.lang("talkwith.settings.api_key")).color(0xFFf0a500))
            .child(
                new TextFieldWidget().value(st.apiKey)
                    .width(cw)
                    .height(16))
            .child(new TextWidget<>(IKey.lang("talkwith.settings.api_pass")).color(0xFFf0a500))
            .child(
                new TextFieldWidget().value(st.passphrase)
                    .width(cw)
                    .height(16))
            // Red warning when the stored encrypted key could not be decrypted (wrong passphrase).
            .child(
                new TextWidget<>(
                    IKey.dynamic(
                        () -> Config.apiKeyDecryptFailed
                            ? StatCollector.translateToLocal("talkwith.settings.api_key_decrypt_failed")
                            : "")).color(0xFFe94560))
            // Hint: multi-mode (server never sends the key back) or encrypted single-mode
            // (field is masked; type a new value to replace it, leave empty to clear).
            .child(new TextWidget<>(IKey.dynamic(() -> {
                if (SessionClient.isMultiMode()) {
                    return ClientProxy.sessionSettings.hasApiKey
                        ? StatCollector.translateToLocal("talkwith.settings.session_key_configured")
                        : StatCollector.translateToLocal("talkwith.settings.session_key_missing");
                }
                if (Config.isStoredApiKeyEncrypted()) {
                    return StatCollector.translateToLocal("talkwith.settings.api_key_encrypted_hint");
                }
                return "";
            })).color(0xFF88ccff))
            .child(
                new ButtonWidget<>().child(new TextWidget<>(IKey.lang("talkwith.settings.apply")).center())
                    .size(80, 20)
                    .onMouseTapped(btn -> {
                        String m = st.model.getStringValue(), u = st.baseUrl.getStringValue(),
                            k = st.apiKey.getStringValue(), p = st.passphrase.getStringValue();
                        if (!m.isEmpty()) SessionClient.applyModel(m);
                        if (!u.isEmpty()) SessionClient.applyBaseUrl(u);
                        // An untouched masked key means "don't change it" — re-applying the
                        // mask itself would overwrite the real key. An empty field still
                        // clears the key. In multi mode this also avoids sending the mask.
                        if (!API_KEY_MASK.equals(k)) {
                            SessionClient.applyApiKey(k);
                        }
                        SessionClient.applyPassphrase(p);
                        return true;
                    }));
    }

    /**
     * Builds the prompt page. Uses explicit absolute positions (like {@link GuiAIChat}'s layout)
     * instead of Flow auto-layout: the Flow container is only a fixed-size holder, and every
     * child is placed at an exact rect that sums to {@code pagerH} — so the list, name input,
     * button row and editor can never overlap or spill past the pager into the back button.
     */
    private static Flow buildPromptPage(int cw, int pagerH, SettingsState st) {
        // The prompt list is refreshed by seedSettingsState() on every open, so no refresh here.

        int pad = 4;
        int gap = 4;
        int listH = 44;
        int nameH = 16;
        int btnH = 18;
        int labelH = 9;
        int x = pad, w = cw - pad * 2;
        int editorH = pagerH - (listH + gap + nameH + gap + btnH + gap + labelH + gap);

        // Scrollable list of prompt files in the active scope (session or local).
        PromptListWidget list = new PromptListWidget(st, name -> loadPrompt(st, name));
        list.background(new Rectangle().color(0x88111122))
            .disableHoverThemeBackground(true);
        list.pos(x, 0)
            .size(w, listH);

        // File name text input (bare name within the scope directory).
        TextFieldWidget nameInput = new TextFieldWidget();
        nameInput.value(st.promptFileName)
            .pos(x, listH + gap)
            .size(w, nameH);

        // Actions row — four equal buttons across the width.
        int yBtn = listH + gap * 2 + nameH;
        int bw = (w - gap * 3) / 4;
        ButtonWidget<?> loadBtn = promptButton(
            "talkwith.prompt.load",
            () -> loadPrompt(st, st.promptFileName.getStringValue()));
        loadBtn.pos(x, yBtn)
            .size(bw, btnH);
        ButtonWidget<?> saveBtn = promptButton("talkwith.prompt.save", () -> savePrompt(st));
        saveBtn.pos(x + bw + gap, yBtn)
            .size(bw, btnH);
        ButtonWidget<?> newBtn = promptButton("talkwith.prompt.new", () -> newPrompt(st));
        newBtn.pos(x + (bw + gap) * 2, yBtn)
            .size(bw, btnH);
        ButtonWidget<?> selectBtn = promptButton(
            "talkwith.prompt.select",
            () -> selectPrompt(st, st.promptFileName.getStringValue()));
        selectBtn.pos(x + (bw + gap) * 3, yBtn)
            .size(bw, btnH);

        // Editor label + multiline content editor.
        int yLabel = yBtn + btnH + gap;
        TextWidget<?> label = new TextWidget<>(IKey.lang("talkwith.prompt.editor"));
        label.pos(x, yLabel)
            .size(w, labelH)
            .color(0xFFf0a500);

        PromptEditor editor = new PromptEditor();
        st.editor = editor;
        editor.background(new Rectangle().color(0x88111122))
            .disableHoverThemeBackground(true);
        editor.pos(x, yLabel + labelH + gap)
            .size(w, editorH);
        // Auto-load the currently active prompt on first build (re-opens are handled by
        // seedSettingsState() → loadActivePrompt()).
        loadActivePrompt(st);

        // Flow is a fixed-size holder; every child above has an explicit pos/size so the
        // Flow does not re-layout them (children with a fixed position are skipped).
        return Flow.column()
            .width(cw)
            .height(pagerH)
            .child(list)
            .child(nameInput)
            .child(loadBtn)
            .child(saveBtn)
            .child(newBtn)
            .child(selectBtn)
            .child(label)
            .child(editor);
    }

    /** Loads the currently active prompt into the editor (local immediately, multi via packet). */
    private static void loadActivePrompt(SettingsState st) {
        if (st.editor == null) return;
        String name = st.activePrompt;
        if (name == null || name.isEmpty()) return;
        st.promptFileName.setStringValue(name);
        if (SessionClient.isMultiMode()) {
            SessionClient.readPrompt(name);
        } else {
            st.editor.text(PromptStore.readLocal(name));
        }
    }

    private static ButtonWidget<?> promptButton(String langKey, Runnable action) {
        // .center() centers the label text inside the button; without it the TextWidget
        // child renders at the button's top-left corner.
        return new ButtonWidget<>().child(new TextWidget<>(IKey.lang(langKey)).center())
            .onMouseTapped(b -> {
                action.run();
                return true;
            });
    }

    /** Refreshes the prompt file list for the active scope (session async, local sync). */
    private static void refreshPromptList(SettingsState st) {
        if (SessionClient.isMultiMode()) {
            SessionClient.requestPromptList();
        } else {
            st.promptList = new ArrayList<>(PromptStore.listLocal());
        }
    }

    private static void loadPrompt(SettingsState st, String name) {
        String filename = Config.sanitizePromptFilename(name);
        if (filename.isEmpty()) return;
        st.promptFileName.setStringValue(filename);
        if (SessionClient.isMultiMode()) {
            SessionClient.readPrompt(filename);
        } else {
            st.activePrompt = filename;
            if (st.editor != null) st.editor.text(PromptStore.readLocal(filename));
        }
    }

    private static void savePrompt(SettingsState st) {
        String name = st.promptFileName.getStringValue();
        if (Config.sanitizePromptFilename(name)
            .isEmpty()) return;
        String content = st.editor != null ? st.editor.text() : "";
        if (SessionClient.isMultiMode()) {
            SessionClient.writePrompt(name, content);
        } else {
            String filename = Config.sanitizePromptFilename(name);
            PromptStore.writeLocal(filename, content);
            st.activePrompt = filename;
            st.promptList = new ArrayList<>(PromptStore.listLocal());
        }
    }

    private static void newPrompt(SettingsState st) {
        st.promptFileName.setStringValue("");
        if (st.editor != null) st.editor.text("");
    }

    private static void selectPrompt(SettingsState st, String name) {
        String filename = Config.sanitizePromptFilename(name);
        if (filename.isEmpty()) return;
        SessionClient.applyPromptFile(filename);
        st.activePrompt = filename;
    }

    /**
     * Custom multiline prompt editor. The library's {@code TextEditorWidget} is NOT used:
     * its multi-line handling is broken (two render/click crashes inside
     * {@code TextFieldRenderer} when the horizontal scrollbar is active) and it has no
     * vertical scrolling, so long prompts cannot be fully viewed. This editor wraps long
     * lines, scrolls vertically with a visible scrollbar, and handles click / typing /
     * arrows / selection / copy-paste itself.
     */
    private static class PromptEditor extends Widget<PromptEditor> implements IFocusedWidget, Interactable {

        private static final int PAD_L = 4;
        private static final int PAD_R = 9; // reserve a gutter for the scrollbar
        private static final int SCROLL_STEP = 14;

        private final List<String> lines = new ArrayList<>();
        private int caretLine;
        private int caretCol;
        private int selLine = -1, selCol = -1; // selection anchor; -1 = no selection
        private boolean focused;
        private int scrollY;
        private int cursorTick;

        PromptEditor() {
            lines.add("");
        }

        /** Full multi-line content (lines joined by {@code '\n'}). */
        String text() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(lines.get(i));
            }
            return sb.toString();
        }

        /** Replaces the whole content, preserving trailing newlines. */
        void text(String s) {
            lines.clear();
            String v = s != null ? s : "";
            if (v.isEmpty()) {
                lines.add("");
            } else {
                for (String ln : v.split("\n", -1)) {
                    lines.add(ln);
                }
            }
            caretLine = 0;
            caretCol = 0;
            selLine = -1;
            scrollY = 0;
        }

        private static net.minecraft.client.gui.FontRenderer font() {
            return Minecraft.getMinecraft().fontRenderer;
        }

        private static int lineHeight() {
            return font().FONT_HEIGHT + 2;
        }

        private int innerWidth() {
            return Math.max(1, getArea().width - PAD_L - PAD_R);
        }

        /** Visual rows: each {@code int[] {lineIndex, startCol, endCol}} — one per wrapped segment / empty line. */
        private List<int[]> buildRows() {
            List<int[]> rows = new ArrayList<>();
            for (int li = 0; li < lines.size(); li++) {
                String s = lines.get(li);
                if (s.isEmpty()) {
                    rows.add(new int[] { li, 0, 0 });
                    continue;
                }
                int start = 0;
                while (start < s.length()) {
                    String seg = font().trimStringToWidth(s.substring(start), innerWidth());
                    if (seg.isEmpty()) {
                        start++;
                        continue;
                    }
                    rows.add(new int[] { li, start, start + seg.length() });
                    start += seg.length();
                }
            }
            return rows;
        }

        private int visualRowOfCaret(List<int[]> rows) {
            for (int i = 0; i < rows.size(); i++) {
                int[] r = rows.get(i);
                if (r[0] != caretLine) continue;
                if (caretCol >= r[1] && caretCol < r[2]) return i;
                if (caretCol == r[2]) {
                    // caret sits on a wrap boundary → belongs to the start of the next segment
                    if (i + 1 < rows.size() && rows.get(i + 1)[0] == caretLine) return i + 1;
                    return i;
                }
                if (caretCol < r[1]) return i;
            }
            return Math.max(0, rows.size() - 1);
        }

        private boolean hasSelection() {
            return selLine >= 0 && (selLine != caretLine || selCol != caretCol);
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            cursorTick++;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            Stencil.applyAtZero(getArea(), context);
            try {
                List<int[]> rows = buildRows();
                int maxScroll = Math.max(0, rows.size() * lineHeight() - (getArea().height - PAD_L));
                if (scrollY > maxScroll) scrollY = maxScroll;
                if (scrollY < 0) scrollY = 0;

                int y = PAD_L - scrollY;
                for (int[] r : rows) {
                    if (y + lineHeight() < PAD_L) {
                        y += lineHeight();
                        continue;
                    }
                    if (y > getArea().height) break;
                    String line = lines.get(r[0]);
                    if (hasSelection()) drawSelectionRow(r, y);
                    font().drawString(line.substring(r[1], r[2]), PAD_L, y, 0xFFCCC8B8, false);
                    y += lineHeight();
                }

                // Caret (blinks while focused)
                if (focused && (cursorTick / 10) % 2 == 0 && !rows.isEmpty()) {
                    int vr = visualRowOfCaret(rows);
                    int[] r = rows.get(vr);
                    String line = lines.get(r[0]);
                    int cx = PAD_L + font().getStringWidth(line.substring(r[1], Math.min(r[2], caretCol)));
                    int cy = PAD_L - scrollY + vr * lineHeight();
                    GuiDraw.drawRect(cx, cy + 1, 1, lineHeight() - 2, 0xFFf0a500);
                }
            } finally {
                Stencil.remove();
            }
            drawScrollbar();
        }

        private void drawSelectionRow(int[] r, int y) {
            int sL, sC, eL, eC;
            if (selLine < caretLine || (selLine == caretLine && selCol < caretCol)) {
                sL = selLine;
                sC = selCol;
                eL = caretLine;
                eC = caretCol;
            } else {
                sL = caretLine;
                sC = caretCol;
                eL = selLine;
                eC = selCol;
            }
            int li = r[0];
            if (li < sL || li > eL) return;
            int from, to;
            if (li == sL && li == eL) {
                from = sC;
                to = eC;
            } else if (li == sL) {
                from = sC;
                to = lines.get(li)
                    .length();
            } else if (li == eL) {
                from = 0;
                to = eC;
            } else {
                from = 0;
                to = lines.get(li)
                    .length();
            }
            int hs = Math.max(r[1], from);
            int he = Math.min(r[2], to);
            if (hs >= he) {
                // empty row fully inside a multi-line selection → highlight the whole row
                if (r[1] == r[2] && ((li > sL && li < eL) || (li == sL && li < eL && sC == 0))) {
                    GuiDraw.drawRect(PAD_L, y, innerWidth(), lineHeight(), 0x332f72a8);
                }
                return;
            }
            String line = lines.get(li);
            int x0 = PAD_L + font().getStringWidth(line.substring(r[1], hs));
            int x1 = PAD_L + font().getStringWidth(line.substring(r[1], he));
            GuiDraw.drawRect(x0, y, x1 - x0, lineHeight(), 0x332f72a8);
        }

        /** A 3px vertical scrollbar on the right edge, shown only when content overflows. */
        private void drawScrollbar() {
            int contentH = buildRows().size() * lineHeight();
            int viewH = getArea().height - PAD_L;
            if (contentH <= viewH) return;
            float ratio = viewH / (float) contentH;
            int barH = Math.max(10, (int) (viewH * ratio));
            int maxScroll = contentH - viewH;
            int barY = PAD_L + (int) ((viewH - barH) * (scrollY / (float) maxScroll));
            int barX = getArea().width - 4;
            GuiDraw.drawRect(barX, barY, 3, barH, 0x66f0a500);
        }

        @Override
        public Interactable.Result onMousePressed(int mouseButton) {
            if (mouseButton != 0) return Interactable.Result.ACCEPT;
            getContext().focus(this);
            setCaretFromMouse();
            selLine = caretLine;
            selCol = caretCol;
            cursorTick = 0;
            return Interactable.Result.SUCCESS;
        }

        @Override
        public void onMouseDrag(int mouseButton, long timeSinceClick) {
            if (mouseButton != 0) return;
            setCaretFromMouse();
        }

        private void setCaretFromMouse() {
            List<int[]> rows = buildRows();
            if (rows.isEmpty()) return;
            int my = getContext().getMouseY();
            int vr = (my - PAD_L + scrollY) / lineHeight();
            if (vr < 0) vr = 0;
            if (vr >= rows.size()) vr = rows.size() - 1;
            int[] r = rows.get(vr);
            String seg = lines.get(r[0])
                .substring(r[1], r[2]);
            int mx = getContext().getMouseX() - PAD_L;
            caretLine = r[0];
            caretCol = r[1] + charColAt(seg, mx);
            scrollToCaret();
        }

        private int charColAt(String s, int x) {
            int w = 0;
            for (int i = 0; i < s.length(); i++) {
                int cw = font().getCharWidth(s.charAt(i));
                if (w + cw >= x) return i;
                w += cw;
            }
            return s.length();
        }

        @Override
        public boolean onMouseScroll(UpOrDown scrollDirection, int amount) {
            int maxScroll = Math.max(0, buildRows().size() * lineHeight() - (getArea().height - PAD_L));
            scrollY += (scrollDirection.isUp() ? -1 : 1) * SCROLL_STEP;
            if (scrollY < 0) scrollY = 0;
            if (scrollY > maxScroll) scrollY = maxScroll;
            return true;
        }

        @Override
        public Interactable.Result onKeyPressed(char typedChar, int keyCode) {
            if (!focused) return Interactable.Result.IGNORE;
            if (Interactable.hasControlDown()) {
                if (typedChar == 'c' || typedChar == 'C') {
                    copySelection();
                    return Interactable.Result.SUCCESS;
                }
                if (typedChar == 'v' || typedChar == 'V') {
                    paste();
                    return Interactable.Result.SUCCESS;
                }
                if (typedChar == 'a' || typedChar == 'A') {
                    selectAll();
                    return Interactable.Result.SUCCESS;
                }
            }
            switch (keyCode) {
                case Keyboard.KEY_BACK:
                    backspace();
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_DELETE:
                    deleteForward();
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_RETURN:
                case Keyboard.KEY_NUMPADENTER:
                    insertNewline();
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_LEFT:
                    moveCaretHoriz(-1);
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_RIGHT:
                    moveCaretHoriz(1);
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_UP:
                    moveCaretVert(-1);
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_DOWN:
                    moveCaretVert(1);
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_HOME:
                    caretCol = 0;
                    scrollToCaret();
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_END:
                    caretCol = lines.get(caretLine)
                        .length();
                    scrollToCaret();
                    return Interactable.Result.SUCCESS;
                case Keyboard.KEY_ESCAPE:
                    getContext().removeFocus();
                    return Interactable.Result.SUCCESS;
                default:
                    if (typedChar >= 32) {
                        insertChar(typedChar);
                        return Interactable.Result.SUCCESS;
                    }
                    return Interactable.Result.IGNORE;
            }
        }

        private void insertChar(char c) {
            deleteSelectionIfAny();
            String line = lines.get(caretLine);
            if (caretCol > line.length()) caretCol = line.length();
            StringBuilder sb = new StringBuilder(line);
            sb.insert(caretCol, c);
            lines.set(caretLine, sb.toString());
            caretCol++;
            scrollToCaret();
        }

        private void insertNewline() {
            deleteSelectionIfAny();
            String line = lines.get(caretLine);
            String left = line.substring(0, caretCol);
            String right = line.substring(caretCol);
            lines.set(caretLine, left);
            lines.add(caretLine + 1, right);
            caretLine++;
            caretCol = 0;
            scrollToCaret();
        }

        private void backspace() {
            if (deleteSelectionIfAny()) return;
            if (caretCol > 0) {
                String line = lines.get(caretLine);
                StringBuilder sb = new StringBuilder(line);
                sb.deleteCharAt(caretCol - 1);
                lines.set(caretLine, sb.toString());
                caretCol--;
            } else if (caretLine > 0) {
                String above = lines.get(caretLine - 1);
                lines.set(caretLine - 1, above + lines.get(caretLine));
                lines.remove(caretLine);
                caretLine--;
                caretCol = above.length();
            }
            scrollToCaret();
        }

        private void deleteForward() {
            if (deleteSelectionIfAny()) return;
            String line = lines.get(caretLine);
            if (caretCol < line.length()) {
                StringBuilder sb = new StringBuilder(line);
                sb.deleteCharAt(caretCol);
                lines.set(caretLine, sb.toString());
            } else if (caretLine < lines.size() - 1) {
                lines.set(caretLine, line + lines.get(caretLine + 1));
                lines.remove(caretLine + 1);
            }
            scrollToCaret();
        }

        /** Removes the selected range (if any) and places the caret at its start. */
        private boolean deleteSelectionIfAny() {
            if (selLine < 0) return false;
            int sL, sC, eL, eC;
            if (selLine < caretLine || (selLine == caretLine && selCol < caretCol)) {
                sL = selLine;
                sC = selCol;
                eL = caretLine;
                eC = caretCol;
            } else {
                sL = caretLine;
                sC = caretCol;
                eL = selLine;
                eC = selCol;
            }
            String first = lines.get(sL)
                .substring(0, sC);
            String last = lines.get(eL)
                .substring(eC);
            lines.set(sL, first + last);
            for (int i = eL; i > sL; i--) {
                lines.remove(i);
            }
            caretLine = sL;
            caretCol = sC;
            selLine = -1;
            scrollToCaret();
            return true;
        }

        private void copySelection() {
            if (!hasSelection()) return;
            int sL, sC, eL, eC;
            if (selLine < caretLine || (selLine == caretLine && selCol < caretCol)) {
                sL = selLine;
                sC = selCol;
                eL = caretLine;
                eC = caretCol;
            } else {
                sL = caretLine;
                sC = caretCol;
                eL = selLine;
                eC = selCol;
            }
            if (sL == eL) {
                GuiScreen.setClipboardString(
                    lines.get(sL)
                        .substring(sC, eC));
                return;
            }
            StringBuilder sb = new StringBuilder(
                lines.get(sL)
                    .substring(sC));
            for (int i = sL + 1; i < eL; i++) {
                sb.append('\n')
                    .append(lines.get(i));
            }
            sb.append('\n')
                .append(
                    lines.get(eL)
                        .substring(0, eC));
            GuiScreen.setClipboardString(sb.toString());
        }

        private void paste() {
            String clip = GuiScreen.getClipboardString();
            if (clip == null || clip.isEmpty()) return;
            deleteSelectionIfAny();
            String[] parts = clip.split("\n", -1);
            if (parts.length == 1) {
                String line = lines.get(caretLine);
                StringBuilder sb = new StringBuilder(line);
                sb.insert(caretCol, parts[0]);
                lines.set(caretLine, sb.toString());
                caretCol += parts[0].length();
            } else {
                String line = lines.get(caretLine);
                String left = line.substring(0, caretCol);
                String right = line.substring(caretCol);
                lines.set(caretLine, left + parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    lines.add(caretLine + i, parts[i]);
                }
                lines.add(caretLine + parts.length, right);
                caretLine += parts.length - 1;
                caretCol = parts[parts.length - 1].length();
            }
            selLine = -1;
            scrollToCaret();
        }

        private void selectAll() {
            selLine = 0;
            selCol = 0;
            caretLine = lines.size() - 1;
            caretCol = lines.get(caretLine)
                .length();
            scrollToCaret();
        }

        private void moveCaretHoriz(int dir) {
            if (dir < 0) {
                if (caretCol > 0) {
                    caretCol--;
                } else if (caretLine > 0) {
                    caretLine--;
                    caretCol = lines.get(caretLine)
                        .length();
                }
            } else {
                String line = lines.get(caretLine);
                if (caretCol < line.length()) {
                    caretCol++;
                } else if (caretLine < lines.size() - 1) {
                    caretLine++;
                    caretCol = 0;
                }
            }
            scrollToCaret();
        }

        private void moveCaretVert(int dir) {
            List<int[]> rows = buildRows();
            if (rows.isEmpty()) return;
            int cur = visualRowOfCaret(rows);
            int[] cr = rows.get(cur);
            int rel = caretCol - cr[1];
            int target = cur + dir;
            if (target < 0) {
                caretLine = 0;
                caretCol = 0;
                return;
            }
            if (target >= rows.size()) {
                caretLine = lines.size() - 1;
                caretCol = lines.get(caretLine)
                    .length();
                return;
            }
            int[] t = rows.get(target);
            caretLine = t[0];
            caretCol = t[1] + Math.min(Math.max(rel, 0), (t[2] - t[1]));
            scrollToCaret();
        }

        /** Keeps the caret's row inside the visible area. */
        private void scrollToCaret() {
            List<int[]> rows = buildRows();
            if (rows.isEmpty()) return;
            int vr = visualRowOfCaret(rows);
            int caretTop = vr * lineHeight();
            int caretBottom = caretTop + lineHeight();
            int viewH = getArea().height - PAD_L;
            if (caretTop < scrollY) {
                scrollY = caretTop;
            } else if (caretBottom > scrollY + viewH) {
                scrollY = caretBottom - viewH;
            }
            int maxScroll = Math.max(0, rows.size() * lineHeight() - viewH);
            if (scrollY < 0) scrollY = 0;
            if (scrollY > maxScroll) scrollY = maxScroll;
        }

        @Override
        public boolean isFocused() {
            return focused;
        }

        @Override
        public void onFocus(ModularGuiContext context) {
            focused = true;
            cursorTick = 0;
        }

        @Override
        public void onRemoveFocus(ModularGuiContext context) {
            focused = false;
        }
    }

    /** Scrollable list of prompt files, drawn from {@link SettingsState#promptList}. Clicking a row loads it. */
    private static class PromptListWidget extends Widget<PromptListWidget> implements Interactable {

        private final SettingsState st;
        private final java.util.function.Consumer<String> onSelect;
        private int scrollOffset;

        PromptListWidget(SettingsState st, java.util.function.Consumer<String> onSelect) {
            this.st = st;
            this.onSelect = onSelect;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            Stencil.applyAtZero(getArea(), context);
            try {
                int lh = Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT + 2;
                if (st.promptList.isEmpty()) {
                    Minecraft.getMinecraft().fontRenderer.drawString(
                        StatCollector.translateToLocal("talkwith.config.prompts_list_empty"),
                        2,
                        4,
                        0xFFCCC8B8,
                        false);
                    return;
                }
                int maxVisible = Math.max(1, (getArea().height - 4) / lh);
                int maxOffset = Math.max(0, st.promptList.size() - maxVisible);
                if (scrollOffset > maxOffset) scrollOffset = maxOffset;
                if (scrollOffset < 0) scrollOffset = 0;
                int y = 2;
                for (int i = scrollOffset; i < st.promptList.size() && y < getArea().height; i++) {
                    String f = st.promptList.get(i);
                    boolean active = f.equals(st.activePrompt);
                    Minecraft.getMinecraft().fontRenderer
                        .drawString((active ? "● " : "○ ") + f, 2, y, active ? 0xFFf0a500 : 0xFFCCC8B8, false);
                    y += lh;
                }
            } finally {
                Stencil.remove();
            }
        }

        @Override
        public Interactable.Result onMousePressed(int mouseButton) {
            if (mouseButton != 0) return Interactable.Result.ACCEPT;
            int lh = Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT + 2;
            int row = (getContext().getMouseY() - 2) / lh + scrollOffset;
            if (row >= 0 && row < st.promptList.size()) {
                onSelect.accept(st.promptList.get(row));
                return Interactable.Result.STOP;
            }
            return Interactable.Result.ACCEPT;
        }

        @Override
        public boolean onMouseScroll(UpOrDown scrollDirection, int amount) {
            scrollOffset += scrollDirection.isUp() ? -amount : amount;
            return true;
        }
    }

    // --- History sub-panel ---

    public static void openHistoryPanel(ModularPanel parent, GuiAIChat chatGui) {
        IPanelHandler handler = HISTORY_HANDLERS.get(parent);
        if (handler == null) {
            handler = IPanelHandler.simple(parent, (pp, pl) -> buildHistoryPanel(null, chatGui), true);
            HISTORY_HANDLERS.put(parent, handler);
        }
        handler.openPanel();
    }

    public static ModularPanel buildHistoryPanel(IPanelHandler backHandler, GuiAIChat chatGui) {
        int W = Math.max(400, screenW() - 40);
        int H = Math.max(180, screenH() - 40);
        int CHAT_W = Math.min(W - 154, W * 2 / 3);

        ModularPanel panel = new Dialog<>("history").setDisablePanelsBelow(false)
            .size(W, H);
        panel.background(new Rectangle().color(0xFF16213e, 0xFF16213e, 0xFF1a1a2e, 0xFF1a1a2e))
            .disableHoverThemeBackground(true);
        panel.padding(6);

        TextWidget<?> tb = new TextWidget<>("");
        tb.pos(0, 0)
            .size(W, 14);
        tb.background(new Rectangle().color(0x881a1a2e))
            .disableHoverThemeBackground(true);
        panel.child(tb);
        TextWidget<?> ti = new TextWidget<>(IKey.lang("talkwith.history.title"));
        ti.left(6)
            .top(3)
            .color(0xFFf0a500);
        panel.child(ti);

        // Message count — dynamic so reused panels stay up to date.
        TextWidget<?> cnt = new TextWidget<>(
            IKey.dynamic(
                () -> ClientProxy.chatHistory.size() + " "
                    + StatCollector.translateToLocal("talkwith.history.messages")));
        cnt.right(6)
            .top(3)
            .color(0x666677);
        panel.child(cnt);

        // Scrollable message list (left pane)
        HistoryListWidget list = new HistoryListWidget();
        list.pos(6, 16)
            .size(CHAT_W - 10, H - 24);
        panel.child(list);

        // Summary panel (right)
        int sx = CHAT_W + 6;
        int sw = W - sx - 6;

        TextWidget<?> sbg = new TextWidget<>("");
        sbg.left(sx)
            .top(16)
            .size(sw, H / 2);
        sbg.background(new Rectangle().color(0xAA111111))
            .disableHoverThemeBackground(true);
        panel.child(sbg);
        TextWidget<?> sti = new TextWidget<>(IKey.lang("talkwith.history.summary"));
        sti.left(sx + 4)
            .top(20)
            .color(0xFFFFFF);
        panel.child(sti);
        TextWidget<?> stx = new TextWidget<>(IKey.dynamic(() -> {
            if (ClientProxy.chatHistory.isEmpty()) {
                return StatCollector.translateToLocal("talkwith.history.empty");
            }
            String p = ClientProxy.chatHistory.get(ClientProxy.chatHistory.size() - 1);
            // Truncate by pixel width (handles CJK wide glyphs and surrogate pairs),
            // not by character count which would cut mid-glyph.
            int maxPx = Math.max(20, sw - 8);
            String trimmed = Minecraft.getMinecraft().fontRenderer.trimStringToWidth(p, maxPx);
            return trimmed.equals(p) ? p : trimmed + "...";
        }));
        stx.left(sx + 4)
            .top(34)
            .width(sw - 8)
            .color(0xFFCCC8B8);
        panel.child(stx);

        // Clear button
        ButtonWidget<?> clear = new ButtonWidget<>();
        clear.child(new TextWidget<>(IKey.lang("talkwith.history.clear")).center());
        clear.left(sx)
            .top(H - 48)
            .size(sw, 18);
        clear.onMouseTapped(btn -> {
            ClientProxy.chatHistory.clear();
            if (chatGui != null) chatGui.lines.clear();
            SessionClient.clearHistory();
            if (backHandler != null) backHandler.closePanel();
            else panel.closeIfOpen();
            return true;
        });
        panel.child(clear);

        // Back button
        ButtonWidget<?> back = new ButtonWidget<>();
        back.child(new TextWidget<>(IKey.lang("talkwith.gui.back")).center());
        back.left(sx)
            .top(H - 26)
            .size(sw, 18);
        back.onMouseTapped(btn -> {
            if (backHandler != null) backHandler.closePanel();
            else panel.closeIfOpen();
            return true;
        });
        panel.child(back);

        return panel;
    }

    private static class HistoryListWidget extends Widget<HistoryListWidget> implements Interactable {

        int scrollOffset;

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            Stencil.applyAtZero(getArea(), context);
            try {
                int lh = Minecraft.getMinecraft().fontRenderer.FONT_HEIGHT + 2;
                List<String> data = ClientProxy.chatHistory;
                int maxVisible = Math.max(1, (getArea().height - 4) / lh);
                int maxOffset = Math.max(0, data.size() - maxVisible);
                if (scrollOffset > maxOffset) scrollOffset = maxOffset;
                if (scrollOffset < 0) scrollOffset = 0;
                if (data.isEmpty()) {
                    Minecraft.getMinecraft().fontRenderer
                        .drawString(StatCollector.translateToLocal("talkwith.history.empty"), 2, 4, 0xFFCCC8B8, false);
                    return;
                }
                int start = Math.max(0, data.size() - maxVisible - scrollOffset);
                int y = 2;
                for (int i = start; i < data.size() && y < getArea().height; i++) {
                    Minecraft.getMinecraft().fontRenderer.drawString(data.get(i), 2, y, 0xFFCCC8B8, false);
                    y += lh;
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
    }

    private static int screenW() {
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaledWidth();
    }

    private static int screenH() {
        Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaledHeight();
    }
}
