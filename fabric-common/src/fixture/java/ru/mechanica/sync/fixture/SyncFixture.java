package ru.mechanica.sync.fixture;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public final class SyncFixture implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("MechanicaSyncFixture");

    @Override
    public void onInitialize() {
        LOGGER.info("MECHANICA_SYNC_FIXTURE_INIT side={}", FabricLoader.getInstance().getEnvironmentType().name());
    }
}
