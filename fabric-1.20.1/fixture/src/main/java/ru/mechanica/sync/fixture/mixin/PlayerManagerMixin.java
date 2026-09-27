package ru.mechanica.sync.fixture.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.fixture.SyncFixture;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {
    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void mechanica$serverJoin(ClientConnection connection, ServerPlayerEntity player,
                                      CallbackInfo callback) {
        SyncFixture.LOGGER.info("MECHANICA_SYNC_FIXTURE_SERVER_JOIN");
    }
}
