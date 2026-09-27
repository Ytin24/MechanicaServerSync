package ru.mechanica.sync.ipc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import ru.mechanica.sync.common.JsonCodec;

public final class BridgeProtocol {
    private final BridgeConfig config;

    public BridgeProtocol(BridgeConfig config) {
        this.config = config;
    }

    public JsonObject hello() {
        JsonObject hello = binding("BridgeHello");
        hello.addProperty("bootstrapVersion", 1);
        hello.addProperty("token", config.token());
        JsonObject version = new JsonObject();
        version.addProperty("major", 1);
        version.addProperty("minor", 0);
        JsonArray protocols = new JsonArray();
        protocols.add(version);
        hello.add("protocols", protocols);
        return hello;
    }

    public void validateReady(JsonObject ready) throws IOException {
        validateBinding(ready);
        if (!"BridgeReady".equals(JsonCodec.string(ready, "type"))) {
            throw new IOException("bridge_handshake_failed");
        }
    }

    public JsonObject request(String type, JsonObject payload) {
        JsonObject request = binding(type);
        request.addProperty("protocolMajor", 1);
        request.addProperty("protocolMinor", 0);
        request.addProperty("requestId", UUID.randomUUID().toString());
        request.addProperty("expiresUtc", Instant.now().plusSeconds(14 * 60).toString());
        request.add("payload", payload);
        return request;
    }

    public JsonObject validateReply(JsonObject request, JsonObject response) throws IOException {
        validateBinding(response);
        if (!JsonCodec.string(request, "requestId").equals(JsonCodec.string(response, "requestId"))) {
            throw new IOException("bridge_reply_mismatch");
        }
        String type = JsonCodec.string(response, "type");
        if (!"Plan".equals(type) && !"Result".equals(type)) {
            throw new IOException("invalid_bridge_reply");
        }
        return JsonCodec.object(response, "payload");
    }

    private JsonObject binding(String type) {
        JsonObject object = new JsonObject();
        object.addProperty("type", type);
        object.addProperty("sessionId", config.sessionId());
        object.addProperty("instanceId", config.instanceId());
        return object;
    }

    private void validateBinding(JsonObject response) throws IOException {
        if (JsonCodec.integer(response, "protocolMajor") != 1 || JsonCodec.integer(response, "protocolMinor") != 0
            || !config.sessionId().equals(JsonCodec.string(response, "sessionId"))
            || !config.instanceId().equals(JsonCodec.string(response, "instanceId"))) {
            throw new IOException("bridge_session_mismatch");
        }
    }
}
