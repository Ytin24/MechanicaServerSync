package ru.mechanica.sync.fixture;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("mechanica_sync_fixture")
public final class SyncFixture {
    public static final Logger LOGGER = LogManager.getLogger("MechanicaSyncFixture");

    public SyncFixture() {
        LOGGER.info("MECHANICA_SYNC_FIXTURE_INIT side={}",
            FMLEnvironment.getDist() == net.neoforged.api.distmarker.Dist.CLIENT ? "CLIENT" : "SERVER");
        NeoForge.EVENT_BUS.addListener(this::joined);
    }

    private void joined(PlayerEvent.PlayerLoggedInEvent event) {
        LOGGER.info("MECHANICA_SYNC_FIXTURE_SERVER_JOIN");
    }
}
