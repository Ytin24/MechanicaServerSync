package ru.mechanica.sync.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import ru.mechanica.sync.common.JsonCodec;

public final class AutoConfigChecks {
    private static int checks;
    private static final AutoConfig.Runtime FABRIC = new AutoConfig.Runtime("fabric", "1.20.1", "0.19.5");

    private AutoConfigChecks() { }

    public static int run() throws Exception {
        checks = 0;
        Path root = Files.createTempDirectory("mechanica-autoconfig");
        try {
            metadata(root);
            completion(root);
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        return checks;
    }

    private static void metadata(Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("metadata"));
        List<String> log = new ArrayList<>();
        check(AutoConfig.modIds(jar(dir, "fabric.jar", "fabric.mod.json",
            "{\n  // comments are allowed by Fabric\n  \"id\": \"sodium\", \"version\": \"1\"\n}"), log::add)
            .equals(list("sodium")), "fabric.mod.json id");
        check(AutoConfig.modIds(jar(dir, "quilt.jar", "quilt.mod.json",
            "{\"quilt_loader\":{\"id\":\"qsl_mod\"}}"), log::add).equals(list("qsl_mod")), "quilt.mod.json id");
        String toml = "modLoader=\"javafml\"\n[[mods]]\nmodId=\"create\"\n[[mods]]\n  modId = \"flywheel\" # comment\n"
            + "[[dependencies.create]]\nmodId=\"forge\"\nmandatory=true\n";
        check(AutoConfig.modIds(jar(dir, "forge.jar", "META-INF/mods.toml", toml), log::add)
            .equals(list("create", "flywheel")), "mods.toml ignores dependency modIds");
        check(AutoConfig.modIds(jar(dir, "neo.jar", "META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"jei\"\n"), log::add)
            .equals(list("jei")), "neoforge.mods.toml id");
        check(AutoConfig.modIds(jar(dir, "legacy.jar", "mcmod.info", "[{\"modid\":\"JourneyMap\"}]"), log::add)
            .equals(list("journeymap")), "mcmod.info id normalized");
        check(AutoConfig.modIds(jar(dir, "plain.jar", "readme.txt", "x"), log::add).isEmpty(), "unknown jar has no ids");
        Path broken = dir.resolve("broken.jar");
        Files.write(broken, "not a zip".getBytes(StandardCharsets.UTF_8));
        check(AutoConfig.modIds(broken, log::add).isEmpty() && !log.isEmpty(), "unreadable jar logged, not fatal");
        check(AutoConfig.targetId("fabric", "1.20.1").equals("fabric-1.20.1"), "target id from runtime");
        check(AutoConfig.targetId("neoforge", "26.3 Snapshot").equals("neoforge-26.3-snapshot"), "target id sanitized");
    }

    private static void completion(Path root) throws Exception {
        Path game = Files.createDirectories(root.resolve("server"));
        Path pack = Files.createDirectories(game.resolve("client-pack"));
        Path config = Files.createDirectories(game.resolve("config")).resolve("mechanica-sync.json");
        Path state = config.resolveSibling(AutoConfig.STATE_FILE);
        jar(pack, "sodium-0.5.jar", "fabric.mod.json", "{\"id\":\"sodium\"}");
        jar(pack, "Iris_Shaders+1.0.jar", "fabric.mod.json", "{\"id\":\"iris\"}");
        jar(pack, "nometa.jar", "readme.txt", "x");
        jar(pack, "duplicate.jar", "fabric.mod.json", "{\"id\":\"sodium\"}");
        jar(pack, "bad name.jar", "fabric.mod.json", "{\"id\":\"bad\"}");
        Files.write(pack.resolve("notes.txt"), "ignored".getBytes(StandardCharsets.UTF_8));

        JsonObject minimal = new JsonObject();
        minimal.addProperty("baseUrl", "https://mods.example.org");
        Files.write(config, JsonCodec.encode(minimal));
        List<String> log = new ArrayList<>();
        Instant now = Instant.parse("2026-11-26T00:00:00Z");

        ServerConfig first = AutoConfig.complete(minimal, game, state, FABRIC, now, log::add);
        check(first.loader().equals("fabric") && first.minecraft().equals("1.20.1")
            && first.loaderVersion().equals("0.19.5") && first.targetId().equals("fabric-1.20.1"), "target filled from runtime");
        check(first.port() == AutoConfig.DEFAULT_PORT && first.bindAddress().equals("127.0.0.1"), "default port and bind");
        check(first.files().size() == 4, "all valid jars published, bad file name skipped");
        check(log.stream().anyMatch(line -> line.contains("bad name.jar")), "skipped file is logged");
        check(first.files().stream().allMatch(file -> "required".equals(file.client()) && file.dependencies().isEmpty()),
            "auto files are required with no dependencies");
        ServerConfig.ClientFile duplicate = find(first, "duplicate.jar");
        ServerConfig.ClientFile sodium = find(first, "sodium-0.5.jar");
        check(!duplicate.modIds().contains("sodium") || !sodium.modIds().contains("sodium"), "mod ids stay unique");
        check(find(first, "Iris_Shaders+1.0.jar").artifactId().equals("iris"), "artifact id from mod id");
        check(find(first, "nometa.jar").modIds().get(0).equals("nometa"), "fallback mod id from filename");
        check(first.revision() == 1 && Files.exists(state), "first start creates revision 1 and state");
        check(Instant.parse(first.expiresUtc()).equals(now.plus(AutoConfig.LIFETIME)), "expiry set 90 days ahead");

        ServerConfig restart = AutoConfig.complete(minimal, game, state, FABRIC, now.plusSeconds(3600), log::add);
        check(restart.revision() == 1 && restart.serverId().equals(first.serverId())
            && restart.expiresUtc().equals(first.expiresUtc()), "plain restart keeps revision, id and expiry");

        jar(pack, "newmod.jar", "fabric.mod.json", "{\"id\":\"newmod\"}");
        ServerConfig added = AutoConfig.complete(minimal, game, state, FABRIC, now.plusSeconds(7200), log::add);
        check(added.revision() == 2 && added.files().size() == 5, "adding a jar bumps revision");

        jar(pack, "newmod.jar", "fabric.mod.json", "{\"id\":\"newmod\",\"version\":\"2\"}");
        ServerConfig changed = AutoConfig.complete(minimal, game, state, FABRIC, now.plusSeconds(9000), log::add);
        check(changed.revision() == 3, "replacing jar bytes bumps revision");

        ServerConfig renewed = AutoConfig.complete(minimal, game, state, FABRIC, now.plus(java.time.Duration.ofDays(70)), log::add);
        check(renewed.revision() == 4 && Instant.parse(renewed.expiresUtc()).isAfter(Instant.parse(first.expiresUtc())),
            "expiry renewed near the end with a new revision");

        new ManifestPublisher(game, renewed, line -> { }).close();
        check(true, "completed config is accepted by publisher");

        JsonObject explicit = new JsonObject();
        explicit.addProperty("baseUrl", "https://mods.example.org");
        explicit.addProperty("revision", 42);
        explicit.addProperty("serverId", "b3a38c5e-593c-4bdd-9046-e019b3453eb0");
        explicit.addProperty("expiresUtc", "2030-01-01T00:00:00Z");
        JsonArray files = new JsonArray();
        JsonObject file = new JsonObject();
        file.addProperty("artifactId", "sodium");
        JsonArray ids = new JsonArray();
        ids.add("sodium");
        file.add("modIds", ids);
        file.addProperty("client", "optional");
        file.addProperty("filename", "sodium-0.5.jar");
        file.add("dependencies", new JsonArray());
        files.add(file);
        explicit.add("files", files);
        ServerConfig manual = AutoConfig.complete(explicit, game, state, FABRIC, now, log::add);
        check(manual.revision() == 42 && manual.files().size() == 1 && "optional".equals(manual.files().get(0).client())
            && manual.serverId().equals("b3a38c5e-593c-4bdd-9046-e019b3453eb0"), "explicit values win");

        JsonObject mismatch = new JsonObject();
        mismatch.addProperty("baseUrl", "https://mods.example.org");
        mismatch.addProperty("loaderVersion", "0.14.0");
        fails(() -> AutoConfig.complete(mismatch, game, state, FABRIC, now, log::add), "explicit target mismatch rejected");

        Files.write(state, "{broken".getBytes(StandardCharsets.UTF_8));
        ServerConfig reset = AutoConfig.complete(minimal, game, state, FABRIC, now, log::add);
        check(reset.revision() == 1 && log.stream().anyMatch(line -> line.startsWith("MECHANICA_SYNC_STATE_RESET")),
            "corrupt state is reset, not fatal");

        Files.write(config, JsonCodec.encode(minimal));
        check(AutoConfig.load(config, game, FABRIC, log::add).files().size() == 5, "load reads config from disk");
    }

    private static ServerConfig.ClientFile find(ServerConfig config, String filename) {
        for (ServerConfig.ClientFile file : config.files()) {
            if (file.filename().equals(filename)) return file;
        }
        throw new AssertionError("missing " + filename);
    }

    private static Path jar(Path dir, String name, String entry, String content) throws IOException {
        Path path = dir.resolve(name);
        try (OutputStream output = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    private static List<String> list(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) result.add(value);
        return result;
    }

    private interface Action { void run() throws Exception; }

    private static void fails(Action action, String description) {
        try {
            action.run();
        } catch (Exception expected) {
            checks++;
            return;
        }
        throw new AssertionError(description);
    }

    private static void check(boolean valid, String description) {
        if (!valid) throw new AssertionError(description);
        checks++;
    }
}
