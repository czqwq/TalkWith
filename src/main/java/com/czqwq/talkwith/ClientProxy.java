package com.czqwq.talkwith;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import com.czqwq.talkwith.ai.ChatSession;
import com.czqwq.talkwith.command.TalkWithCommand;
import com.czqwq.talkwith.gui.GuiAIChat;
import com.czqwq.talkwith.teams.TeamManagerClient;

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
    /**
     * The currently-open {@link GuiAIChat} instance, or {@code null} when the GUI is closed.
     * Set by {@link GuiAIChat} on open/close. Used by {@link com.czqwq.talkwith.network.PacketSessionBroadcast}
     * to route AI replies into the GUI rather than vanilla chat when the player has it open.
     */
    public static volatile GuiAIChat activeGui = null;

    /**
     * Whether AI I/O is routed through the vanilla chat HUD. Derived from
     * {@link Config#guiMode} so there is a single source of truth; never mutated directly.
     */
    public static boolean useVanillaGui() {
        return "vanilla".equals(Config.guiMode);
    }

    /**
     * Persistent chat history shared across all {@link GuiAIChat} instances.
     * Survives GUI open/close cycles so that closing the inventory (which closes the GUI)
     * does not discard the conversation. Capped at {@link #MAX_CHAT_HISTORY} entries.
     */
    public static final List<String> chatHistory = new CopyOnWriteArrayList<>();
    /** Maximum number of lines retained in {@link #chatHistory}. */
    public static final int MAX_CHAT_HISTORY = 500;

    private static final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();

    /** Latest known server session AI settings (multi mode), populated by {@code setting_get} responses. */
    public static final SessionSettings sessionSettings = new SessionSettings();

    /** Client-side cache of the active server session's AI settings (multi mode only). */
    public static class SessionSettings {

        public volatile String model = "";
        public volatile String baseUrl = "";
        public volatile boolean hasApiKey = false;
        public volatile String promptFile = "";
    }

    /** Stores the latest server session settings received from {@code setting_get}. */
    public static void storeSessionSettings(com.czqwq.talkwith.network.PacketSessionSettings s) {
        sessionSettings.model = s.model;
        sessionSettings.baseUrl = s.baseUrl;
        sessionSettings.hasApiKey = s.hasApiKey;
        sessionSettings.promptFile = s.promptFile;
    }

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
        // GUI mode is derived from Config.guiMode (single source of truth), no field to sync.
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        ClientCommandHandler.instance.registerCommand(new TalkWithCommand());
        // TeamManagerClient listens for FMLNetworkEvent.ClientDisconnectionFromServerEvent,
        // which is posted on the FML bus (not the Forge event bus).
        FMLCommonHandler.instance()
            .bus()
            .register(new TeamManagerClient());
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
        chatHistory.clear();
    }
}
