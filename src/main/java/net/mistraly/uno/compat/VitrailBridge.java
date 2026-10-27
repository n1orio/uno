package net.mistraly.uno.compat;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.vitrail.dh.DhLods;
import dev.vitrail.render.DistantDraw;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.fml.ModList;
import org.joml.Vector2f;

/// Hands Uno's LODs to a Vitrail shader pack as far terrain. Vitrail draws Distant Horizons' far terrain with the
/// pack's own `dh_terrain`, `dh_water` and `dh_shadow`; Uno stands in for DH: its mixins answer the questions Vitrail
/// asks DH (is there far terrain, how far, which planes), and its sections go through `DistantDraw.draw`, the same
/// door DH's geometry goes through. Nothing in Vitrail is changed. Only loaded when Vitrail is installed.
public final class VitrailBridge {
    /// Near plane of the far terrain; LODs never come closer than the edge of the render distance.
    private static final float NEAR_PLANE = 16.0F;

    private static final boolean DISTANT_HORIZONS = ModList.get().isLoaded("distanthorizons");

    private static volatile int distanceBlocks = 2048;
    private static volatile float farPlane = 2560.0F;

    private static List<DhLods.Section> opaque = List.of();
    private static List<DhLods.Section> water = List.of();
    private static boolean drewOpaque;

    private VitrailBridge() {
    }

    /// Called every frame by the renderer: how far the far terrain reaches.
    public static void update(int lodDistance, float far) {
        distanceBlocks = lodDistance;
        farPlane = far;
    }

    /// Whether Uno answers in place of Distant Horizons. DH keeps precedence when it is installed. Like DH this
    /// follows the switch in the settings rather than whether a world is open: a pack is built at startup, before
    /// any world, and built without far terrain it would never compile its dh_ programs.
    private static boolean enabled() {
        try {
            return net.mistraly.uno.UnoConfig.ENABLED.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static boolean answers() {
        return !DISTANT_HORIZONS && enabled();
    }

    // ---------------------------------------------------------------- answers for the DhDepth mixin

    public static boolean present() {
        return enabled();
    }

    public static int renderDistanceBlocks() {
        return enabled() ? distanceBlocks : -1;
    }

    /// The z row of a reversed-Z projection with Uno's planes, so that Vitrail's DhDepth.planes() gives them back.
    public static boolean zRow(Vector2f dest) {
        float near = NEAR_PLANE;
        float far = farPlane;
        if (far <= near) {
            return false;
        }
        dest.set(near / (far - near), far * near / (far - near));
        return true;
    }

    // ---------------------------------------------------------------- sections

    /// This frame's sections. Each has its own vertex buffers (Distant Horizons' vertex layout), and all share one
    /// index buffer of 32-bit quad indices starting at 0.
    public static void setSections(int count, int[] cornerX, int[] cornerY, int[] cornerZ, GpuBuffer[] opaqueVertices,
        int[] opaqueQuads, GpuBuffer[] waterVertices, int[] waterQuads, GpuBuffer indices) {
        List<DhLods.Section> opaqueList = new ArrayList<>(count);
        List<DhLods.Section> waterList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (opaqueQuads[i] > 0 && opaqueVertices[i] != null) {
                opaqueList.add(new DhLods.Section(cornerX[i], cornerY[i], cornerZ[i],
                    List.of(new DhLods.Piece(opaqueVertices[i], indices, opaqueQuads[i] * 6))));
            }
            if (waterQuads[i] > 0 && waterVertices[i] != null) {
                waterList.add(new DhLods.Section(cornerX[i], cornerY[i], cornerZ[i],
                    List.of(new DhLods.Piece(waterVertices[i], indices, waterQuads[i] * 6))));
            }
        }
        opaque = opaqueList;
        water = waterList;
    }

    /// Hands the opaque half to the pack. False when no pack draws far terrain; Uno then draws it itself.
    public static boolean drawOpaque() {
        drewOpaque = !opaque.isEmpty() && DistantDraw.draw(true, opaque);
        return drewOpaque;
    }

    /// Hands the water half to the pack, which Vitrail only takes after the opaque half of the same frame.
    public static boolean drawWater() {
        boolean drew = drewOpaque && (water.isEmpty() || DistantDraw.draw(false, water));
        drewOpaque = false;
        return drew;
    }
}
