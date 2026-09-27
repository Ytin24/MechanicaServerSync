package ru.mechanica.sync.fixture.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.fixture.SyncFixture;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void mechanica$clientJoin(ClientboundLoginPacket packet, CallbackInfo callback) {
        SyncFixture.LOGGER.info("MECHANICA_SYNC_FIXTURE_CLIENT_JOIN");
        if (Boolean.getBoolean("mechanica.smoke")) {
            Minecraft client = Minecraft.getInstance();
            Thread stop = new Thread(() -> {
                try {
                    Thread.sleep(2000);
                    client.execute(client::stop);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, "Mechanica smoke exit");
            stop.setDaemon(true);
            stop.start();
        }
    }
}
