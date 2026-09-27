package ru.mechanica.sync.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import ru.mechanica.sync.common.JsonCodec;

public final class ServerConfig {
    private final String serverId, expiresUtc, targetId, minecraft, loader, loaderVersion, bindAddress;
    private final long revision;
    private final URI baseUrl;
    private final int port;
    private final List<ClientFile> files;

    public ServerConfig(String serverId, long revision, String expiresUtc, String targetId,
                        String minecraft, String loader, String loaderVersion, URI baseUrl,
                        String bindAddress, int port, List<ClientFile> files) {
        this.serverId = serverId;
        this.revision = revision;
        this.expiresUtc = expiresUtc;
        this.targetId = targetId;
        this.minecraft = minecraft;
        this.loader = loader;
        this.loaderVersion = loaderVersion;
        this.baseUrl = baseUrl;
        this.bindAddress = bindAddress;
        this.port = port;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    public String serverId() { return serverId; }
    public long revision() { return revision; }
    public String expiresUtc() { return expiresUtc; }
    public String targetId() { return targetId; }
    public String minecraft() { return minecraft; }
    public String loader() { return loader; }
    public String loaderVersion() { return loaderVersion; }
    public URI baseUrl() { return baseUrl; }
    public String bindAddress() { return bindAddress; }
    public int port() { return port; }
    public List<ClientFile> files() { return files; }

    public static final int MAXIMUM_JSON_BYTES = 1024 * 1024;
    public static final long MAXIMUM_FILE_BYTES = 512L * 1024 * 1024;
    public static final long MAXIMUM_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;

    public static final class ClientFile {
        private final String artifactId, client, filename;
        private final List<String> modIds, dependencies;

        public ClientFile(String artifactId, List<String> modIds, String client, String filename, List<String> dependencies) {
            this.artifactId = artifactId;
            this.modIds = Collections.unmodifiableList(new ArrayList<>(modIds));
            this.client = client;
            this.filename = filename;
            this.dependencies = Collections.unmodifiableList(new ArrayList<>(dependencies));
        }

        public String artifactId() { return artifactId; }
        public List<String> modIds() { return modIds; }
        public String client() { return client; }
        public String filename() { return filename; }
        public List<String> dependencies() { return dependencies; }
    }

    public static ServerConfig read(Path configPath) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(configPath)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAXIMUM_JSON_BYTES) {
                    throw new IOException("invalid_json_size");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }
        return parse(JsonCodec.decode(bytes, MAXIMUM_JSON_BYTES));
    }

    public static ServerConfig parse(JsonObject config) throws IOException {
        String serverId = JsonCodec.string(config, "serverId");
        try {
            if (!UUID.fromString(serverId).toString().equalsIgnoreCase(serverId)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid_server_id");
        }
        long revision = JsonCodec.integer(config, "revision");
        if (revision < 1 || revision > 9007199254740991L) {
            throw new IOException("invalid_revision");
        }
        String expiresUtc = JsonCodec.string(config, "expiresUtc");
        try {
            if (!expiresUtc.endsWith("Z") || !Instant.parse(expiresUtc).isAfter(Instant.now())) {
                throw new IOException("expired_manifest");
            }
        } catch (DateTimeParseException ex) {
            throw new IOException("invalid_expiry");
        }
        String targetId = identifier(JsonCodec.string(config, "targetId"));
        String minecraft = JsonCodec.string(config, "minecraft");
        String loader = JsonCodec.string(config, "loader");
        String loaderVersion = JsonCodec.string(config, "loaderVersion");
        if (minecraft.trim().isEmpty() || minecraft.length() > 128 || loaderVersion.trim().isEmpty()
            || loaderVersion.length() > 128 || !Arrays.asList("fabric", "quilt", "forge", "neoforge").contains(loader)) {
            throw new IOException("unsupported_target");
        }
        URI baseUrl;
        try {
            baseUrl = URI.create(JsonCodec.string(config, "baseUrl"));
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid_base_url");
        }
        boolean loopback = "127.0.0.1".equals(baseUrl.getHost()) || "[::1]".equals(baseUrl.getHost());
        if (baseUrl.getHost() == null || baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null
            || baseUrl.getRawFragment() != null || baseUrl.getPort() == 0 || baseUrl.getPort() > 65535
            || !(baseUrl.getRawPath().isEmpty() || "/".equals(baseUrl.getRawPath()))
            || !("https".equals(baseUrl.getScheme()) || ("http".equals(baseUrl.getScheme()) && loopback))) {
            throw new IOException("invalid_base_url");
        }
        String bindAddress = config.has("bindAddress") ? JsonCodec.string(config, "bindAddress") : "127.0.0.1";
        if (!Arrays.asList("127.0.0.1", "::1", "0.0.0.0", "::").contains(bindAddress)) {
            throw new IOException("invalid_bind_address");
        }
        long port = JsonCodec.integer(config, "port");
        if (port < 1 || port > 65535) {
            throw new IOException("invalid_port");
        }
        JsonElement rawFiles = config.get("files");
        if (rawFiles == null || !rawFiles.isJsonArray() || rawFiles.getAsJsonArray().size() > 2048) {
            throw new IOException("invalid_files");
        }
        List<ClientFile> files = new ArrayList<>();
        Set<String> artifactIds = new HashSet<>();
        Set<String> filenames = new HashSet<>();
        Set<String> modIds = new HashSet<>();
        for (JsonElement element : rawFiles.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IOException("invalid_file");
            }
            JsonObject file = element.getAsJsonObject();
            String artifactId = identifier(JsonCodec.string(file, "artifactId"));
            String filename = filename(JsonCodec.string(file, "filename"));
            String client = JsonCodec.string(file, "client");
            if (!Arrays.asList("required", "optional", "unsupported").contains(client)
                || !artifactIds.add(artifactId) || !filenames.add(filename.toLowerCase(Locale.ROOT))) {
                throw new IOException("duplicate_or_invalid_file");
            }
            List<String> ids = strings(file, "modIds");
            if (ids.isEmpty() || ids.size() > 256) {
                throw new IOException("invalid_mod_ids");
            }
            for (String id : ids) {
                if (!id.matches("[a-z][a-z0-9_-]{1,63}") || !modIds.add(id)) {
                    throw new IOException("duplicate_or_invalid_mod_id");
                }
            }
            List<String> dependencies = strings(file, "dependencies");
            if (dependencies.size() > 2048 || new HashSet<>(dependencies).size() != dependencies.size()) {
                throw new IOException("invalid_dependencies");
            }
            for (String dependency : dependencies) {
                identifier(dependency);
                if (artifactId.equals(dependency)) {
                    throw new IOException("invalid_dependencies");
                }
            }
            files.add(new ClientFile(artifactId, ids, client, filename, dependencies));
        }
        Set<String> required = new HashSet<>();
        for (ClientFile file : files) {
            if ("required".equals(file.client())) {
                required.add(file.artifactId());
            }
        }
        for (ClientFile file : files) {
            if (!artifactIds.containsAll(file.dependencies())
                || ("required".equals(file.client()) && !required.containsAll(file.dependencies()))) {
                throw new IOException("missing_dependency");
            }
        }
        return new ServerConfig(serverId, revision, expiresUtc, targetId, minecraft, loader, loaderVersion,
            baseUrl.resolve("/"), bindAddress, (int) port, files);
    }

    public String url(String path) {
        return baseUrl.resolve(path).toASCIIString();
    }

    private static String identifier(String value) throws IOException {
        if (!value.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IOException("invalid_identifier");
        }
        return value;
    }

    private static String filename(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,195}\\.jar")
            || value.substring(0, value.indexOf('.')).matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])")) {
            throw new IOException("invalid_filename");
        }
        return value;
    }

    private static List<String> strings(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("invalid_" + name);
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new IOException("invalid_" + name);
            }
            result.add(item.getAsString());
        }
        return Collections.unmodifiableList(result);
    }
}
