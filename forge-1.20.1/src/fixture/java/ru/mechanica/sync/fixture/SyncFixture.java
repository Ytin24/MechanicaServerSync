package ru.mechanica.sync.fixture;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("mechanica_sync_fixture")
public final class SyncFixture {
    public static final Logger LOGGER = LogManager.getLogger("MechanicaSyncFixture");

    public SyncFixture() {
        LOGGER.info("MECHANICA_SYNC_FIXTURE_INIT side={}",
            FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT ? "CLIENT" : "SERVER");
        MinecraftForge.EVENT_BUS.addListener(this::joined);
    }

    private void joined(PlayerEvent.PlayerLoggedInEvent event) {
        LOGGER.info("MECHANICA_SYNC_FIXTURE_SERVER_JOIN");
    }
}
