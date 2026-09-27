package ru.mechanica.sync.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class JsonCodec {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private JsonCodec() { }

    public static byte[] encode(JsonObject object) {
        return GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    }

    public static JsonObject decode(byte[] bytes, int maximum) throws IOException {
        if (bytes.length == 0 || bytes.length > maximum) {
            throw new IOException("invalid_json_size");
        }
        String text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("invalid_json");
            }
            return value.getAsJsonObject();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new IOException("invalid_json", ex);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 16) {
            throw new IOException("json_too_deep");
        }
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) {
                        throw new IOException("duplicate_json_key");
                    }
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(read(reader, depth + 1));
                }
                reader.endArray();
                return array;
            }
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: {
                reader.nextNull();
                return JsonNull.INSTANCE;
            }
            default: throw new IOException("invalid_json_token");
        }
    }

    public static String string(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("invalid_" + name);
        }
        return value.getAsString();
    }

    public static long integer(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("invalid_" + name);
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IOException("invalid_" + name);
        }
    }

    public static JsonObject object(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonObject()) {
            throw new IOException("invalid_" + name);
        }
        return value.getAsJsonObject();
    }
}
