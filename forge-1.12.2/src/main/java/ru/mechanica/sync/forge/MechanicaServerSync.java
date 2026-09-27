package ru.mechanica.sync.forge;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.mechanica.sync.server.ManifestPublisher;
import ru.mechanica.sync.server.ServerConfig;

@Mod(modid = "mechanica_server_sync", name = "Mechanica Server Sync", version = "1.0.0",
     acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public final class MechanicaServerSync {
    private static final Logger LOGGER = LogManager.getLogger("MechanicaServerSync");
    private static ManifestPublisher publisher;
    private Path gameDir;
    private Path configuration;

    @Mod.EventHandler
    public void initialize(FMLPreInitializationEvent event) {
        configuration = event.getModConfigurationDirectory().toPath().resolve("mechanica-sync.json");
        gameDir = event.getModConfigurationDirectory().toPath().getParent();
        if (event.getSide().isClient()) ClientBridge.initialize();
    }

    @Mod.EventHandler
    public void starting(FMLServerStartingEvent event) {
        if (!event.getServer().isDedicatedServer() || !Files.exists(configuration)) return;
        try {
            ServerConfig config = ServerConfig.read(configuration);
            if (!"forge".equals(config.loader()) || !ForgeVersion.getVersion().equals(config.loaderVersion())
                || !Loader.MC_VERSION.equals(config.minecraft())) {
                throw new IllegalStateException("server_target_mismatch");
            }
            publisher = new ManifestPublisher(gameDir, config, LOGGER::info);
            publisher.start();
            Runtime.getRuntime().addShutdownHook(new Thread(MechanicaServerSync::stop, "Mechanica sync shutdown"));
        } catch (Exception ex) {
            stop();
            LOGGER.error("MECHANICA_SYNC_SERVER_CONFIG_ERROR", ex);
        }
    }

    @Mod.EventHandler
    public void stopping(FMLServerStoppingEvent event) {
        stop();
    }

    private static synchronized void stop() {
        if (publisher != null) {
            publisher.close();
            publisher = null;
        }
    }
}
