package ru.mechanica.sync.forge.mixin;

import net.minecraft.network.status.server.SServerInfoPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import ru.mechanica.sync.server.StatusAdvertisement;

@Mixin(SServerInfoPacket.class)
public abstract class StatusResponseMixin {
    @ModifyArg(method = "write", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/PacketBuffer;writeUtf(Ljava/lang/String;)Lnet/minecraft/network/PacketBuffer;"), index = 0)
    private String advertise(String json) {
        return StatusAdvertisement.decorate(json);
    }
}
