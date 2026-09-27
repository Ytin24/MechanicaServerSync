package ru.mechanica.sync.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.mechanica.sync.server.ManifestPublisher;
import ru.mechanica.sync.server.ServerConfig;

@Mod("mechanica_server_sync")
public final class MechanicaServerSync {
    private static final Logger LOGGER = LogManager.getLogger("MechanicaServerSync");
    private static ManifestPublisher publisher;

    public MechanicaServerSync() {
        NeoForge.EVENT_BUS.addListener(this::starting);
        NeoForge.EVENT_BUS.addListener(this::stopping);
    }

    private void starting(ServerStartingEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        Path configuration = FMLPaths.CONFIGDIR.get().resolve("mechanica-sync.json");
        if (!Files.exists(configuration)) {
            return;
        }
        try {
            ServerConfig config = ServerConfig.read(configuration);
            String runtimeLoader = ModList.get().getModContainerById("neoforge").orElseThrow()
                .getModInfo().getVersion().toString();
            if (!"neoforge".equals(config.loader()) || !runtimeLoader.equals(config.loaderVersion())
                || !SharedConstants.getCurrentVersion().name().equals(config.minecraft())) {
                throw new IllegalStateException("server_target_mismatch");
            }
            publisher = new ManifestPublisher(FMLPaths.GAMEDIR.get(), config, LOGGER::info);
            publisher.start();
            Runtime.getRuntime().addShutdownHook(new Thread(MechanicaServerSync::stop, "Mechanica sync shutdown"));
        } catch (Exception ex) {
            stop();
            LOGGER.error("MECHANICA_SYNC_SERVER_CONFIG_ERROR", ex);
        }
    }

    private void stopping(ServerStoppingEvent event) {
        if (event.getServer().isDedicatedServer()) {
            stop();
        }
    }

    private static synchronized void stop() {
        if (publisher != null) {
            publisher.close();
            publisher = null;
        }
    }
}

