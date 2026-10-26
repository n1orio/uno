package net.mistraly.uno.compat;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;

/// Asks Sodium whether a 16^3 section has been built. Only loaded when Sodium is installed.
public final class SodiumSections {
    private SodiumSections() {
    }

    public static boolean built(int x, int y, int z) {
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
        if (renderer == null) {
            return false;
        }
        try {
            return renderer.isSectionReady(x, y, z);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
