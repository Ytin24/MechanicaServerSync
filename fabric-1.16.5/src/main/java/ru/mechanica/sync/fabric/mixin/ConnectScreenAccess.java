package ru.mechanica.sync.fabric.mixin;

import net.minecraft.client.gui.screen.ConnectScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ConnectScreen.class)
public interface ConnectScreenAccess {
    @Invoker("connect") void mechanica$connect(String host, int port);
}
