package ru.mechanica.sync.forge;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mechanica.sync.ipc.BridgeClient;
import ru.mechanica.sync.ipc.BridgeConfig;

public final class ClientBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("MechanicaServerSync");
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
            if (config != null) {
                LOGGER.info("MECHANICA_SYNC_CLIENT_READY");
            }
        } catch (IOException ex) {
            invalidEnvironment = true;
            LOGGER.warn("MECHANICA_SYNC_INVALID_ENVIRONMENT");
        }
    }

    public static boolean intercept(Screen parent, Minecraft client, ServerAddress address,
                                    ServerData info, boolean quickPlay) {
        if (bypass) {
            bypass = false;
            return false;
        }
        if (config == null && !invalidEnvironment) {
            return false;
        }
        if (active != null) {
            active.cancel();
        }
        if (invalidEnvironment) {
            showFailure(client, parent);
            return true;
        }
        Attempt attempt = new Attempt(parent, client, address, info, quickPlay);
        active = attempt;
        client.setScreen(new PreparationScreen(() -> {
            attempt.cancel();
            if (active == attempt) {
                active = null;
                client.setScreen(parent);
            }
        }));
        LOGGER.info("MECHANICA_SYNC_PREPARE");
        WORKERS.execute(attempt::prepare);
        return true;
    }

    private static void showFailure(Minecraft client, Screen parent) {
        client.setScreen(new DisconnectedScreen(parent, Component.literal("Не удалось подготовить подключение"),
            Component.literal("Проверь подключение и сообщение в Mechanica.")));
    }

    private static final class Attempt {
        private final Screen parent;
        private final Minecraft client;
        private final ServerAddress address;
        private final ServerData info;
        private final boolean quickPlay;
        private final BridgeClient bridge = new BridgeClient(config);
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Attempt(Screen parent, Minecraft client, ServerAddress address,
                        ServerData info, boolean quickPlay) {
            this.parent = parent;
            this.client = client;
            this.address = address;
            this.info = info;
            this.quickPlay = quickPlay;
        }

        private void prepare() {
            try {
                BridgeClient.Outcome outcome = bridge.prepare(address.getHost(), address.getPort());
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
            if (cancelled.get() || active != this) {
                return;
            }
            active = null;
            switch (outcome) {
                case READY -> {
                    LOGGER.info("MECHANICA_SYNC_CONNECT_READY");
                    bypass = true;
                    try {
                        ConnectScreen.startConnecting(parent, client, address, info, quickPlay);
                    } finally {
                        bypass = false;
                    }
                }
                case CANCELLED -> client.setScreen(parent);
                case RESTART_REQUIRED -> {
                    LOGGER.info("MECHANICA_SYNC_RESTART_REQUIRED");
                    client.stop();
                }
            }
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                WORKERS.execute(() -> {
                    bridge.cancel();
                    bridge.cancelPlan();
                });
            }
        }
    }
}
