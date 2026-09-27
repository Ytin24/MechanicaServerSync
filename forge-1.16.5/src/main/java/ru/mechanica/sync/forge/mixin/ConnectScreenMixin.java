package ru.mechanica.sync.forge.mixin;

import net.minecraft.client.gui.screen.ConnectingScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.forge.ClientBridge;

@Mixin(ConnectingScreen.class)
public abstract class ConnectScreenMixin {
    @Shadow @Final private Screen parent;

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true)
    private void prepare(String host, int port, CallbackInfo callback) {
        if (ClientBridge.intercept((Screen) (Object) this, parent, host, port)) callback.cancel();
    }
}