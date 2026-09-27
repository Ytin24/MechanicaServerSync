package ru.mechanica.sync.fabric.mixin;

import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.mechanica.sync.fabric.ClientBridge;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Shadow @Final private Screen parent;
    @Unique private String mechanica$host;
    @Unique private int mechanica$port;

    @Inject(method = "connect(Ljava/lang/String;I)V", at = @At("HEAD"), cancellable = true)
    private void mechanica$deferConnection(String host, int port, CallbackInfo callback) {
        if (ClientBridge.shouldIntercept()) {
            mechanica$host = host;
            mechanica$port = port;
            callback.cancel();
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mechanica$prepareConnection(CallbackInfo callback) {
        if (mechanica$host != null) {
            String host = mechanica$host;
            mechanica$host = null;
            ClientBridge.prepare((ConnectScreen) (Object) this, parent, host, mechanica$port);
            callback.cancel();
        }
    }
}
