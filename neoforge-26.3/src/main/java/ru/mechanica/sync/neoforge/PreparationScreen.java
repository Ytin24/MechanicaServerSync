package ru.mechanica.sync.neoforge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class PreparationScreen extends Screen {
    private final Runnable cancel;

    PreparationScreen(Runnable cancel) {
        super(Component.literal("Проверка модов сервера"));
        this.cancel = cancel;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Отмена"), button -> onClose())
            .bounds(width / 2 - 100, height / 2 + 35, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractBackground(graphics, mouseX, mouseY, partialTick);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 30, 0xffffff);
        graphics.centeredText(font, Component.literal("Подтверждение и загрузка — в Mechanica"),
            width / 2, height / 2 - 8, 0xb8b8b8);
    }

    @Override
    public void onClose() {
        cancel.run();
    }
}

