package ru.mechanica.sync.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;

final class PreparationScreen extends Screen {
    private final Runnable cancel;

    PreparationScreen(Runnable cancel) {
        super(new LiteralText("Проверка модов сервера"));
        this.cancel = cancel;
    }

    @Override
    protected void init() {
        addButton(new ButtonWidget(width / 2 - 100, height / 2 + 35, 200, 20,
            new LiteralText("Отмена"), button -> onClose()));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);
        drawCenteredText(matrices, textRenderer, title, width / 2, height / 2 - 30, 0xFFFFFF);
        drawCenteredText(matrices, textRenderer, new LiteralText("Подтверждение и загрузка — в Mechanica"),
            width / 2, height / 2 - 8, 0xBEBEBE);
    }

    @Override
    public void onClose() { cancel.run(); }
}
