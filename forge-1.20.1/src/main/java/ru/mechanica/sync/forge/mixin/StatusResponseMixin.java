package ru.mechanica.sync.forge.mixin;

import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.mechanica.sync.server.StatusAdvertisement;

@Mixin(ClientboundStatusResponsePacket.class)
public abstract class StatusResponseMixin {
    @ModifyArg(method = "write", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/FriendlyByteBuf;writeUtf(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;"), index = 0)
    private String advertiseCached(String json) {
        return StatusAdvertisement.decorate(json);
    }

    @Redirect(method = "write", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/FriendlyByteBuf;writeJsonWithCodec(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"))
    private <T> void advertise(FriendlyByteBuf destination, Codec<T> codec, T status) {
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            encoded.writeJsonWithCodec(codec, status);
            destination.writeUtf(StatusAdvertisement.decorate(encoded.readUtf()));
        } finally {
            encoded.release();
        }
    }
}
