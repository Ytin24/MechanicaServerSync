package ru.mechanica.sync.forge;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.client.event.GuiOpenEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.mechanica.sync.ipc.BridgeClient;
import ru.mechanica.sync.ipc.BridgeConfig;

public final class ClientBridge {
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

    public static void initialize() {
        try {
            config = BridgeConfig.fromEnvironment(System.getenv()).orElse(null);
            if (config != null) LOGGER.info("MECHANICA_SYNC_CLIENT_READY");
        } catch (IOException ex) {
            invalidEnvironment = true;
            LOGGER.warn("MECHANICA_SYNC_INVALID_ENVIRONMENT");
        }
    }

    public static boolean intercept(Screen connecting, Screen parent, String host, int port) {
        if (bypass || (config == null && !invalidEnvironment)) return false;
        if (active != null) active.cancel();
        active = new Attempt(connecting, parent, host, port);
        return true;
    }

    public static void open(GuiOpenEvent event) {
        Attempt attempt = active;
        if (attempt == null || event.getGui() != attempt.connecting) return;
        if (invalidEnvironment) {
            active = null;
            event.setGui(failure(attempt.parent));
            return;
        }
        event.setGui(new PreparationScreen(() -> {
            attempt.cancel();
            if (active == attempt) {
                active = null;
                attempt.client.setScreen(attempt.parent);
            }
        }));
        LOGGER.info("MECHANICA_SYNC_PREPARE");
        WORKERS.execute(attempt::prepare);
    }

    private static Screen failure(Screen parent) {
        return new DisconnectedScreen(parent, new StringTextComponent("Не удалось подготовить подключение"),
            new StringTextComponent("Проверь подключение и сообщение в Mechanica."));
    }

    private static final class Attempt {
        private final Minecraft client = Minecraft.getInstance();
        private final Screen connecting;
        private final Screen parent;
        private final String host;
        private final int port;
        private final BridgeClient bridge;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Attempt(Screen connecting, Screen parent, String host, int port) {
            this.connecting = connecting;
            this.parent = parent;
            this.host = host;
            this.port = port;
            this.bridge = config == null ? null : new BridgeClient(config);
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
                            client.setScreen(failure(parent));
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
                    bypass = true;
                    try {
                        client.setScreen(new ConnectingScreen(parent, client, host, port));
                    } finally {
                        bypass = false;
                    }
                    break;
                case CANCELLED:
                    client.setScreen(parent);
                    break;
                case RESTART_REQUIRED:
                    LOGGER.info("MECHANICA_SYNC_RESTART_REQUIRED");
                    client.stop();
                    break;
            }
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true) && bridge != null) {
                WORKERS.execute(() -> {
                    bridge.cancel();
                    bridge.cancelPlan();
                });
            }
        }
    }
}