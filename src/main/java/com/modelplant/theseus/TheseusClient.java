package com.modelplant.theseus;

import com.modelplant.theseus.client.ClientControlHandler;
import com.modelplant.theseus.client.ClientScreenshotHandler;
import com.modelplant.theseus.client.TheseusSessionScreen;
import com.modelplant.theseus.config.TheseusClientConfig;
import com.modelplant.theseus.network.ClientPacketHooks;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Theseus.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Theseus.MOD_ID, value = Dist.CLIENT)
public final class TheseusClient {
    public TheseusClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, TheseusClientConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        ClientPacketHooks.PLAYER_CONTROL = ClientControlHandler::handle;
        ClientPacketHooks.SCREENSHOT_REQUEST = ClientScreenshotHandler::handleRequest;
        NeoForge.EVENT_BUS.register(TheseusSessionScreen.KeybindHandler.class);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        Theseus.LOGGER.info("Theseus client ready ({})", Minecraft.getInstance().getUser().getName());
    }
}
