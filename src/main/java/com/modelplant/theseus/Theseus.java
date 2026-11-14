package com.modelplant.theseus;

import com.modelplant.theseus.bootstrap.TheseusBootstrap;
import com.modelplant.theseus.config.TheseusServerConfig;
import com.modelplant.theseus.network.TheseusNetwork;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(Theseus.MOD_ID)
public final class Theseus {
    public static final String MOD_ID = "theseus";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Theseus(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(TheseusNetwork::register);
        // COMMON (config/theseus-common.toml): survives world switches. SERVER would be
        // per-save / world-scoped and force re-configuring provider+model each entry.
        modContainer.registerConfig(ModConfig.Type.COMMON, TheseusServerConfig.SPEC);
        NeoForge.EVENT_BUS.register(TheseusBootstrap.class);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(TheseusBootstrap::initCommon);
        LOGGER.info("Theseus common setup complete");
    }
}
