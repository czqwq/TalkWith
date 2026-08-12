package com.czqwq.talkwith.gui;

import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.czqwq.talkwith.TalkWith;

/**
 * Standalone history screen (for /talkwith history open command).
 * When opened from GuiAIChat, uses IPanelHandler sub-panel instead.
 */
public class GuiChatHistory extends CustomModularScreen {

    private final CustomModularScreen parent;

    public GuiChatHistory(CustomModularScreen parent) {
        super(TalkWith.MODID);
        this.parent = parent;
    }

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        return GuiSubPanels.buildHistoryPanel(null, parent instanceof GuiAIChat ? (GuiAIChat) parent : null);
    }
}
