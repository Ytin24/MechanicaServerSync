package ru.mechanica.sync.ipc;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.mechanica.sync.common.JsonCodec;

public final class BridgeClient {
    public enum Outcome { READY, CANCELLED, RESTART_REQUIRED }

    private final BridgeConfig config;
    private final BridgeProtocol protocol;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile PipeChannel channel;
    private volatile String planId;

    public BridgeClient(BridgeConfig config) {
        this.config = config;
        this.protocol = new BridgeProtocol(config);
    }

    public Outcome prepare(String host, int port) throws IOException {
        if (host == null || host.trim().isEmpty() || host.length() > 253 || port < 1 || port > 65535) {
            throw new IOException("invalid_server_address");
        }
        try (PipeChannel opened = PipeChannel.connect(config, cancelled::get, Duration.ofMinutes(15))) {
            channel = opened;
            if (cancelled.get()) {
                return Outcome.CANCELLED;
            }
            protocol.validateReady(opened.exchange(protocol.hello()));
            JsonObject target = new JsonObject();
            target.addProperty("host", host);
            target.addProperty("port", port);
            target.addProperty("knownRevision", 0);
            JsonObject request = protocol.request("PrepareConnection", target);
            JsonObject response = opened.exchange(request);
            JsonObject payload = protocol.validateReply(request, response);
            if ("Result".equals(JsonCodec.string(response, "type"))) {
                return outcome(payload);
            }
            planId = JsonCodec.string(payload, "planId");
            if (planId.trim().isEmpty() || planId.length() > 256) {
                throw new IOException("invalid_plan");
            }
            if (cancelled.get()) {
                cancelPlan();
                return Outcome.CANCELLED;
            }
            JsonObject apply = protocol.request("Apply", planPayload());
            JsonObject result = opened.exchange(apply);
            JsonObject resultPayload = protocol.validateReply(apply, result);
            if (!"Result".equals(JsonCodec.string(result, "type"))) {
                throw new IOException("invalid_bridge_reply");
            }
            return cancelled.get() ? Outcome.CANCELLED : outcome(resultPayload);
        } finally {
            channel = null;
        }
    }

    public void cancel() {
        cancelled.set(true);
        PipeChannel opened = channel;
        if (opened != null) {
            opened.close();
        }
    }

    public void cancelPlan() {
        try (PipeChannel cancellation = PipeChannel.connect(config, () -> false, Duration.ofSeconds(10))) {
            protocol.validateReady(cancellation.exchange(protocol.hello()));
            JsonObject request = protocol.request("Cancel", planPayload());
            protocol.validateReply(request, cancellation.exchange(request));
        } catch (IOException ignored) { }
    }

    private JsonObject planPayload() {
        JsonObject payload = new JsonObject();
        if (planId != null) {
            payload.addProperty("planId", planId);
        }
        return payload;
    }

    private static Outcome outcome(JsonObject payload) throws IOException {
        switch (JsonCodec.string(payload, "status")) {
            case "ready": return Outcome.READY;
            case "cancelled": return Outcome.CANCELLED;
            case "restart_required": return Outcome.RESTART_REQUIRED;
            default: throw new IOException("server_preparation_failed");
        }
    }
}
