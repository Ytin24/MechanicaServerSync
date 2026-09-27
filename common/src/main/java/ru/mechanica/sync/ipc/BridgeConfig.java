package ru.mechanica.sync.ipc;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BridgeConfig {
    private final String pipe;
    private final String sessionId;
    private final String instanceId;
    private final String token;

    public BridgeConfig(String pipe, String sessionId, String instanceId, String token) {
        this.pipe = pipe;
        this.sessionId = sessionId;
        this.instanceId = instanceId;
        this.token = token;
    }

    public String pipe() { return pipe; }
    public String sessionId() { return sessionId; }
    public String instanceId() { return instanceId; }
    public String token() { return token; }
    public static Optional<BridgeConfig> fromEnvironment(Map<String, String> environment) throws IOException {
        String pipe = environment.get("MECHANICA_BRIDGE_PIPE");
        String session = environment.get("MECHANICA_BRIDGE_SESSION");
        String instance = environment.get("MECHANICA_BRIDGE_INSTANCE");
        String token = environment.get("MECHANICA_BRIDGE_TOKEN");
        if (pipe == null && session == null && instance == null && token == null) {
            return Optional.empty();
        }
        if (pipe == null || !pipe.matches("[A-Za-z0-9._-]{1,200}") || session == null
            || instance == null || instance.trim().isEmpty() || instance.length() > 256
            || token == null || token.trim().isEmpty() || token.length() > 2048) {
            throw new IOException("invalid_bridge_environment");
        }
        try {
            if (!UUID.fromString(session).toString().equalsIgnoreCase(session)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid_bridge_environment");
        }
        return Optional.of(new BridgeConfig(pipe, session, instance, token));
    }

    @Override
    public String toString() {
        return "BridgeConfig";
    }
}
