package ru.mechanica.sync.forge;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextComponentString;
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

    public static boolean intercept(Object connecting, String host, int port) {
        if (bypass || (config == null && !invalidEnvironment)) return false;
        GuiScreen parent = net.minecraftforge.fml.relauncher.ReflectionHelper.getPrivateValue(
            GuiConnecting.class, (GuiConnecting) connecting, "previousGuiScreen", "field_146374_i");
        if (active != null) active.cancel();
        active = new Attempt((GuiScreen) connecting, parent, host, port);
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
                attempt.client.displayGuiScreen(attempt.parent);
            }
        }));
        LOGGER.info("MECHANICA_SYNC_PREPARE");
        WORKERS.execute(attempt::prepare);
    }

    private static GuiScreen failure(GuiScreen parent) {
        return new GuiDisconnected(parent, "Не удалось подготовить подключение",
            new TextComponentString("Проверь подключение и сообщение в Mechanica."));
    }

    private static final class Attempt {
        private final Minecraft client = Minecraft.getMinecraft();
        private final GuiScreen connecting;
        private final GuiScreen parent;
        private final String host;
        private final int port;
        private final BridgeClient bridge;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Attempt(GuiScreen connecting, GuiScreen parent, String host, int port) {
            this.connecting = connecting;
            this.parent = parent;
            this.host = host;
            this.port = port;
            this.bridge = config == null ? null : new BridgeClient(config);
        }

        private void prepare() {
            try {
                BridgeClient.Outcome outcome = bridge.prepare(host, port);
                client.addScheduledTask(() -> complete(outcome));
            } catch (IOException | RuntimeException ex) {
                if (!cancelled.get()) {
                    LOGGER.warn("MECHANICA_SYNC_PREPARATION_FAILED");
                    client.addScheduledTask(() -> {
                        if (active == this && !cancelled.get()) {
                            active = null;
                            client.displayGuiScreen(failure(parent));
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
                        client.displayGuiScreen(new GuiConnecting(parent, client, host, port));
                    } finally {
                        bypass = false;
                    }
                    break;
                case CANCELLED:
                    client.displayGuiScreen(parent);
                    break;
                case RESTART_REQUIRED:
                    LOGGER.info("MECHANICA_SYNC_RESTART_REQUIRED");
                    client.shutdown();
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
