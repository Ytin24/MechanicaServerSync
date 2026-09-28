package ru.mechanica.sync.fabric.mixin;

import net.minecraft.network.packet.s2c.query.QueryResponseS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import ru.mechanica.sync.server.StatusAdvertisement;

@Mixin(QueryResponseS2CPacket.class)
public abstract class StatusResponseMixin {
    @ModifyArg(method = "write", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/PacketByteBuf;writeString(Ljava/lang/String;)Lnet/minecraft/network/PacketByteBuf;"), index = 0)
    private String mechanica$advertise(String json) {
        return StatusAdvertisement.decorate(json);
    }
}
