package com.czqwq.talkwith.network;

import com.czqwq.talkwith.network.PacketHandshake;
import com.czqwq.talkwith.network.PacketJoinSession;
import com.czqwq.talkwith.network.PacketSessionBroadcast;
import com.czqwq.talkwith.network.PacketSessionControl;
import com.czqwq.talkwith.network.PacketSessionMessage;
import com.czqwq.talkwith.network.PacketShareInvite;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public class PacketHandler {
    public static final String CHANNEL = "talkwith";
    public static SimpleNetworkWrapper INSTANCE;
    private static int id = 0;

    public static void init() {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        INSTANCE.registerMessage(PacketHandshake.Handler.class, PacketHandshake.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(PacketShareInvite.Handler.class, PacketShareInvite.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(PacketJoinSession.Handler.class, PacketJoinSession.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PacketSessionMessage.Handler.class, PacketSessionMessage.class, id++, Side.SERVER);
        INSTANCE.registerMessage(PacketSessionBroadcast.Handler.class, PacketSessionBroadcast.class, id++, Side.CLIENT);
        INSTANCE.registerMessage(PacketSessionControl.Handler.class, PacketSessionControl.class, id++, Side.SERVER);
    }
}
