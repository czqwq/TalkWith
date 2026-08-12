package com.czqwq.talkwith.network.teams;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import org.apache.commons.lang3.tuple.Pair;

import com.czqwq.talkwith.teams.TeamManagerClient;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;

public class PacketTeamDataSync implements IMessage {

    private boolean complete;
    private List<Pair<String, NBTTagCompound>> data;

    public PacketTeamDataSync() {}

    public PacketTeamDataSync(List<Pair<String, NBTTagCompound>> data) {
        this.complete = true;
        this.data = data;
    }

    public PacketTeamDataSync(String key, NBTTagCompound tag) {
        this.complete = false;
        this.data = Collections.singletonList(Pair.of(key, tag));
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            complete = buf.readBoolean();
            int length = buf.readShort();
            if (length < 0 || length > 256) {
                throw new IllegalArgumentException("Invalid team data entry count: " + length);
            }
            data = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                int keyLen = buf.readShort();
                if (keyLen < 0 || keyLen > buf.readableBytes()) {
                    throw new IllegalArgumentException("Invalid team data key length: " + keyLen);
                }
                byte[] keyBytes = new byte[keyLen];
                buf.readBytes(keyBytes);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                // CompressedStreamTools.write(...) below writes uncompressed NBT — read it back the same way.
                NBTTagCompound tag = CompressedStreamTools.read(new DataInputStream(new ByteBufInputStream(buf)));
                data.add(Pair.of(key, tag));
            }
        } catch (Exception e) {
            com.czqwq.talkwith.TalkWith.LOG.error("[TalkWith] Failed to read PacketTeamDataSync", e);
            data = Collections.emptyList();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        try {
            buf.writeBoolean(complete);
            buf.writeShort(data.size());
            for (Pair<String, NBTTagCompound> pair : data) {
                byte[] keyBytes = pair.getLeft()
                    .getBytes(StandardCharsets.UTF_8);
                buf.writeShort(keyBytes.length);
                buf.writeBytes(keyBytes);
                CompressedStreamTools.write(pair.getRight(), new ByteBufOutputStream(buf));
            }
        } catch (Exception e) {
            com.czqwq.talkwith.TalkWith.LOG.error("[TalkWith] Failed to write PacketTeamDataSync", e);
        }
    }

    public static class Handler implements IMessageHandler<PacketTeamDataSync, IMessage> {

        @Override
        public IMessage onMessage(PacketTeamDataSync msg, MessageContext ctx) {
            if (msg.data != null) {
                TeamManagerClient.onTeamDataSyncPacket(msg.complete, msg.data);
            }
            return null;
        }
    }
}
