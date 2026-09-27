package ru.mechanica.sync.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = "mechanica_server_sync", value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    public static void initialize(FMLClientSetupEvent event) {
        ClientBridge.initialize();
    }
}

