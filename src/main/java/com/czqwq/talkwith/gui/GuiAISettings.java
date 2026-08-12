package com.czqwq.talkwith.gui;

import com.cleanroommc.modularui.screen.CustomModularScreen;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.czqwq.talkwith.TalkWith;

/**
 * Standalone settings screen (for /talkwith settings command).
 * When opened from GuiAIChat, uses IPanelHandler sub-panel instead.
 */
public class GuiAISettings extends CustomModularScreen {

    public GuiAISettings() {
        super(TalkWith.MODID);
    }

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        // Fresh state each build: the standalone screen is recreated on every open, so its
        // settings must reflect the current Config values (not a stale cache). Seeding also
        // registers this state as the active one so async server replies reach it.
        GuiSubPanels.SettingsState st = new GuiSubPanels.SettingsState();
        GuiSubPanels.seedSettingsState(st);
        return GuiSubPanels.buildSettingsPanel(null, st);
    }
}
