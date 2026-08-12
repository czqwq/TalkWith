package com.czqwq.talkwith.network;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.gui.GuiSubPanels;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → client reply to the {@code setting_get} request. Carries the session's
 * current AI settings so the settings GUI can display the real session values
 * (not stale local {@code Config} fallbacks) in multi mode.
 * <p>
 * The API key itself is never sent back — only whether one is configured, so the
 * GUI can show "已配置/未配置" without leaking the secret a second time.
 */
public class PacketSessionSettings implements IMessage {

    public String model;
    public String baseUrl;
    public boolean hasApiKey;
    public String promptFile;

    public PacketSessionSettings() {}

    public PacketSessionSettings(String model, String baseUrl, boolean hasApiKey, String promptFile) {
        this.model = model != null ? model : "";
        this.baseUrl = baseUrl != null ? baseUrl : "";
        this.hasApiKey = hasApiKey;
        this.promptFile = promptFile != null ? promptFile : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        model = readString(buf);
        baseUrl = readString(buf);
        hasApiKey = buf.readBoolean();
        promptFile = readString(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeString(buf, model);
        writeString(buf, baseUrl);
        buf.writeBoolean(hasApiKey);
        writeString(buf, promptFile);
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len < 0 || len > 10 * 1024 * 1024) {
            throw new IllegalStateException("PacketSessionSettings: string length out of range: " + len);
        }
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static class Handler implements IMessageHandler<PacketSessionSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketSessionSettings msg, MessageContext ctx) {
            ClientProxy.scheduleOnMainThread(() -> {
                ClientProxy.storeSessionSettings(msg);
                GuiSubPanels.onSessionSettingsReceived();
            });
            return null;
        }
    }
}
