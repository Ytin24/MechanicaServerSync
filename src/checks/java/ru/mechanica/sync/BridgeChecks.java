package ru.mechanica.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import ru.mechanica.sync.common.JsonCodec;
import ru.mechanica.sync.ipc.BridgeConfig;
import ru.mechanica.sync.ipc.BridgeProtocol;
import ru.mechanica.sync.ipc.FrameCodec;
import ru.mechanica.sync.server.ManifestPublisher;
import ru.mechanica.sync.server.ServerConfig;
import ru.mechanica.sync.server.StatusAdvertisement;
import ru.mechanica.sync.server.StatusAdvertisementChecks;

public final class BridgeChecks {
    private static int checks;

    public static void main(String[] args) throws Exception {
        framing();
        protocol();
        checks += StatusAdvertisementChecks.run();
        publisher();
        System.out.println("Mechanica bridge checks: " + checks + " passed");
    }

    private static void framing() throws Exception {
        JsonObject message = new JsonObject();
        message.addProperty("text", "Проверка");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FrameCodec.write(new DataOutputStream(buffer), message);
        byte[] bytes = buffer.toByteArray();
        int payloadLength = JsonCodec.encode(message).length;
        check((bytes[0] & 0xff) == payloadLength && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0, "little endian byte size");
        check(FrameCodec.read(new DataInputStream(new ByteArrayInputStream(bytes))).equals(message), "UTF8 roundtrip");
        fails(() -> FrameCodec.read(new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0, 0, 0}))), "zero frame");
        fails(() -> FrameCodec.read(new DataInputStream(new ByteArrayInputStream(new byte[]{1, 64, 0, 0}))), "oversize frame");
        fails(() -> FrameCodec.read(new DataInputStream(new ByteArrayInputStream(new byte[]{-1, -1, -1, -1}))), "unsigned overflow frame");
        fails(() -> FrameCodec.read(new DataInputStream(new ByteArrayInputStream(Arrays.copyOf(bytes, bytes.length - 1)))), "truncated frame");
        fails(() -> JsonCodec.decode("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8), 100), "duplicate JSON keys");
        fails(() -> JsonCodec.decode("{\"x\":NaN}".getBytes(StandardCharsets.UTF_8), 100), "invalid JSON number");
        fails(() -> JsonCodec.decode(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}'}, 100), "invalid UTF8");
        fails(() -> JsonCodec.decode(("{\"x\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}").getBytes(StandardCharsets.UTF_8), 100), "JSON depth");
        check(BridgeConfig.fromEnvironment(Map.of()).isEmpty(), "no environment does not intercept");
        fails(() -> BridgeConfig.fromEnvironment(Map.of("MECHANICA_BRIDGE_PIPE", "name")), "partial environment fails closed");
        fails(() -> BridgeConfig.fromEnvironment(Map.of("MECHANICA_BRIDGE_PIPE", "../name", "MECHANICA_BRIDGE_SESSION", UUID.randomUUID().toString(), "MECHANICA_BRIDGE_INSTANCE", "test", "MECHANICA_BRIDGE_TOKEN", "test")), "pipe traversal rejected");
    }

    private static void protocol() throws Exception {
        BridgeConfig config = new BridgeConfig("mechanica-test", UUID.randomUUID().toString(), "instance", "secret");
        BridgeProtocol protocol = new BridgeProtocol(config);
        check(!config.toString().contains("secret"), "configuration string redacts token");
        JsonObject hello = protocol.hello();
        check(JsonCodec.integer(hello, "bootstrapVersion") == 1 && "BridgeHello".equals(JsonCodec.string(hello, "type")), "bootstrap envelope");
        JsonObject ready = new JsonObject();
        ready.addProperty("type", "BridgeReady");
        ready.addProperty("protocolMajor", 1);
        ready.addProperty("protocolMinor", 0);
        ready.addProperty("sessionId", config.sessionId());
        ready.addProperty("instanceId", config.instanceId());
        protocol.validateReady(ready);
        check(true, "handshake binding");
        JsonObject wrongSession = ready.deepCopy();
        wrongSession.addProperty("sessionId", UUID.randomUUID().toString());
        fails(() -> protocol.validateReady(wrongSession), "cross session handshake");
        JsonObject request = protocol.request("PrepareConnection", new JsonObject());
        check(UUID.fromString(JsonCodec.string(request, "requestId")).version() == 4, "UUIDv4 request ID");
        Duration lifetime = Duration.between(Instant.now(), Instant.parse(JsonCodec.string(request, "expiresUtc")));
        check(lifetime.toSeconds() > 0 && lifetime.toMinutes() < 15, "bounded request expiry");
        check(!request.has("token"), "token only in handshake");
        JsonObject response = ready.deepCopy();
        response.addProperty("type", "Result");
        response.addProperty("requestId", JsonCodec.string(request, "requestId"));
        response.add("payload", new JsonObject());
        protocol.validateReply(request, response);
        check(true, "matching reply accepted");
        response.addProperty("requestId", UUID.randomUUID().toString());
        fails(() -> protocol.validateReply(request, response), "cross request reply");
    }

    private static void publisher() throws Exception {
        Path game = Files.createTempDirectory("mechanica-publisher-check-").toRealPath();
        try {
            Path pack = Files.createDirectory(game.resolve("client-pack"));
            Path approved = pack.resolve("fixture.jar");
            byte[] contents = "approved fixture bytes".getBytes(StandardCharsets.UTF_8);
            Files.write(approved, contents);
            Files.writeString(pack.resolve("unlisted.jar"), "not approved");
            int port;
            try (ServerSocket reservation = new ServerSocket(0)) {
                port = reservation.getLocalPort();
            }
            JsonObject configObject = config(port);
            ServerConfig config = ServerConfig.parse(configObject);
            check("127.0.0.1".equals(config.bindAddress()), "loopback bind default");
            for (String[] target : new String[][] {
                {"1.12.2", "forge", "14.23.5.2860"}, {"1.16.5", "fabric", "0.19.5"},
                {"1.21.1", "quilt", "0.30.1"}, {"1.21.1", "neoforge", "21.1.234"}, {"26.3", "fabric", "0.19.5"}
            }) {
                JsonObject variant = configObject.deepCopy();
                variant.addProperty("minecraft", target[0]);
                variant.addProperty("loader", target[1]);
                variant.addProperty("loaderVersion", target[2]);
                ServerConfig parsed = ServerConfig.parse(variant);
                check(parsed.minecraft().equals(target[0]) && parsed.loader().equals(target[1]) && parsed.loaderVersion().equals(target[2]),
                    "publisher preserves the administrator target without Minecraft API dependencies");
            }
            JsonObject traversal = configObject.deepCopy();
            traversal.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("filename", "../outside.jar");
            fails(() -> ServerConfig.parse(traversal), "path traversal rejected");
            JsonObject device = configObject.deepCopy();
            device.getAsJsonArray("files").get(0).getAsJsonObject().addProperty("filename", "CON.jar");
            fails(() -> ServerConfig.parse(device), "Windows device path rejected");
            JsonObject remoteHttp = configObject.deepCopy();
            remoteHttp.addProperty("baseUrl", "http://example.com");
            fails(() -> ServerConfig.parse(remoteHttp), "nonlocal HTTP rejected");
            JsonObject credentials = configObject.deepCopy();
            credentials.addProperty("baseUrl", "https://user:password@example.com");
            fails(() -> ServerConfig.parse(credentials), "URL credentials rejected");
            JsonObject expired = configObject.deepCopy();
            expired.addProperty("expiresUtc", Instant.now().minusSeconds(1).toString());
            fails(() -> ServerConfig.parse(expired), "expired manifest rejected");
            JsonObject missingDependency = configObject.deepCopy();
            missingDependency.getAsJsonArray("files").get(0).getAsJsonObject().getAsJsonArray("dependencies").add("missing");
            fails(() -> ServerConfig.parse(missingDependency), "dependency closure");
            CopyOnWriteArrayList<String> log = new CopyOnWriteArrayList<>();
            try (ManifestPublisher publisher = new ManifestPublisher(game, config, log::add);
                 HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                check(StatusAdvertisement.decorate("{}") == "{}", "unstarted publisher does not advertise");
                publisher.start();
                String base = "http://127.0.0.1:" + port;
                JsonObject advertised = JsonCodec.decode(StatusAdvertisement.decorate("{}").getBytes(StandardCharsets.UTF_8), 4096);
                check(JsonCodec.string(advertised.getAsJsonObject("mechanica"), "descriptorUrl").equals(base + "/mechanica/descriptor.json"),
                    "started publisher advertises its descriptor");
                try (ManifestPublisher conflicting = new ManifestPublisher(game, config, log::add)) {
                    fails(conflicting::start, "failed bind does not publish");
                    check(StatusAdvertisement.decorate("{}").equals(new String(JsonCodec.encode(advertised), StandardCharsets.UTF_8)),
                        "failed publisher does not remove running publisher advertisement");
                }
                HttpResponse<byte[]> descriptorResponse = get(http, base + "/mechanica/descriptor.json");
                check(descriptorResponse.statusCode() == 200, "descriptor served");
                JsonObject descriptor = JsonCodec.decode(descriptorResponse.body(), ServerConfig.MAXIMUM_JSON_BYTES);
                JsonObject target = descriptor.getAsJsonArray("protocols").get(0).getAsJsonObject().getAsJsonArray("targets").get(0).getAsJsonObject();
                HttpResponse<byte[]> manifestResponse = get(http, JsonCodec.string(target, "manifestUrl"));
                check(manifestResponse.statusCode() == 200 && ManifestPublisher.hash(manifestResponse.body()).equals(JsonCodec.string(target, "sha512")), "exact manifest bytes hash");
                JsonObject manifest = JsonCodec.decode(manifestResponse.body(), ServerConfig.MAXIMUM_JSON_BYTES);
                check(JsonCodec.string(manifest, "serverId").equals(config.serverId()) && JsonCodec.integer(manifest, "revision") == 1, "manifest identity");
                check(JsonCodec.string(manifest, "expiresUtc").equals(JsonCodec.string(configObject, "expiresUtc")), "configured expiry precision preserved");
                JsonArray files = manifest.getAsJsonArray("files");
                check(files.size() == 1, "only explicit client files published");
                JsonObject file = files.get(0).getAsJsonObject();
                String fileUrl = JsonCodec.string(file.getAsJsonObject("source"), "url");
                HttpResponse<byte[]> fileResponse = get(http, fileUrl);
                check(fileResponse.statusCode() == 200 && Arrays.equals(contents, fileResponse.body()), "approved file downloaded");
                check(JsonCodec.integer(file, "size") == contents.length && JsonCodec.string(file, "sha512").equals(ManifestPublisher.hash(fileResponse.body())), "file size and hash");
                check(get(http, base + "/mechanica/files/unlisted.jar").statusCode() == 404, "unlisted file denied");
                check(get(http, base + "/mechanica/%2e%2e/config/mechanica-sync.json").statusCode() == 404, "encoded traversal denied");
                check(get(http, base + "/mechanica/descriptor.json?path=client-pack/unlisted.jar").statusCode() == 404, "query cannot choose path");
                HttpResponse<byte[]> post = http.send(HttpRequest.newBuilder(URI.create(base + "/mechanica/descriptor.json")).timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
                check(post.statusCode() == 405, "POST denied");
                Files.write(approved, "changed fixture bytes!".getBytes(StandardCharsets.UTF_8));
                check(get(http, fileUrl).statusCode() == 409, "mutated approved file rejected");
                check(log.stream().filter(line -> line.startsWith("MECHANICA_SYNC_FILE_REQUEST")).count() == 1, "only valid served file logged");
                check(log.stream().anyMatch(line -> line.startsWith("MECHANICA_SYNC_SERVER_READY")), "server readiness marker");
            }
            check(StatusAdvertisement.decorate("{}") == "{}", "stopped publisher no longer advertises");
        } finally {
            try (var paths = Files.walk(game)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static JsonObject config(int port) {
        JsonObject config = new JsonObject();
        config.addProperty("serverId", UUID.randomUUID().toString());
        config.addProperty("revision", 1);
        config.addProperty("expiresUtc", new DateTimeFormatterBuilder().appendInstant(7).toFormatter().format(Instant.now().plusSeconds(3600)));
        config.addProperty("targetId", "fabric-1.21.1");
        config.addProperty("minecraft", "1.21.1");
        config.addProperty("loader", "fabric");
        config.addProperty("loaderVersion", "0.19.3");
        config.addProperty("baseUrl", "http://127.0.0.1:" + port);
        config.addProperty("port", port);
        JsonObject file = new JsonObject();
        file.addProperty("artifactId", "fixture");
        JsonArray ids = new JsonArray();
        ids.add("mechanica_sync_fixture");
        file.add("modIds", ids);
        file.addProperty("client", "required");
        file.addProperty("filename", "fixture.jar");
        file.add("dependencies", new JsonArray());
        JsonArray files = new JsonArray();
        files.add(file);
        config.add("files", files);
        return config;
    }

    private static HttpResponse<byte[]> get(HttpClient http, String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void fails(CheckedAction action, String description) throws Exception {
        try {
            action.run();
        } catch (IOException ex) {
            checks++;
            return;
        }
        throw new AssertionError(description);
    }

    private static void check(boolean valid, String description) {
        if (!valid) {
            throw new AssertionError(description);
        }
        checks++;
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
