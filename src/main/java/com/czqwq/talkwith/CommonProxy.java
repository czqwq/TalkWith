package com.czqwq.talkwith;

import com.czqwq.talkwith.command.TalkWithServerCommand;
import com.czqwq.talkwith.network.PacketHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.common.MinecraftForge;

import java.io.File;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        File configDir = new File(event.getSuggestedConfigurationFile().getParentFile(), "talkwith");
        configDir.mkdirs();
        Config.init(new File(configDir, "talkwith.cfg"));
    }

    public void init(FMLInitializationEvent event) {
        PacketHandler.init();
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new TalkWithServerCommand());
        MinecraftForge.EVENT_BUS.register(new ServerEventHandler());
    }
}
