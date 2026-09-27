package ru.mechanica.sync.fabric;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.LiteralText;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import ru.mechanica.sync.fabric.mixin.ConnectScreenAccess;
import ru.mechanica.sync.ipc.BridgeClient;
import ru.mechanica.sync.ipc.BridgeConfig;

public final class ClientBridge implements ClientModInitializer {
    private static final Logger LOGGER = LogManager.getLogger("MechanicaServerSync");
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "Mechanica launcher bridge");
        thread.setDaemon(true);
        return thread;
    });
    private static BridgeConfig config;
    private static boolean invalidEnvironment;
    private static boolean bypass;
    private static Attempt active;

    @Override
    public void onInitializeClient() {
        try {
            config = BridgeConfig.fromEnvironment(System.getenv()).orElse(null);
            if (config != null) LOGGER.info("MECHANICA_SYNC_CLIENT_READY");
        } catch (IOException ex) {
            invalidEnvironment = true;
            LOGGER.warn("MECHANICA_SYNC_INVALID_ENVIRONMENT");
        }
    }

    public static boolean shouldIntercept() {
        if (bypass) {
            bypass = false;
            return false;
        }
        return config != null || invalidEnvironment;
    }

    public static void prepare(ConnectScreen source, Screen parent, String host, int port) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (active != null) active.cancel();
        if (invalidEnvironment) {
            showFailure(client, parent);
            return;
        }
        Attempt attempt = new Attempt(source, parent, host, port);
        active = attempt;
        client.openScreen(new PreparationScreen(() -> {
            attempt.cancel();
            if (active == attempt) {
                active = null;
                client.openScreen(parent);
            }
        }));
        LOGGER.info("MECHANICA_SYNC_PREPARE");
        WORKERS.execute(attempt::prepare);
    }

    private static void showFailure(MinecraftClient client, Screen parent) {
        client.openScreen(new DisconnectedScreen(parent, new LiteralText("Не удалось подготовить подключение"),
            new LiteralText("Проверь подключение и сообщение в Mechanica.")));
    }

    private static final class Attempt {
        private final ConnectScreen source;
        private final Screen parent;
        private final String host;
        private final int port;
        private final MinecraftClient client = MinecraftClient.getInstance();
        private final BridgeClient bridge = new BridgeClient(config);
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Attempt(ConnectScreen source, Screen parent, String host, int port) {
            this.source = source;
            this.parent = parent;
            this.host = host;
            this.port = port;
        }

        private void prepare() {
            try {
                BridgeClient.Outcome outcome = bridge.prepare(host, port);
                client.execute(() -> complete(outcome));
            } catch (IOException | RuntimeException ex) {
                if (!cancelled.get()) {
                    LOGGER.warn("MECHANICA_SYNC_PREPARATION_FAILED");
                    client.execute(() -> {
                        if (active == this && !cancelled.get()) {
                            active = null;
                            showFailure(client, parent);
                        }
                    });
                }
            }
        }

        private void complete(BridgeClient.Outcome outcome) {
            if (cancelled.get() || active != this) return;
            active = null;
            switch (outcome) {
                case READY:
                    LOGGER.info("MECHANICA_SYNC_CONNECT_READY");
                    client.openScreen(source);
                    bypass = true;
                    try {
                        ((ConnectScreenAccess) source).mechanica$connect(host, port);
                    } finally {
                        bypass = false;
                    }
                    break;
                case CANCELLED:
                    client.openScreen(parent);
                    break;
                case RESTART_REQUIRED:
                    LOGGER.info("MECHANICA_SYNC_RESTART_REQUIRED");
                    client.scheduleStop();
                    break;
            }
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) WORKERS.execute(() -> {
                bridge.cancel();
                bridge.cancelPlan();
            });
        }
    }
}
