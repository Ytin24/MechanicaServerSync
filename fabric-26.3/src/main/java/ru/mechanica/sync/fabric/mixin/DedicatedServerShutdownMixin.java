package ru.mechanica.sync.fabric.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.fabric.ServerBridge;

@Mixin(MinecraftServer.class)
public abstract class DedicatedServerShutdownMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void mechanica$stopPublisher(CallbackInfo callback) {
        ServerBridge.stop();
    }
}
