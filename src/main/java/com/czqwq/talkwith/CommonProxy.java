package com.czqwq.talkwith;

import java.io.File;

import net.minecraftforge.common.MinecraftForge;

import com.czqwq.talkwith.ai.SessionPersistence;
import com.czqwq.talkwith.network.PacketHandler;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        File configDir = new File(
            event.getSuggestedConfigurationFile()
                .getParentFile(),
            "talkwith");
        configDir.mkdirs();
        Config.init(new File(configDir, "talkwith.cfg"));
        SessionPersistence.init(new File(configDir, "sessions"));
    }

    public void init(FMLInitializationEvent event) {
        PacketHandler.init();
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        // Session restoration happens in ServerEventHandler.onWorldLoad (WorldEvent.Load for dim 0).
        // SessionPersistence.init() is still called in preInit for the migration fallback path.
        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
    }
}
