package com.czqwq.talkwith.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.Config;
import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketSessionMessage;

public class GuiAIChat extends GuiScreen {

    private final List<String> lines = new ArrayList<>();
    private GuiTextField inputField;
    private final String initialText;
    private boolean isThinking = false;
    private int thinkingTick = 0;
    public String currentSessionId = null;

    public GuiAIChat(String initialText) {
        this.initialText = initialText;
        this.currentSessionId = ClientProxy.currentSessionId;
    }

    @Override
    public void initGui() {
        inputField = new GuiTextField(fontRendererObj, 4, height - 24, width - 8, 20);
        inputField.setMaxStringLength(512);
        inputField.setFocused(true);
        if (initialText != null && !initialText.isEmpty()) {
            inputField.setText(initialText);
            sendMessage();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height - 28, 0xCC000000);
        drawRect(0, height - 28, width, height - 27, 0xFF888888);
        drawRect(2, height - 26, width - 2, height - 5, 0xFF000000);

        int lineHeight = fontRendererObj.FONT_HEIGHT + 2;
        int maxVisibleLines = (height - 32) / lineHeight;
        int startY = height - 30 - lineHeight;

        // Render from bottom up
        for (int i = lines.size() - 1; i >= 0 && startY > 0; i--) {
            String line = lines.get(i);
            List<String> wrapped = wrapLine(line, width - 8);
            for (int j = wrapped.size() - 1; j >= 0 && startY > 0; j--) {
                fontRendererObj.drawStringWithShadow(wrapped.get(j), 4, startY, 0xFFFFFF);
                startY -= lineHeight;
            }
        }

        if (isThinking) {
            StringBuilder dotBuilder = new StringBuilder();
            int dotCount = (thinkingTick / 10) % 4;
            for (int d = 0; d < dotCount; d++) dotBuilder.append('.');
            String dots = dotBuilder.toString();
            fontRendererObj.drawStringWithShadow("§7AI is thinking" + dots, 4, startY > 0 ? startY : 4, 0xFFFFFF);
        }

        inputField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @SuppressWarnings("unchecked")
    private List<String> wrapLine(String text, int maxWidth) {
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            List<String> single = new java.util.ArrayList<>();
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

        lines.add("§eYou: §f" + text);
        inputField.setText("");
        isThinking = true;

        if (currentSessionId != null) {
            PacketHandler.INSTANCE.sendToServer(new PacketSessionMessage(currentSessionId, text));
            isThinking = false;
        } else {
            ClientProxy.clientSession.addMessage("user", text);
            AIClient.sendAsync(
                ClientProxy.clientSession.getMessages(Config.systemPrompt),
                reply -> ClientProxy.scheduleOnMainThread(() -> {
                    ClientProxy.clientSession.addMessage("assistant", reply);
                    lines.add("§b[AI]§r " + reply);
                    isThinking = false;
                }),
                err -> ClientProxy.scheduleOnMainThread(() -> {
                    lines.add("§c[Error]§r " + err);
                    isThinking = false;
                }));
        }
    }

    @Override
    public void updateScreen() {
        thinkingTick++;
        inputField.updateCursorCounter();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
