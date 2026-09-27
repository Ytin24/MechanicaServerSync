package ru.mechanica.sync.ipc;

import com.google.gson.JsonObject;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import ru.mechanica.sync.common.JsonCodec;

public final class FrameCodec {
    public static final int MAXIMUM_BYTES = 16 * 1024;

    private FrameCodec() { }

    public static JsonObject read(DataInput input) throws IOException {
        int length = Integer.reverseBytes(input.readInt());
        if (length < 1 || length > MAXIMUM_BYTES) {
            throw new IOException("invalid_frame_size");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return JsonCodec.decode(bytes, MAXIMUM_BYTES);
    }

    public static void write(DataOutput output, JsonObject message) throws IOException {
        byte[] bytes = JsonCodec.encode(message);
        if (bytes.length == 0 || bytes.length > MAXIMUM_BYTES) {
            throw new IOException("invalid_frame_size");
        }
        output.writeInt(Integer.reverseBytes(bytes.length));
        output.write(bytes);
    }
}
