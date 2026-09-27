package ru.mechanica.sync.fixture.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.fixture.SyncFixture;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onGameJoin", at = @At("TAIL"))
    private void mechanica$clientJoin(GameJoinS2CPacket packet, CallbackInfo callback) {
        SyncFixture.LOGGER.info("MECHANICA_SYNC_FIXTURE_CLIENT_JOIN");
        if (Boolean.getBoolean("mechanica.smoke")) {
            MinecraftClient client = MinecraftClient.getInstance();
            Thread stop = new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    client.execute(client::scheduleStop);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, "Mechanica smoke exit");
            stop.setDaemon(true);
            stop.start();
        }
    }
}
