package net.mistraly.uno.world;

/// A voxel is one int: bits 0-15 world-local block state id, 16-23 world-local biome id, 24-27 block light,
/// 28-31 sky light. State id 0 is air.
public final class Voxel {
    public static final int AIR = 0;

    private Voxel() {
    }

    public static int pack(int state, int biome, int sky, int block) {
        return (state & 0xFFFF) | ((biome & 0xFF) << 16) | ((block & 15) << 24) | (sky << 28);
    }

    public static int state(int voxel) {
        return voxel & 0xFFFF;
    }

    public static int biome(int voxel) {
        return (voxel >>> 16) & 0xFF;
    }

    public static int block(int voxel) {
        return (voxel >>> 24) & 15;
    }

    public static int sky(int voxel) {
        return voxel >>> 28;
    }

    public static int withLight(int voxel, int sky, int block) {
        return (voxel & 0x00FFFFFF) | ((block & 15) << 24) | (sky << 28);
    }
}
