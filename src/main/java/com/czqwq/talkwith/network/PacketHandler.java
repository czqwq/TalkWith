package com.czqwq.talkwith.network;

import com.czqwq.talkwith.network.teams.PacketTeamDataSync;
import com.czqwq.talkwith.network.teams.PacketTeamInfoSync;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public class PacketHandler {

    public static final String CHANNEL = "talkwith";
    public static SimpleNetworkWrapper INSTANCE;

    /**
     * No-op handlers used on the server side for client-bound packets.
     * Each packet needs its own distinct class so Netty's pipeline does not
     * reject them as duplicate handler names.
     */
    public static final class NoOpHandshake<M extends IMessage> implements IMessageHandler<M, IMessage> {

        @Override
        public IMessage onMessage(M message, MessageContext ctx) {
            return null;
        }
    }

    public static final class NoOpOpenGui<M extends IMessage> implements IMessageHandler<M, IMessage> {

        @Override
        public IMessage onMessage(M message, MessageContext ctx) {
            return null;
        }
    }

    public static final class NoOpSessionBroadcast<M extends IMessage> implements IMessageHandler<M, IMessage> {

        @Override
        public IMessage onMessage(M message, MessageContext ctx) {
            return null;
        }
    }

    public static final class NoOpClientAIRequest<M extends IMessage> implements IMessageHandler<M, IMessage> {

        @Override
        public IMessage onMessage(M message, MessageContext ctx) {
            return null;
        }
    }

    public static final class NoOpTeamInfoSync<M extends IMessage> implements IMessageHandler<M, IMessage> {

        @Override
        public IMessage onMessage(M message, MessageContext ctx) {
            return null;
        }
    }

    public static final class NoOpTeamDataSync<M extends IMessage> implements IMessageHandler<M, IMessage> {

        @Override
        public IMessage onMessage(M message, MessageContext ctx) {
            return null;
        }
    }

    public static final class NoOpSessionSettings<M extends IMessage> implements IMessageHandler<M, IMessage> {

        @Override
        public IMessage onMessage(M message, MessageContext ctx) {
            return null;
        }
    }

    public static final class NoOpPromptData<M extends IMessage> implements IMessageHandler<M, IMessage> {

        @Override
        public IMessage onMessage(M message, MessageContext ctx) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static void init() {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        boolean isClient = FMLCommonHandler.instance()
            .getSide()
            .isClient();

        // CLIENT-bound packets: register real handlers only on the client side.
        // The server must still register the same message class with the same discriminator ID
        // so the network codec is available on both sides, but it uses a no-op handler.
        // Each no-op class must be distinct so Netty does not reject duplicate pipeline names.
        INSTANCE.registerMessage(
            isClient ? PacketHandshake.Handler.class : (Class) NoOpHandshake.class,
            PacketHandshake.class,
            0,
            Side.CLIENT);
        // Discriminator 1 freed (was PacketShareInvite, removed — team system replaced session invites)
        INSTANCE.registerMessage(
            isClient ? PacketOpenGui.Handler.class : (Class) NoOpOpenGui.class,
            PacketOpenGui.class,
            2,
            Side.CLIENT);

        // SERVER-bound packets: same on both sides.
        INSTANCE.registerMessage(PacketJoinSession.Handler.class, PacketJoinSession.class, 3, Side.SERVER);
        INSTANCE.registerMessage(PacketSessionMessage.Handler.class, PacketSessionMessage.class, 4, Side.SERVER);

        INSTANCE.registerMessage(
            isClient ? PacketSessionBroadcast.Handler.class : (Class) NoOpSessionBroadcast.class,
            PacketSessionBroadcast.class,
            5,
            Side.CLIENT);

        INSTANCE.registerMessage(PacketSessionControl.Handler.class, PacketSessionControl.class, 6, Side.SERVER);

        // Client-bound reply to the "setting_get" request (session AI settings for the settings GUI).
        INSTANCE.registerMessage(
            isClient ? PacketSessionSettings.Handler.class : (Class) NoOpSessionSettings.class,
            PacketSessionSettings.class,
            10,
            Side.CLIENT);

        // Client-bound prompt management replies (list / content).
        INSTANCE.registerMessage(
            isClient ? PacketPromptData.Handler.class : (Class) NoOpPromptData.class,
            PacketPromptData.class,
            11,
            Side.CLIENT);

        // Server-bound prompt write request (name + content; owner only).
        INSTANCE.registerMessage(PacketPromptWrite.Handler.class, PacketPromptWrite.class, 12, Side.SERVER);

        INSTANCE.registerMessage(
            isClient ? PacketClientAIRequest.Handler.class : (Class) NoOpClientAIRequest.class,
            PacketClientAIRequest.class,
            7,
            Side.CLIENT);

        // Team system packets (CLIENT-bound)
        INSTANCE.registerMessage(
            isClient ? PacketTeamInfoSync.Handler.class : (Class) NoOpTeamInfoSync.class,
            PacketTeamInfoSync.class,
            8,
            Side.CLIENT);
        INSTANCE.registerMessage(
            isClient ? PacketTeamDataSync.Handler.class : (Class) NoOpTeamDataSync.class,
            PacketTeamDataSync.class,
            9,
            Side.CLIENT);
    }
}
