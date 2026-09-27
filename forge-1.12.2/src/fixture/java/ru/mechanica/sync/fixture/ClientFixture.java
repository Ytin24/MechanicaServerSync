package ru.mechanica.sync.fixture;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = "mechanica_sync_fixture", value = Side.CLIENT)
public final class ClientFixture {
    private static boolean connected;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.getMinecraft();
        boolean joined = client.world != null && client.player != null;
        if (!joined) {
            connected = false;
            return;
        }
        if (connected) return;
        connected = true;
        SyncFixture.LOGGER.info("MECHANICA_SYNC_FIXTURE_CLIENT_JOIN");
        if (Boolean.getBoolean("mechanica.smoke")) {
            Thread exit = new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    client.addScheduledTask(client::shutdown);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, "Mechanica smoke exit");
            exit.setDaemon(true);
            exit.start();
        }
    }
}

