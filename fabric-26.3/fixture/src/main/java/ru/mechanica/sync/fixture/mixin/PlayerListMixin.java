package ru.mechanica.sync.fixture.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.fixture.SyncFixture;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void mechanica$serverJoin(Connection connection, ServerPlayer player,
                                      CommonListenerCookie clientData, CallbackInfo callback) {
        SyncFixture.LOGGER.info("MECHANICA_SYNC_FIXTURE_SERVER_JOIN");
    }
}
