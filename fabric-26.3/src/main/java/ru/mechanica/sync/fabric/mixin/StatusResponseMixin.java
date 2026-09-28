package ru.mechanica.sync.fabric.mixin;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.fabric.StatusResponseCodec;

@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusResponseMixin {
    @Shadow @Final @Mutable
    public static StreamCodec<ByteBuf, ClientboundStatusResponsePacket> STREAM_CODEC;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void mechanica$advertise(CallbackInfo callback) {
        STREAM_CODEC = new StatusResponseCodec(STREAM_CODEC);
    }
}
