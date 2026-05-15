package com.czqwq.talkwith;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.ServerChatEvent;

import com.czqwq.talkwith.ai.AIClient;
import com.czqwq.talkwith.ai.SharedSession;
import com.czqwq.talkwith.network.PacketClientAIRequest;
import com.czqwq.talkwith.network.PacketHandler;
import com.czqwq.talkwith.network.PacketHandshake;
import com.czqwq.talkwith.network.PacketSessionBroadcast;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

public class ServerEventHandler {

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            PacketHandler.INSTANCE.sendTo(new PacketHandshake(), (EntityPlayerMP) event.player);
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        String msg = event.message;
        if (!msg.startsWith("> ") || !(event.player instanceof EntityPlayerMP)) return;

        event.setCanceled(true);
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        String text = msg.substring(2)
            .trim();
        UUID playerUuid = player.getUniqueID();
        String playerName = player.getCommandSenderName();

        // Check if the player is in a server session
        SharedSession foundSession = null;
        for (SharedSession s : SharedSession.sessions.values()) {
            if (s.hasPlayer(playerUuid)) {
                foundSession = s;
                break;
            }
        }

        if (foundSession != null) {
            final SharedSession session = foundSession;
            final MinecraftServer server = MinecraftServer.getServer();
            session.session.addMessage("user", playerName + ": " + text);
            AIClient.sendAsync(
                session.session.getMessages(Config.systemPrompt),
                session.ownerBaseUrl,
                session.ownerApiKey,
                session.sessionModel,
                reply -> {
                    session.session.addMessage("assistant", reply);
                    session.lastReplyTime = System.currentTimeMillis();
                    broadcastToSession(session, playerName, text, reply, server);
                },
                err -> broadcastErrorToSession(session, err, server));
        } else {
            // No server session: delegate to client for local AI processing
            PacketHandler.INSTANCE.sendTo(new PacketClientAIRequest(playerName, text), player);
        }
    }

    private static void broadcastToSession(SharedSession session, String playerName, String playerMsg, String reply,
        MinecraftServer server) {
        PacketSessionBroadcast packet = new PacketSessionBroadcast(playerName, playerMsg, reply);
        for (UUID uuid : session.players) {
            EntityPlayerMP member = getPlayerByUUID(server, uuid);
            if (member != null) {
                PacketHandler.INSTANCE.sendTo(packet, member);
            }
        }
    }

    private static void broadcastErrorToSession(SharedSession session, String err, MinecraftServer server) {
        for (UUID uuid : session.players) {
            EntityPlayerMP member = getPlayerByUUID(server, uuid);
            if (member != null) {
                member.addChatMessage(
                    new net.minecraft.util.ChatComponentText("§c[TalkWith]§r ").appendSibling(
                        new net.minecraft.util.ChatComponentTranslation("talkwith.session.ai_error", err)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityPlayerMP getPlayerByUUID(MinecraftServer server, UUID uuid) {
        for (Object obj : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP p = (EntityPlayerMP) obj;
            if (p.getUniqueID()
                .equals(uuid)) return p;
        }
        return null;
    }
}
