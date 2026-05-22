package com.czqwq.talkwith.network;

import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.util.TextUtils;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: sets the client's current session ID.
 * Sending an empty string clears it (returns to client/single mode).
 *
 * <p>
 * When {@link #silent} is {@code true} (used on player login to restore a previous session),
 * only {@link ClientProxy#currentSessionId} is updated — the GUI is never auto-opened. Players
 * must explicitly run {@code /talkwith gui} to open the GUI after a reconnect.
 */
public class PacketOpenGui implements IMessage {

    public String sessionId;
    /**
     * When {@code true}, only update {@link ClientProxy#currentSessionId}; do NOT open the GUI.
     * Used on login to silently restore session membership without forcing the chat screen open.
     */
    public boolean silent;

    public PacketOpenGui() {}

    public PacketOpenGui(String sessionId) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.silent = false;
    }

    public PacketOpenGui(String sessionId, boolean silent) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.silent = silent;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        sessionId = ByteBufUtils.readUTF8String(buf);
        silent = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, sessionId != null ? sessionId : "");
        buf.writeBoolean(silent);
    }

    public static class Handler implements IMessageHandler<PacketOpenGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenGui msg, MessageContext ctx) {
            final String sid = msg.sessionId;
            final boolean isSilent = msg.silent;
            ClientProxy.scheduleOnMainThread(() -> {
                if (sid == null || sid.isEmpty()) {
                    ClientProxy.currentSessionId = null;
                    // Close the GUI if it is currently open for a session
                    if (ClientProxy.activeGui != null) {
                        Minecraft.getMinecraft()
                            .displayGuiScreen(null);
                    }
                    return;
                }
                // Always update the session ID
                ClientProxy.currentSessionId = sid;
                if (isSilent) {
                    // Silent restore (login reconnect): just set the ID, notify via chat
                    String displayName = sid;
                    TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.gui.session_joined", displayName));
                    return;
                }
                if (ClientProxy.useVanillaGui) {
                    // Vanilla mode: just update the session ID and notify via chat
                    TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.gui.session_joined", sid));
                } else {
                    // Open the GUI if it is not already open
                    if (ClientProxy.activeGui == null) {
                        Minecraft.getMinecraft()
                            .displayGuiScreen(new GuiAIChat(""));
                    } else {
                        // Update the session ID on the already-open GUI
                        ClientProxy.activeGui.currentSessionId = sid;
                    }
                }
            });
            return null;
        }
    }
}
