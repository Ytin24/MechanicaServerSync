package ru.mechanica.sync.fabric.mixin;

import com.mojang.serialization.Codec;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.query.QueryResponseS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.mechanica.sync.server.StatusAdvertisement;

@Mixin(QueryResponseS2CPacket.class)
public abstract class StatusResponseMixin {
    @Redirect(method = "write", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/PacketByteBuf;encodeAsJson(Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V"))
    private <T> void mechanica$advertise(PacketByteBuf buffer, Codec<T> codec, T metadata) {
        PacketByteBuf serialized = new PacketByteBuf(buffer.alloc().buffer());
        try {
            serialized.encodeAsJson(codec, metadata);
            buffer.writeString(StatusAdvertisement.decorate(serialized.readString()));
        } finally {
            serialized.release();
        }
    }
}
