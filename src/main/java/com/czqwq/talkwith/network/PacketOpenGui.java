package com.czqwq.talkwith.network;

import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.util.TextUtils;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → Client: sets the client's current session ID and single-override state.
 * Sending an empty string clears the session (returns to client/single mode).
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
    /**
     * Human-readable session name for display in notifications.
     * Empty string means the session has no name (UUID will be shown as fallback).
     */
    public String sessionName;
    /** Whether the player has the single-mode override active in this session. */
    public boolean singleOverride;

    public PacketOpenGui() {}

    public PacketOpenGui(String sessionId) {
        this(sessionId, false, "", false);
    }

    public PacketOpenGui(String sessionId, boolean silent) {
        this(sessionId, silent, "", false);
    }

    public PacketOpenGui(String sessionId, boolean silent, String sessionName) {
        this(sessionId, silent, sessionName, false);
    }

    public PacketOpenGui(String sessionId, boolean silent, String sessionName, boolean singleOverride) {
        this.sessionId = sessionId != null ? sessionId : "";
        this.silent = silent;
        this.sessionName = sessionName != null ? sessionName : "";
        this.singleOverride = singleOverride;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        sessionId = ByteBufUtils.readUTF8String(buf);
        silent = buf.readBoolean();
        sessionName = ByteBufUtils.readUTF8String(buf);
        singleOverride = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, sessionId != null ? sessionId : "");
        buf.writeBoolean(silent);
        ByteBufUtils.writeUTF8String(buf, sessionName != null ? sessionName : "");
        buf.writeBoolean(singleOverride);
    }

    public static class Handler implements IMessageHandler<PacketOpenGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenGui msg, MessageContext ctx) {
            final String sid = msg.sessionId;
            final boolean isSilent = msg.silent;
            final String sName = msg.sessionName != null ? msg.sessionName : "";
            final boolean override = msg.singleOverride;
            ClientProxy.scheduleOnMainThread(() -> {
                if (sid == null || sid.isEmpty()) {
                    ClientProxy.currentSessionId = null;
                    ClientProxy.isSingleOverride = false;
                    // Close the GUI if it is currently open for a session
                    if (ClientProxy.activeGui != null) {
                        ClientGUI.close();
                    }
                    return;
                }
                // Always update the session ID and override state
                ClientProxy.currentSessionId = sid;
                ClientProxy.isSingleOverride = override;
                // Prefer the human-readable session name; fall back to UUID.
                String displayName = sName.isEmpty() ? sid : sName;
                if (isSilent) {
                    // Silent restore (login reconnect): just set the ID, notify via chat
                    TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.gui.session_joined", displayName));
                    return;
                }
                if (ClientProxy.useVanillaGui()) {
                    // Vanilla mode: just update the session ID and notify via chat
                    TextUtils.info(StatCollector.translateToLocalFormatted("talkwith.gui.session_joined", displayName));
                } else {
                    // Open the GUI if it is not already open
                    if (ClientProxy.activeGui == null) {
                        ClientGUI.open(new GuiAIChat());
                    }
                }
            });
            return null;
        }
    }
}
