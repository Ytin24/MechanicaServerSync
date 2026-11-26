package ru.mechanica.sync.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import ru.mechanica.sync.common.JsonCodec;

/**
 * Fills in everything the server can work out for itself, so that an administrator only has to drop
 * client JARs into {@code client-pack} and set {@code baseUrl}.
 *
 * <p>Every value written explicitly in {@code mechanica-sync.json} wins. Missing values are derived:
 * the target comes from the running server, the file list from {@code client-pack} (mod IDs are read
 * from the JAR metadata), and {@code serverId}, {@code revision} and {@code expiresUtc} are kept in
 * {@code mechanica-sync.state.json} next to the config. The revision grows only when the published
 * set actually changes, so clients are not asked to resync after a plain restart.
 */
public final class AutoConfig {
    public static final String STATE_FILE = "mechanica-sync.state.json";
    public static final int DEFAULT_PORT = 25566;
    static final Duration LIFETIME = Duration.ofDays(90);
    static final Duration RENEW_BEFORE = Duration.ofDays(30);

    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,195}\\.jar");
    private static final Pattern TOML_MOD_ID = Pattern.compile("^\\s*modId\\s*=\\s*\"([^\"]*)\"");
    private static final int MAXIMUM_METADATA_BYTES = 256 * 1024;

    private AutoConfig() { }

    /** What the running server actually is; used both as defaults and to reject a stale config. */
    public static final class Runtime {
        private final String loader, minecraft, loaderVersion;

        public Runtime(String loader, String minecraft, String loaderVersion) {
            this.loader = loader;
            this.minecraft = minecraft;
            this.loaderVersion = loaderVersion;
        }
    }

    /**
     * Reads {@code configuration}, completes it and validates it with {@link ServerConfig#parse}.
     * Throws {@code server_target_mismatch} when explicit target fields disagree with the server.
     */
    public static ServerConfig load(Path configuration, Path gameDirectory, Runtime runtime, Consumer<String> log)
        throws IOException {
        JsonObject config = JsonCodec.decode(readLimited(configuration, ServerConfig.MAXIMUM_JSON_BYTES),
            ServerConfig.MAXIMUM_JSON_BYTES);
        Path statePath = configuration.resolveSibling(STATE_FILE);
        return complete(config, gameDirectory, statePath, runtime, Instant.now(), log);
    }

    static ServerConfig complete(JsonObject source, Path gameDirectory, Path statePath, Runtime runtime,
                                 Instant now, Consumer<String> log) throws IOException {
        JsonObject config = copy(source);
        target(config, "loader", runtime.loader);
        target(config, "minecraft", runtime.minecraft);
        target(config, "loaderVersion", runtime.loaderVersion);
        if (!config.has("targetId")) {
            config.addProperty("targetId", targetId(runtime.loader, runtime.minecraft));
        }
        if (!config.has("port")) {
            config.addProperty("port", DEFAULT_PORT);
        }

        boolean autoFiles = !config.has("files")
            || (config.get("files").isJsonPrimitive() && "auto".equals(config.get("files").getAsString()));
        if (autoFiles) {
            config.add("files", scan(gameDirectory.toRealPath().resolve("client-pack"), log));
        }

        boolean autoServerId = !config.has("serverId");
        boolean autoExpiry = !config.has("expiresUtc");
        boolean autoRevision = !config.has("revision");
        if (autoServerId || autoExpiry || autoRevision) {
            JsonObject state = readState(statePath, log);
            boolean dirty = false;
            if (autoServerId) {
                String serverId = optionalString(state, "serverId");
                if (serverId == null || !validUuid(serverId)) {
                    serverId = UUID.randomUUID().toString();
                    state.addProperty("serverId", serverId);
                    dirty = true;
                }
                config.addProperty("serverId", serverId);
            }
            if (autoExpiry) {
                String expiresUtc = optionalString(state, "expiresUtc");
                Instant expires = parseInstant(expiresUtc);
                if (expires == null || expires.isBefore(now.plus(RENEW_BEFORE))) {
                    expiresUtc = now.plus(LIFETIME).truncatedTo(ChronoUnit.SECONDS).toString();
                    state.addProperty("expiresUtc", expiresUtc);
                    dirty = true;
                }
                config.addProperty("expiresUtc", expiresUtc);
            }
            if (autoRevision) {
                String fingerprint = fingerprint(config, gameDirectory);
                long revision = state.has("revision") && state.get("revision").isJsonPrimitive()
                    ? safeLong(state.get("revision")) : 0;
                if (revision < 1 || !fingerprint.equals(optionalString(state, "fingerprint"))) {
                    revision = Math.max(revision, 0) + 1;
                    state.addProperty("revision", revision);
                    state.addProperty("fingerprint", fingerprint);
                    dirty = true;
                }
                config.addProperty("revision", revision);
            }
            if (dirty) {
                writeState(statePath, state);
            }
        }

        ServerConfig parsed = ServerConfig.parse(config);
        if (!parsed.loader().equals(runtime.loader) || !parsed.loaderVersion().equals(runtime.loaderVersion)
            || !parsed.minecraft().equals(runtime.minecraft)) {
            throw new IOException("server_target_mismatch: config " + parsed.loader() + " " + parsed.minecraft()
                + " " + parsed.loaderVersion() + ", server " + runtime.loader + " " + runtime.minecraft
                + " " + runtime.loaderVersion);
        }
        log.accept("MECHANICA_SYNC_CONFIG files=" + parsed.files().size() + " revision=" + parsed.revision()
            + " target=" + parsed.targetId() + (autoFiles ? " source=client-pack" : " source=config"));
        return parsed;
    }

    /** Lists {@code client-pack/*.jar} as required files, reading mod IDs from each JAR's metadata. */
    static JsonArray scan(Path clientPack, Consumer<String> log) throws IOException {
        if (!Files.isDirectory(clientPack, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("invalid_client_pack_directory");
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(clientPack, "*.jar")) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    jars.add(entry);
                }
            }
        }
        Collections.sort(jars, (left, right) -> left.getFileName().toString()
            .compareToIgnoreCase(right.getFileName().toString()));
        Set<String> usedModIds = new LinkedHashSet<>();
        Set<String> usedArtifacts = new LinkedHashSet<>();
        JsonArray files = new JsonArray();
        for (Path jar : jars) {
            String filename = jar.getFileName().toString();
            if (!FILENAME.matcher(filename).matches()
                || filename.substring(0, filename.indexOf('.')).matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])")) {
                log.accept("MECHANICA_SYNC_SKIPPED_FILE filename=" + filename
                    + " reason=use only latin letters, digits and . _ + - in client-pack file names");
                continue;
            }
            if (files.size() >= 2048) {
                log.accept("MECHANICA_SYNC_SKIPPED_FILE filename=" + filename + " reason=more than 2048 files");
                continue;
            }
            List<String> modIds = new ArrayList<>();
            for (String id : modIds(jar, log)) {
                if (MOD_ID.matcher(id).matches() && !usedModIds.contains(id)) {
                    modIds.add(id);
                }
            }
            if (modIds.isEmpty()) {
                modIds.add(unique(fallbackModId(filename), usedModIds, "_"));
            }
            usedModIds.addAll(modIds);
            String artifactId = unique(artifactId(modIds.get(0)), usedArtifacts, "-");
            usedArtifacts.add(artifactId);

            JsonObject file = new JsonObject();
            file.addProperty("artifactId", artifactId);
            JsonArray ids = new JsonArray();
            for (String id : modIds) {
                ids.add(id);
            }
            file.add("modIds", ids);
            file.addProperty("client", "required");
            file.addProperty("filename", filename);
            file.add("dependencies", new JsonArray());
            files.add(file);
        }
        if (files.size() == 0) {
            log.accept("MECHANICA_SYNC_EMPTY_CLIENT_PACK path=" + clientPack);
        }
        return files;
    }

    /** Mod IDs declared by a Fabric, Quilt, Forge, NeoForge or legacy Forge JAR; empty when unknown. */
    static List<String> modIds(Path jar, Consumer<String> log) {
        List<String> ids = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            JsonObject fabric = jsonEntry(zip, "fabric.mod.json");
            if (fabric != null && fabric.has("id") && fabric.get("id").isJsonPrimitive()) {
                ids.add(fabric.get("id").getAsString());
            }
            JsonObject quilt = jsonEntry(zip, "quilt.mod.json");
            if (quilt != null && quilt.has("quilt_loader") && quilt.get("quilt_loader").isJsonObject()) {
                JsonElement id = quilt.getAsJsonObject("quilt_loader").get("id");
                if (id != null && id.isJsonPrimitive()) {
                    ids.add(id.getAsString());
                }
            }
            tomlModIds(zip, "META-INF/neoforge.mods.toml", ids);
            tomlModIds(zip, "META-INF/mods.toml", ids);
            legacyModIds(zip, ids);
        } catch (IOException | RuntimeException ex) {
            log.accept("MECHANICA_SYNC_METADATA_UNREADABLE filename=" + jar.getFileName() + " reason=" + ex.getMessage());
        }
        List<String> distinct = new ArrayList<>();
        for (String id : ids) {
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            if (!distinct.contains(normalized)) {
                distinct.add(normalized);
            }
        }
        return distinct;
    }

    private static void tomlModIds(ZipFile zip, String name, List<String> ids) throws IOException {
        String text = textEntry(zip, name);
        if (text == null) {
            return;
        }
        boolean inMods = false;
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("[")) {
                inMods = line.replace(" ", "").equals("[[mods]]");
                continue;
            }
            if (inMods) {
                java.util.regex.Matcher matcher = TOML_MOD_ID.matcher(line);
                if (matcher.find()) {
                    ids.add(matcher.group(1));
                }
            }
        }
    }

    private static void legacyModIds(ZipFile zip, List<String> ids) throws IOException {
        String text = textEntry(zip, "mcmod.info");
        if (text == null) {
            return;
        }
        JsonElement root = lenient(text);
        JsonArray mods = null;
        if (root != null && root.isJsonArray()) {
            mods = root.getAsJsonArray();
        } else if (root != null && root.isJsonObject() && root.getAsJsonObject().has("modList")
            && root.getAsJsonObject().get("modList").isJsonArray()) {
            mods = root.getAsJsonObject().getAsJsonArray("modList");
        }
        if (mods == null) {
            return;
        }
        for (JsonElement mod : mods) {
            if (mod.isJsonObject() && mod.getAsJsonObject().has("modid")
                && mod.getAsJsonObject().get("modid").isJsonPrimitive()) {
                ids.add(mod.getAsJsonObject().get("modid").getAsString());
            }
        }
    }

    private static JsonObject jsonEntry(ZipFile zip, String name) throws IOException {
        String text = textEntry(zip, name);
        if (text == null) {
            return null;
        }
        JsonElement value = lenient(text);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonElement lenient(String text) {
        try {
            JsonReader reader = new JsonReader(new java.io.StringReader(text));
            reader.setLenient(true);
            return new JsonParser().parse(reader);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String textEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        try (InputStream input = zip.getInputStream(entry)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > MAXIMUM_METADATA_BYTES) {
                    return null;
                }
                output.write(buffer, 0, count);
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    static String targetId(String loader, String minecraft) {
        String raw = (loader + "-" + minecraft).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        if (raw.length() > 64) {
            raw = raw.substring(0, 64);
        }
        return IDENTIFIER.matcher(raw).matches() ? raw : "server";
    }

    private static String fallbackModId(String filename) {
        String base = filename.substring(0, filename.length() - 4).toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_-]", "_");
        if (base.isEmpty() || !Character.isLetter(base.charAt(0))) {
            base = "jar_" + base;
        }
        if (base.length() > 56) {
            base = base.substring(0, 56);
        }
        return base.length() < 2 ? "jar_mod" : base;
    }

    private static String artifactId(String modId) {
        String id = modId.replace('_', '-');
        return IDENTIFIER.matcher(id).matches() ? id : "mod";
    }

    private static String unique(String base, Set<String> used, String separator) {
        if (!used.contains(base)) {
            return base;
        }
        for (int index = 2; ; index++) {
            String suffix = separator + index;
            String candidate = (base.length() + suffix.length() > 64 ? base.substring(0, 64 - suffix.length()) : base) + suffix;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** Hash of everything clients see except the revision itself. */
    private static String fingerprint(JsonObject config, Path gameDirectory) throws IOException {
        MessageDigest digest = digest();
        for (String key : new String[]{"serverId", "expiresUtc", "targetId", "minecraft", "loader", "loaderVersion", "baseUrl"}) {
            update(digest, key + "=" + (config.has(key) ? config.get(key).toString() : "") + "\n");
        }
        Path clientPack = gameDirectory.toRealPath().resolve("client-pack");
        JsonElement files = config.get("files");
        if (files != null && files.isJsonArray()) {
            for (JsonElement element : files.getAsJsonArray()) {
                update(digest, element.toString() + "\n");
                if (element.isJsonObject() && element.getAsJsonObject().has("filename")) {
                    String filename = element.getAsJsonObject().get("filename").getAsString();
                    Path file = clientPack.resolve(filename);
                    if (FILENAME.matcher(filename).matches() && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        update(digest, hashFile(file) + "\n");
                    }
                }
            }
        }
        return hex(digest.digest());
    }

    private static String hashFile(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static JsonObject readState(Path statePath, Consumer<String> log) {
        if (!Files.isRegularFile(statePath, LinkOption.NOFOLLOW_LINKS)) {
            return new JsonObject();
        }
        try {
            return JsonCodec.decode(readLimited(statePath, 64 * 1024), 64 * 1024);
        } catch (IOException ex) {
            log.accept("MECHANICA_SYNC_STATE_RESET reason=" + ex.getMessage());
            return new JsonObject();
        }
    }

    private static void writeState(Path statePath, JsonObject state) throws IOException {
        Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        Files.write(temporary, JsonCodec.encode(state));
        try {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void target(JsonObject config, String name, String runtimeValue) {
        if (!config.has(name)) {
            config.addProperty(name, runtimeValue);
        }
    }

    private static JsonObject copy(JsonObject source) {
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            copy.add(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    private static long safeLong(JsonElement value) {
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static boolean validUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || !value.endsWith("Z")) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static byte[] readLimited(Path path, int maximum) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > maximum) {
                    throw new IOException("invalid_json_size");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void update(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
