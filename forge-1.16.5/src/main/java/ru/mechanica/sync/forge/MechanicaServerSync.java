package ru.mechanica.sync.forge;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.util.SharedConstants;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.mechanica.sync.server.AutoConfig;
import ru.mechanica.sync.server.ManifestPublisher;
import ru.mechanica.sync.server.ServerConfig;

@Mod("mechanica_server_sync")
public final class MechanicaServerSync {
    private static final Logger LOGGER = LogManager.getLogger("MechanicaServerSync");
    private static ManifestPublisher publisher;

    public MechanicaServerSync() {
        MinecraftForge.EVENT_BUS.addListener(this::starting);
        MinecraftForge.EVENT_BUS.addListener(this::stopping);
    }

    private void starting(FMLServerStartingEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        Path configuration = FMLPaths.CONFIGDIR.get().resolve("mechanica-sync.json");
        if (!Files.exists(configuration)) {
            return;
        }
        try {
            String runtimeLoader = ModList.get().getModContainerById("forge").orElseThrow(() -> new IllegalStateException("missing_loader"))
                .getModInfo().getVersion().toString();
            ServerConfig config = AutoConfig.load(configuration, FMLPaths.GAMEDIR.get(),
                new AutoConfig.Runtime("forge", SharedConstants.getCurrentVersion().getName(), runtimeLoader), LOGGER::info);
            publisher = new ManifestPublisher(FMLPaths.GAMEDIR.get(), config, LOGGER::info);
            publisher.start();
            Runtime.getRuntime().addShutdownHook(new Thread(MechanicaServerSync::stop, "Mechanica sync shutdown"));
        } catch (Exception ex) {
            stop();
            LOGGER.error("MECHANICA_SYNC_SERVER_CONFIG_ERROR", ex);
        }
    }

    private void stopping(FMLServerStoppingEvent event) {
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

