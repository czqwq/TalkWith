package com.czqwq.talkwith.network;

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

    public static final class NoOpShareInvite<M extends IMessage> implements IMessageHandler<M, IMessage> {

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
        INSTANCE.registerMessage(
            isClient ? PacketShareInvite.Handler.class : (Class) NoOpShareInvite.class,
            PacketShareInvite.class,
            1,
            Side.CLIENT);
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

        INSTANCE.registerMessage(
            isClient ? PacketClientAIRequest.Handler.class : (Class) NoOpClientAIRequest.class,
            PacketClientAIRequest.class,
            7,
            Side.CLIENT);
    }
}
