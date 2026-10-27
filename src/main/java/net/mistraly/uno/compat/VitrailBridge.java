package net.mistraly.uno.compat;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.vitrail.api.DistantTerrain;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import net.mistraly.uno.Uno;

/// Hands Uno's LODs to a Vitrail shader pack as far terrain, through Vitrail's `DistantTerrain` door: the pack's
/// own `dh_terrain`, `dh_water` and `dh_shadow` then draw them like Distant Horizons' terrain, lit and shadowed
/// by the pack. Only loaded when Vitrail is installed.
public final class VitrailBridge {
    private static List<DistantTerrain.Section> opaque = List.of();
    private static List<DistantTerrain.Section> water = List.of();
    private static boolean drewOpaque;

    private VitrailBridge() {
    }

    public static void register(BooleanSupplier enabled, IntSupplier distance, DoubleSupplier farPlane) {
        DistantTerrain.register(new DistantTerrain.Provider() {
            @Override
            public boolean enabled() {
                return enabled.getAsBoolean();
            }

            @Override
            public int renderDistanceBlocks() {
                return distance.getAsInt();
            }

            @Override
            public float nearPlane() {
                return 16.0F;
            }

            @Override
            public float farPlane() {
                return (float) farPlane.getAsDouble();
            }
        });
        Uno.LOGGER.info("Uno: registered with Vitrail as far terrain provider");
    }

    /// Whether a loaded shader pack draws far terrain itself; Uno then hands its LODs over instead of drawing them.
    public static boolean packDraws() {
        return DistantTerrain.packDrawsFarTerrain();
    }

    /// Hands this frame's sections to the pack. `starts[i]` is the first quad of section i in `quads` (quads are
    /// 16 bytes, 4 vertices of 16 bytes each in Distant Horizons' layout are not what Uno stores, so the quads are
    /// the source of truth and the pack reads them through {@code vertices}). Returns whether the opaque half drew.
    public static boolean hand(GpuBuffer vertices, GpuBuffer indices, int count, int[] cornerX, int[] cornerY, int[] cornerZ,
        int[] starts, int[] opaqueQuads, int[] waterQuads) {
        List<DistantTerrain.Section> opaqueList = new ArrayList<>(count);
        List<DistantTerrain.Section> waterList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (opaqueQuads[i] > 0) {
                opaqueList.add(new DistantTerrain.Section(cornerX[i], cornerY[i], cornerZ[i],
                    List.of(new DistantTerrain.Piece(vertices, indices, opaqueQuads[i] * 6, 0, starts[i] * 4))));
            }
            if (waterQuads[i] > 0) {
                waterList.add(new DistantTerrain.Section(cornerX[i], cornerY[i], cornerZ[i],
                    List.of(new DistantTerrain.Piece(vertices, indices, waterQuads[i] * 6, 0, (starts[i] + opaqueQuads[i]) * 4))));
            }
        }
        opaque = opaqueList;
        water = waterList;
        return drawOpaque();
    }

    /// Hands the opaque half to the pack; false means it did not draw it and Uno should.
    public static boolean drawOpaque() {
        drewOpaque = !opaque.isEmpty() && DistantTerrain.draw(true, opaque);
        return drewOpaque;
    }

    /// Hands the water half to the pack, only after it drew the opaque half this frame.
    public static boolean drawWater() {
        boolean drew = drewOpaque && (water.isEmpty() || DistantTerrain.draw(false, water));
        drewOpaque = false;
        return drew;
    }
}
