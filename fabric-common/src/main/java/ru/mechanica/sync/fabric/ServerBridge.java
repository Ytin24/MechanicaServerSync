package ru.mechanica.sync.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import java.util.Optional;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import ru.mechanica.sync.server.AutoConfig;
import ru.mechanica.sync.server.ManifestPublisher;
import ru.mechanica.sync.server.ServerConfig;

public final class ServerBridge implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("MechanicaServerSync");
    private static ManifestPublisher publisher;

    @Override
    public void onInitializeServer() {
        FabricLoader loader = FabricLoader.getInstance();
        Path configuration = loader.getConfigDir().resolve("mechanica-sync.json");
        if (!Files.exists(configuration)) {
            return;
        }
        try {
            Optional<ModContainer> quilt = loader.getModContainer("quilt_loader");
            String loaderName = quilt.isPresent() ? "quilt" : "fabric";
            ModContainer runtimeLoader = quilt.orElseGet(() -> loader.getModContainer("fabricloader").orElseThrow(() -> new IllegalStateException("loader_missing")));
            String currentLoader = runtimeLoader
                .getMetadata().getVersion().getFriendlyString();
            String currentMinecraft = loader.getModContainer("minecraft").orElseThrow(() -> new IllegalStateException("minecraft_missing"))
                .getMetadata().getVersion().getFriendlyString();
            ServerConfig config = AutoConfig.load(configuration, loader.getGameDir(),
                new AutoConfig.Runtime(loaderName, currentMinecraft, currentLoader), LOGGER::info);
            publisher = new ManifestPublisher(loader.getGameDir(), config, LOGGER::info);
            publisher.start();
            Runtime.getRuntime().addShutdownHook(new Thread(ServerBridge::stop, "Mechanica sync shutdown"));
        } catch (Exception ex) {
            stop();
            LOGGER.error("MECHANICA_SYNC_SERVER_CONFIG_ERROR", ex);
        }
    }

    public static synchronized void stop() {
        if (publisher != null) {
            publisher.close();
            publisher = null;
        }
    }
}
