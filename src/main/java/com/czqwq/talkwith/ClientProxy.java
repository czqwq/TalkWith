package com.czqwq.talkwith;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import com.czqwq.talkwith.ai.ChatSession;
import com.czqwq.talkwith.command.TalkWithCommand;
import com.czqwq.talkwith.gui.GuiAIChat;

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
    /** True when the player is in a session but has used {@code /talkwith switch single}. */
    public static boolean isSingleOverride = false;
    /** True when takeover mode is active (all chat intercepted, no {@code >} prefix needed). */
    public static boolean isTakeover = false;
    /**
     * Chat mode while in takeover: {@code "ai"} (default), {@code "group"}, or {@code "public"}.
     * Only meaningful when {@link #isTakeover} is true.
     */
    public static String takeoverChatMode = "ai";
    /**
     * The currently-open {@link GuiAIChat} instance, or {@code null} when the GUI is closed.
     * Set by {@link GuiAIChat} on open/close. Used by {@link com.czqwq.talkwith.network.PacketSessionBroadcast}
     * to route AI replies into the GUI rather than vanilla chat when the player has it open.
     */
    public static volatile GuiAIChat activeGui = null;
    /**
     * When {@code false} (default), AI replies are shown in {@link GuiAIChat}.
     * When {@code true}, all AI I/O is routed through the vanilla chat HUD.
     * Persisted via {@link Config#guiMode}; never reset on reconnect.
     */
    public static boolean useVanillaGui = false;

    /**
     * Persistent chat history shared across all {@link GuiAIChat} instances.
     * Survives GUI open/close cycles so that closing the inventory (which closes the GUI)
     * does not discard the conversation. Capped at {@link #MAX_CHAT_HISTORY} entries.
     */
    public static final List<String> chatHistory = new CopyOnWriteArrayList<>();
    /** Maximum number of lines retained in {@link #chatHistory}. */
    public static final int MAX_CHAT_HISTORY = 500;

    private static final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();

    /**
     * Appends a line to {@link #chatHistory}, evicting the oldest entry if the cap is exceeded.
     * Safe to call from any thread; list is a {@link CopyOnWriteArrayList}.
     */
    public static void addToChatHistory(String line) {
        chatHistory.add(line);
        while (chatHistory.size() > MAX_CHAT_HISTORY) {
            chatHistory.remove(0);
        }
    }

    public static void scheduleOnMainThread(Runnable r) {
        mainThreadTasks.add(r);
    }

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        // Apply the persisted GUI mode preference (default vs vanilla)
        useVanillaGui = "vanilla".equals(Config.guiMode);
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
    // The ">" prefix shortcut is handled server-side via ServerChatEvent in ServerEventHandler,
    // which cancels the message and sends PacketOpenGui to open GuiAIChat on the client.

    @SubscribeEvent
    public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        serverHasMod = false;
        currentSessionId = null;
        isSingleOverride = false;
        isTakeover = false;
        takeoverChatMode = "ai";
        chatHistory.clear();
    }
}
