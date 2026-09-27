package ru.mechanica.sync.fixture;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = "mechanica_sync_fixture", value = Dist.CLIENT)
public final class ClientFixture {
    @SubscribeEvent
    public static void joined(ClientPlayerNetworkEvent.LoggingIn event) {
        SyncFixture.LOGGER.info("MECHANICA_SYNC_FIXTURE_CLIENT_JOIN");
        if (Boolean.getBoolean("mechanica.smoke")) {
            Thread exit = new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    Minecraft client = Minecraft.getInstance();
                    client.execute(client::stop);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, "Mechanica smoke exit");
            exit.setDaemon(true);
            exit.start();
        }
    }
}

