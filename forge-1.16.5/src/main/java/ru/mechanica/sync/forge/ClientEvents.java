package ru.mechanica.sync.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "mechanica_server_sync", value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void open(GuiOpenEvent event) { ClientBridge.open(event); }
}
