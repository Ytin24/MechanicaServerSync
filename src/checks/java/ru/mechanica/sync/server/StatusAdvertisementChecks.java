package ru.mechanica.sync.server;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import ru.mechanica.sync.common.JsonCodec;

public final class StatusAdvertisementChecks {
    private static int checks;

    private StatusAdvertisementChecks() { }

    public static int run() throws Exception {
        checks = 0;
        String vanilla = "{\n\"version\":{\"name\":\"Minecraft\",\"protocol\":767},"
            + "\"players\":{\"max\":20,\"online\":1,\"sample\":[{\"name\":\"Player\",\"id\":\"test\"}]},"
            + "\"description\":{\"text\":\"Привет <world> & \\\"friends\\\"\",\"extra\":[{\"text\":\"!\",\"bold\":true}]},"
            + "\"favicon\":\"data:image/png;base64,abc+/=\",\"previewsChat\":false,"
            + "\"modded\":{\"names\":[\"one\",\"two\"],\"unknown\":null,\"value\":1.25}}";
        check(StatusAdvertisement.decorate(vanilla) == vanilla, "disabled status keeps original bytes");
        StatusAdvertisement.Registration first = StatusAdvertisement.register("https://mods.example.org/mechanica/descriptor.json");
        StatusAdvertisement.Registration second = null;
        try {
            JsonObject decorated = decode(StatusAdvertisement.decorate(vanilla));
            JsonObject advertisement = decorated.remove("mechanica").getAsJsonObject();
            check(JsonCodec.integer(advertisement, "protocol") == 1, "status declares protocol 1");
            check(JsonCodec.string(advertisement, "descriptorUrl").equals("https://mods.example.org/mechanica/descriptor.json"),
                "status declares publisher URL");
            check(decorated.equals(decode(vanilla)), "vanilla and unknown modded status fields preserved");
            check(decode(StatusAdvertisement.decorate(StatusAdvertisement.decorate(vanilla)))
                .equals(decode(StatusAdvertisement.decorate(vanilla))), "status decoration is idempotent");
            String malformed = "{broken";
            check(StatusAdvertisement.decorate(malformed) == malformed, "malformed status preserved");
            String nonObject = "[1,2]";
            check(StatusAdvertisement.decorate(nonObject) == nonObject, "nonobject status preserved");
            String atLimit = "{\"description\":\"" + "a".repeat(32749) + "\"}";
            check(atLimit.length() == 32767 && StatusAdvertisement.decorate(atLimit) == atLimit,
                "advertisement cannot exceed Minecraft status string limit");
            second = StatusAdvertisement.register("https://new.example.org/mechanica/descriptor.json");
            StatusAdvertisement.unregister(first);
            StatusAdvertisement.unregister(null);
            check(JsonCodec.string(decode(StatusAdvertisement.decorate(vanilla)).getAsJsonObject("mechanica"), "descriptorUrl")
                .equals("https://new.example.org/mechanica/descriptor.json"), "closing old publisher preserves active publisher");
            StatusAdvertisement.unregister(second);
            check(StatusAdvertisement.decorate(vanilla) == vanilla, "closing active publisher removes advertisement");
            StatusAdvertisement.unregister(second);
            check(StatusAdvertisement.decorate(vanilla) == vanilla, "closing twice stays disabled");
        } finally {
            StatusAdvertisement.unregister(first);
            StatusAdvertisement.unregister(second);
        }
        return checks;
    }

    private static JsonObject decode(String json) throws Exception {
        return JsonCodec.decode(json.getBytes(StandardCharsets.UTF_8), 131068);
    }

    private static void check(boolean valid, String description) {
        if (!valid) throw new AssertionError(description);
        checks++;
    }
}
