package ru.mechanica.sync.forge;

import java.io.IOException;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

final class PreparationScreen extends GuiScreen {
    private final Runnable cancel;

    PreparationScreen(Runnable cancel) { this.cancel = cancel; }

    @Override
    public void initGui() {
        buttonList.add(new GuiButton(0, width / 2 - 100, height / 2 + 35, 200, 20, "Отмена"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTick);
        drawCenteredString(fontRenderer, "Проверка модов сервера", width / 2, height / 2 - 30, 0xffffff);
        drawCenteredString(fontRenderer, "Подтверждение и загрузка — в Mechanica",
            width / 2, height / 2 - 8, 0xb8b8b8);
    }

    @Override
    protected void actionPerformed(GuiButton button) { cancel.run(); }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) cancel.run();
        else super.keyTyped(typedChar, keyCode);
    }
}

