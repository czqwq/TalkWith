package com.czqwq.talkwith.network;

import java.util.ArrayList;
import java.util.List;

import com.czqwq.talkwith.ClientProxy;
import com.czqwq.talkwith.gui.GuiSubPanels;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server → client reply to prompt management requests ({@code prompt_list} / {@code prompt_read} /
 * {@code prompt_write}). Either carries a list of prompt filenames in the session's prompt directory
 * ({@link #isList}) or a single file's {@link #name} + {@link #content}.
 */
public class PacketPromptData implements IMessage {

    public boolean isList;
    public List<String> names = new ArrayList<>();
    public String name = "";
    public String content = "";

    public PacketPromptData() {}

    public static PacketPromptData list(List<String> names) {
        PacketPromptData p = new PacketPromptData();
        p.isList = true;
        p.names = names != null ? names : new ArrayList<String>();
        return p;
    }

    public static PacketPromptData content(String name, String content) {
        PacketPromptData p = new PacketPromptData();
        p.isList = false;
        p.name = name != null ? name : "";
        p.content = content != null ? content : "";
        return p;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        isList = buf.readBoolean();
        if (isList) {
            int n = buf.readInt();
            for (int i = 0; i < n && i < 1000; i++) {
                names.add(readString(buf));
            }
        } else {
            name = readString(buf);
            content = readString(buf);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(isList);
        if (isList) {
            buf.writeInt(names.size());
            for (String s : names) {
                writeString(buf, s);
            }
        } else {
            writeString(buf, name);
            writeString(buf, content);
        }
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len < 0 || len > 10 * 1024 * 1024) {
            throw new IllegalStateException("PacketPromptData: string length out of range: " + len);
        }
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static class Handler implements IMessageHandler<PacketPromptData, IMessage> {

        @Override
        public IMessage onMessage(PacketPromptData msg, MessageContext ctx) {
            ClientProxy.scheduleOnMainThread(() -> GuiSubPanels.onPromptData(msg));
            return null;
        }
    }
}
