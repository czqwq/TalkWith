package com.czqwq.talkwith.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import com.czqwq.talkwith.ai.SharedSession;
import com.czqwq.talkwith.prompt.PromptStore;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Client → server request to write a prompt file into the sender's session prompt directory.
 * Owner only. The server replies with the updated prompt file list so the GUI can refresh.
 * The content can be large, so a safe 4-byte length-prefixed string encoding is used instead of
 * {@code ByteBufUtils.writeUTF8String} (64 KB limit).
 */
public class PacketPromptWrite implements IMessage {

    public String name = "";
    public String content = "";

    public PacketPromptWrite() {}

    public PacketPromptWrite(String name, String content) {
        this.name = name != null ? name : "";
        this.content = content != null ? content : "";
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = readString(buf);
        content = readString(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeString(buf, name);
        writeString(buf, content);
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = (s != null ? s : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    private static String readString(ByteBuf buf) {
        int len = buf.readInt();
        if (len < 0 || len > 10 * 1024 * 1024) {
            throw new IllegalStateException("PacketPromptWrite: string length out of range: " + len);
        }
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static class Handler implements IMessageHandler<PacketPromptWrite, IMessage> {

        @Override
        public IMessage onMessage(PacketPromptWrite msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            UUID playerUuid = player.getUniqueID();
            SharedSession s = SharedSession.findByPlayer(playerUuid);
            if (s == null) {
                player.addChatMessage(err("talkwith.session.not_found"));
                return null;
            }
            if (!s.ownerUuid.equals(playerUuid)) {
                player.addChatMessage(err("talkwith.session.owner_only"));
                return null;
            }
            String filename = com.czqwq.talkwith.Config.sanitizePromptFilename(msg.name);
            if (filename.isEmpty()) {
                player.addChatMessage(err("talkwith.config.prompt_file.invalid"));
                return null;
            }
            PromptStore.writeSession(s.sessionId, filename, msg.content);
            player.addChatMessage(okf("talkwith.config.prompt_file.written", filename));
            return PacketPromptData.list(PromptStore.listSession(s.sessionId));
        }

        private static IChatComponent err(String key) {
            return new ChatComponentText("§c[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key));
        }

        private static IChatComponent okf(String key, Object... args) {
            return new ChatComponentText("§a[TalkWith]§r ").appendSibling(new ChatComponentTranslation(key, args));
        }
    }
}
