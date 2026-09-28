package ru.mechanica.sync.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import ru.mechanica.sync.common.JsonCodec;

public final class StatusAdvertisement {
    private static final int MAXIMUM_CHARACTERS = 32767;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final AtomicReference<Registration> ACTIVE = new AtomicReference<>();

    private StatusAdvertisement() { }

    static Registration register(String descriptorUrl) {
        Registration registration = new Registration(descriptorUrl);
        ACTIVE.set(registration);
        return registration;
    }

    static void unregister(Registration registration) {
        if (registration != null) {
            ACTIVE.compareAndSet(registration, null);
        }
    }

    public static String decorate(String json) {
        Registration registration = ACTIVE.get();
        if (registration == null || json == null || json.length() > MAXIMUM_CHARACTERS) {
            return json;
        }
        try {
            JsonObject status = JsonCodec.decode(json.getBytes(StandardCharsets.UTF_8), MAXIMUM_CHARACTERS * 4);
            JsonObject advertisement = new JsonObject();
            advertisement.addProperty("protocol", 1);
            advertisement.addProperty("descriptorUrl", registration.descriptorUrl);
            status.add("mechanica", advertisement);
            String decorated = GSON.toJson(status);
            return decorated.length() <= MAXIMUM_CHARACTERS ? decorated : json;
        } catch (IOException | RuntimeException ex) {
            return json;
        }
    }

    static final class Registration {
        private final String descriptorUrl;

        private Registration(String descriptorUrl) {
            this.descriptorUrl = descriptorUrl;
        }
    }
}
