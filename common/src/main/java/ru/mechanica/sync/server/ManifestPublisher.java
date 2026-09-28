package ru.mechanica.sync.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import ru.mechanica.sync.common.JsonCodec;

public final class ManifestPublisher implements AutoCloseable {
    private static final class PublishedFile {
        private final Path path;
        private final long size;
        private final String sha512, filename;

        private PublishedFile(Path path, long size, String sha512, String filename) {
            this.path = path;
            this.size = size;
            this.sha512 = sha512;
            this.filename = filename;
        }

        private Path path() { return path; }
        private long size() { return size; }
        private String sha512() { return sha512; }
        private String filename() { return filename; }
    }

    private final ServerConfig config;
    private final Path clientPack;
    private final byte[] descriptor;
    private final byte[] manifest;
    private final String manifestPath;
    private final Map<String, PublishedFile> files = new LinkedHashMap<>();
    private final Consumer<String> log;
    private HttpServer server;
    private ThreadPoolExecutor executor;
    private StatusAdvertisement.Registration statusRegistration;

    public ManifestPublisher(Path gameDirectory, ServerConfig config, Consumer<String> log) throws IOException {
        this.config = config;
        this.log = log;
        clientPack = gameDirectory.toRealPath().resolve("client-pack");
        if (!Files.isDirectory(clientPack, LinkOption.NOFOLLOW_LINKS)
            || !clientPack.toRealPath().equals(clientPack)) {
            throw new IOException("invalid_client_pack_directory");
        }
        JsonArray entries = new JsonArray();
        long total = 0;
        for (ServerConfig.ClientFile file : config.files()) {
            Path path = clientPack.resolve(file.filename());
            validatePath(path);
            long size;
            String sha512;
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                size = channel.size();
                if (size < 1 || size > ServerConfig.MAXIMUM_FILE_BYTES) {
                    throw new IOException("invalid_file_size");
                }
                sha512 = hash(channel);
                if (size != channel.size()) {
                    throw new IOException("client_file_changed");
                }
            }
            total += size;
            if (total > ServerConfig.MAXIMUM_TOTAL_BYTES) {
                throw new IOException("client_pack_too_large");
            }
            String filePath = "/mechanica/files/" + sha512 + "/" + file.filename();
            files.put(filePath, new PublishedFile(path, size, sha512, file.filename()));
            JsonObject entry = new JsonObject();
            entry.addProperty("artifactId", file.artifactId());
            entry.add("modIds", strings(file.modIds()));
            entry.addProperty("client", file.client());
            entry.addProperty("filename", file.filename());
            entry.addProperty("size", size);
            entry.addProperty("sha512", sha512);
            entry.add("dependencies", strings(file.dependencies()));
            JsonObject source = new JsonObject();
            source.addProperty("type", "external");
            source.addProperty("url", config.url(filePath));
            entry.add("source", source);
            entries.add(entry);
        }
        JsonObject manifestObject = target();
        manifestObject.addProperty("type", "Manifest");
        manifestObject.addProperty("protocolMajor", 1);
        manifestObject.addProperty("protocolMinor", 0);
        manifestObject.addProperty("serverId", config.serverId());
        manifestObject.addProperty("revision", config.revision());
        manifestObject.addProperty("expiresUtc", config.expiresUtc());
        manifestObject.add("files", entries);
        manifest = JsonCodec.encode(manifestObject);
        if (manifest.length > ServerConfig.MAXIMUM_JSON_BYTES) {
            throw new IOException("manifest_too_large");
        }
        manifestPath = "/mechanica/manifests/" + config.revision() + ".json";
        JsonObject target = target();
        target.addProperty("manifestUrl", config.url(manifestPath));
        target.addProperty("sha512", hash(manifest));
        JsonArray targets = new JsonArray();
        targets.add(target);
        JsonObject protocol = new JsonObject();
        protocol.addProperty("major", 1);
        protocol.addProperty("minor", 0);
        JsonArray capabilities = new JsonArray();
        capabilities.add("mods-v1");
        protocol.add("requiredCapabilities", capabilities);
        protocol.add("targets", targets);
        JsonArray protocols = new JsonArray();
        protocols.add(protocol);
        JsonObject descriptorObject = new JsonObject();
        descriptorObject.addProperty("descriptorVersion", 1);
        descriptorObject.addProperty("serverId", config.serverId());
        descriptorObject.add("protocols", protocols);
        descriptor = JsonCodec.encode(descriptorObject);
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("publisher_already_started");
        }
        executor = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "Mechanica sync HTTP");
            thread.setDaemon(true);
            return thread;
        });
        try {
            server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 32);
            server.setExecutor(executor);
            server.createContext("/", this::handle);
            server.start();
            statusRegistration = StatusAdvertisement.register(config.url("/mechanica/descriptor.json"));
            log.accept("MECHANICA_SYNC_SERVER_READY url=" + config.url("/mechanica/descriptor.json"));
        } catch (IOException | RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!Instant.parse(config.expiresUtc()).isAfter(Instant.now())) {
                exchange.sendResponseHeaders(410, -1);
                return;
            }
            if ("/mechanica/descriptor.json".equals(path)) {
                sendJson(exchange, descriptor);
            } else if (manifestPath.equals(path)) {
                sendJson(exchange, manifest);
            } else if (files.containsKey(path)) {
                sendFile(exchange, files.get(path));
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } finally {
            exchange.close();
        }
    }

    private static void sendJson(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("ETag", "\"" + hash(body) + "\"");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private void sendFile(HttpExchange exchange, PublishedFile file) throws IOException {
        SeekableByteChannel opened;
        try {
            validatePath(file.path());
            opened = Files.newByteChannel(file.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            exchange.sendResponseHeaders(409, -1);
            return;
        }
        try (SeekableByteChannel channel = opened) {
            if (channel.size() != file.size() || !file.sha512().equals(hash(channel))) {
                exchange.sendResponseHeaders(409, -1);
                return;
            }
            channel.position(0);
            exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
            exchange.getResponseHeaders().set("ETag", "\"" + file.sha512() + "\"");
            exchange.sendResponseHeaders(200, file.size());
            log.accept("MECHANICA_SYNC_FILE_REQUEST filename=" + file.filename());
            try (OutputStream output = exchange.getResponseBody()) {
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                long remaining = file.size();
                while (remaining > 0) {
                    buffer.clear();
                    buffer.limit((int) Math.min(buffer.capacity(), remaining));
                    int read = channel.read(buffer);
                    if (read < 0) {
                        throw new IOException("client_file_changed");
                    }
                    output.write(buffer.array(), 0, read);
                    remaining -= read;
                }
            }
        }
    }

    private void validatePath(Path path) throws IOException {
        if (!path.getParent().equals(clientPack) || !clientPack.toRealPath().equals(clientPack)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("invalid_client_file");
        }
    }

    private JsonObject target() {
        JsonObject target = new JsonObject();
        target.addProperty("targetId", config.targetId());
        target.addProperty("minecraft", config.minecraft());
        target.addProperty("loader", config.loader());
        target.addProperty("loaderVersion", config.loaderVersion());
        return target;
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    public static String hash(byte[] bytes) {
        return hex(digest().digest(bytes));
    }

    private static String hash(SeekableByteChannel channel) throws IOException {
        MessageDigest digest = digest();
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        while (channel.read(buffer) >= 0) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        char[] result = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = digits[value >>> 4];
            result[index * 2 + 1] = digits[value & 15];
        }
        return new String(result);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public synchronized void close() {
        StatusAdvertisement.unregister(statusRegistration);
        statusRegistration = null;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }
}
