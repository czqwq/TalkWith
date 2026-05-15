package com.czqwq.talkwith;

import java.util.concurrent.ConcurrentLinkedQueue;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import com.czqwq.talkwith.ai.ChatSession;
import com.czqwq.talkwith.command.TalkWithCommand;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    public static final ChatSession clientSession = new ChatSession();
    public static volatile boolean serverHasMod = false;
    public static String currentSessionId = null;

    private static final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();

    public static void scheduleOnMainThread(Runnable r) {
        mainThreadTasks.add(r);
    }

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientCommandHandler.instance.registerCommand(new TalkWithCommand());
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Runnable r;
        while ((r = mainThreadTasks.poll()) != null) {
            try {
                r.run();
            } catch (Exception e) {
                TalkWith.LOG.error("Main thread task error", e);
            }
        }
    }

    // Note: ClientChatEvent does not exist in this GTNH Forge build.
    // The ">" prefix chat shortcut is therefore not available client-side;
    // users should use /talkwith or the server-side ServerChatEvent hook instead.

    @SubscribeEvent
    public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        serverHasMod = false;
        currentSessionId = null;
    }
}
