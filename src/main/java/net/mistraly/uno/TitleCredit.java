package net.mistraly.uno;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ScreenEvent;

/// "Uno <version> by Niorio" at the bottom of the title screen. The version is the mod's own, from its metadata, so
/// it follows every release. The license requires this line to stay.
final class TitleCredit {
    private static String text;

    private TitleCredit() {
    }

    static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) {
            return;
        }
        if (text == null) {
            String version = ModList.get().getModContainerById(Uno.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("");
            text = "Uno " + version + " by Niorio";
        }
        var graphics = event.getGuiGraphics();
        graphics.centeredText(Minecraft.getInstance().font, text, screen.width / 2, screen.height - 10, 0xFFFFFFFF);
    }
}
