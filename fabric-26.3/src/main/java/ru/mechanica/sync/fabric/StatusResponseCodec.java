package ru.mechanica.sync.fabric;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.Utf8String;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import ru.mechanica.sync.server.StatusAdvertisement;

public final class StatusResponseCodec implements StreamCodec<ByteBuf, ClientboundStatusResponsePacket> {
    private final StreamCodec<ByteBuf, ClientboundStatusResponsePacket> delegate;

    public StatusResponseCodec(StreamCodec<ByteBuf, ClientboundStatusResponsePacket> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ClientboundStatusResponsePacket decode(ByteBuf buffer) {
        return delegate.decode(buffer);
    }

    @Override
    public void encode(ByteBuf buffer, ClientboundStatusResponsePacket packet) {
        ByteBuf serialized = buffer.alloc().buffer();
        try {
            delegate.encode(serialized, packet);
            Utf8String.write(buffer, StatusAdvertisement.decorate(Utf8String.read(serialized, 32767)), 32767);
        } finally {
            serialized.release();
        }
    }
}
