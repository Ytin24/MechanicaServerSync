package ru.mechanica.sync.forge;

import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = "mechanica_server_sync", value = Side.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void open(GuiOpenEvent event) { ClientBridge.open(event); }
}

