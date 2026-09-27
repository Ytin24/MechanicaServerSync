package ru.mechanica.sync.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class PreparationScreen extends Screen {
    private final Runnable cancel;

    PreparationScreen(Runnable cancel) {
        super(Text.literal("Проверка модов сервера"));
        this.cancel = cancel;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), button -> close())
            .dimensions(width / 2 - 100, height / 2 + 35, 200, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 30, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Подтверждение и загрузка — в Mechanica"),
            width / 2, height / 2 - 8, 0xBEBEBE);
    }

    @Override
    public void close() {
        cancel.run();
    }
}
