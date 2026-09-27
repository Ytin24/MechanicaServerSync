package ru.mechanica.sync.fixture;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = "mechanica_sync_fixture", name = "Mechanica Server Sync Fixture", version = "1.0.0",
     acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public final class SyncFixture {
    public static final Logger LOGGER = LogManager.getLogger("MechanicaSyncFixture");

    @Mod.EventHandler
    public void initialize(FMLPreInitializationEvent event) {
        LOGGER.info("MECHANICA_SYNC_FIXTURE_INIT side={}", event.getSide().name());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void joined(PlayerEvent.PlayerLoggedInEvent event) {
        LOGGER.info("MECHANICA_SYNC_FIXTURE_SERVER_JOIN");
    }
}

