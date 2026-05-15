package com.czqwq.talkwith;

import com.czqwq.talkwith.ai.ChatSession;
import com.czqwq.talkwith.command.TalkWithCommand;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.network.PacketHandler;
import cpw.mods.fml.client.ClientCommandHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.concurrent.ConcurrentLinkedQueue;

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
        FMLCommonHandler.instance().bus().register(this);
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

    @SubscribeEvent
    public void onClientChat(ClientChatEvent event) {
        String msg = event.message;
        if (msg.startsWith("> ") || msg.equals(">")) {
            event.setCanceled(true);
            String text = msg.length() > 2 ? msg.substring(2) : "";
            Minecraft.getMinecraft().displayGuiScreen(new GuiAIChat(text));
        }
    }

    @SubscribeEvent
    public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        serverHasMod = false;
        currentSessionId = null;
    }
}
