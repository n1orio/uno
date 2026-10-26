package net.mistraly.uno.world;

/// Addressing of LOD sections and voxels.
///
/// A section is 32^3 voxels; at level L a voxel is 2^L blocks, so a section spans 32 * 2^L blocks per axis.
/// Section coordinates are in units of that span. Key layout: level (4 bits) | y (8 bits) | x (24 bits) | z (24 bits).
public final class LodKey {
    public static final int SIZE = 32;
    public static final int SHIFT = 5;
    public static final int VOLUME = SIZE * SIZE * SIZE;
    public static final int MAX_LEVEL = 4;
    public static final int LEVELS = MAX_LEVEL + 1;

    private LodKey() {
    }

    public static long pack(int level, int x, int y, int z) {
        return ((long) level << 60) | ((long) (y & 0xFF) << 48) | ((long) (x & 0xFFFFFF) << 24) | (z & 0xFFFFFFL);
    }

    public static int level(long key) {
        return (int) (key >>> 60);
    }

    public static int y(long key) {
        return (byte) (key >>> 48);
    }

    public static int x(long key) {
        return ((int) (key >>> 24)) << 8 >> 8;
    }

    public static int z(long key) {
        return ((int) key) << 8 >> 8;
    }

    public static long parent(long key) {
        return pack(level(key) + 1, x(key) >> 1, y(key) >> 1, z(key) >> 1);
    }

    /// Child `i` (0..7): bit 0 = +x, bit 1 = +y, bit 2 = +z.
    public static long child(long key, int i) {
        return pack(level(key) - 1, x(key) * 2 + (i & 1), y(key) * 2 + ((i >> 1) & 1), z(key) * 2 + ((i >> 2) & 1));
    }

    public static long neighbor(long key, int dx, int dy, int dz) {
        return pack(level(key), x(key) + dx, y(key) + dy, z(key) + dz);
    }

    /// Size of the section in blocks.
    public static int span(int level) {
        return SIZE << level;
    }

    public static int originX(long key) {
        return x(key) * span(level(key));
    }

    public static int originY(long key) {
        return y(key) * span(level(key));
    }

    public static int originZ(long key) {
        return z(key) * span(level(key));
    }

    public static int index(int x, int y, int z) {
        return (y << 10) | (z << 5) | x;
    }

    public static String toString(long key) {
        return "L" + level(key) + "[" + x(key) + "," + y(key) + "," + z(key) + "]";
    }
}
