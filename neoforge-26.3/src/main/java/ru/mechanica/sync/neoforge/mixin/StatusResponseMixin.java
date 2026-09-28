package ru.mechanica.sync.neoforge.mixin;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.server.StatusAdvertisement;

@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusResponseMixin {
    @Shadow @Final @Mutable
    public static StreamCodec<FriendlyByteBuf, ClientboundStatusResponsePacket> STREAM_CODEC;

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void advertise(CallbackInfo callback) {
        StreamCodec<FriendlyByteBuf, ClientboundStatusResponsePacket> original = STREAM_CODEC;
        STREAM_CODEC = StreamCodec.of((destination, packet) -> {
            FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
            try {
                original.encode(encoded, packet);
                destination.writeUtf(StatusAdvertisement.decorate(encoded.readUtf()));
            } finally {
                encoded.release();
            }
        }, original::decode);
    }
}
