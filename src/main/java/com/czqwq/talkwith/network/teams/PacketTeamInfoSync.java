package com.czqwq.talkwith.network.teams;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.czqwq.talkwith.TalkWith;
import com.czqwq.talkwith.teams.TeamManagerClient;
import com.czqwq.talkwith.teams.TeamRole;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketTeamInfoSync implements IMessage {

    private UUID uuid;
    private String name;
    private TeamRole role;

    public PacketTeamInfoSync() {}

    public PacketTeamInfoSync(UUID uuid, String name, TeamRole role) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            long most = buf.readLong();
            long least = buf.readLong();
            this.uuid = new UUID(most, least);
            int nameLen = buf.readShort();
            if (nameLen < 0 || nameLen > buf.readableBytes()) {
                throw new IllegalArgumentException("Invalid team name length: " + nameLen);
            }
            byte[] nameBytes = new byte[nameLen];
            buf.readBytes(nameBytes);
            this.name = new String(nameBytes, StandardCharsets.UTF_8);
            short roleOrdinal = buf.readShort();
            TeamRole[] roles = TeamRole.values();
            this.role = (roleOrdinal >= 0 && roleOrdinal < roles.length) ? roles[roleOrdinal] : TeamRole.MEMBER;
        } catch (Exception e) {
            this.uuid = new UUID(0L, 0L);
            this.name = "";
            this.role = TeamRole.MEMBER;
            TalkWith.LOG.error("Failed to decode PacketTeamInfoSync", e);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(nameBytes.length);
        buf.writeBytes(nameBytes);
        buf.writeShort((short) role.ordinal());
    }

    public static class Handler implements IMessageHandler<PacketTeamInfoSync, IMessage> {

        @Override
        public IMessage onMessage(PacketTeamInfoSync msg, MessageContext ctx) {
            TeamManagerClient.onTeamInfoSyncPacket(msg.uuid, msg.name, msg.role);
            return null;
        }
    }
}
