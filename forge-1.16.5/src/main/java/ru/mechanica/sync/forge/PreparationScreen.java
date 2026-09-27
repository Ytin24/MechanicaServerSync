package ru.mechanica.sync.forge;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.StringTextComponent;

final class PreparationScreen extends Screen {
    private final Runnable cancel;

    PreparationScreen(Runnable cancel) {
        super(new StringTextComponent("Проверка модов сервера"));
        this.cancel = cancel;
    }

    @Override
    protected void init() {
        addButton(new Button(width / 2 - 100, height / 2 + 35, 200, 20,
            new StringTextComponent("Отмена"), button -> onClose()));
    }

    @Override
    public void render(MatrixStack graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        drawCenteredString(graphics, font, title, width / 2, height / 2 - 30, 0xffffff);
        drawCenteredString(graphics, font, "Подтверждение и загрузка — в Mechanica",
            width / 2, height / 2 - 8, 0xb8b8b8);
    }

    @Override
    public void onClose() { cancel.run(); }
}