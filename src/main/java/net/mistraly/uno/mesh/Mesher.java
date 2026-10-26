package net.mistraly.uno.mesh;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.mistraly.uno.world.BlockKind;
import net.mistraly.uno.world.IdTables;
import net.mistraly.uno.world.LodKey;
import net.mistraly.uno.world.Voxel;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/// Greedy mesher: per direction and per slice, a 32x32 mask of face keys is merged into rectangles.
///
/// Quad = 4 x uint32:
/// word 0: x 0-4, y 5-9, z 10-14 (min corner), width-1 15-19, height-1 20-24, direction 25-27, sky light 28-31;
/// word 1: material from {@link BlockMaterials}: material index 0-15, tint RGB565 16-31;
/// word 2: block light 0-3, ambient occlusion 4-11 (2 bits per corner, corner = cu + 2 * cv, 3 = open);
/// word 3: unused.
/// Width runs along u and height along v: Y faces u=x v=z, Z faces u=x v=y, X faces u=z v=y.
public final class Mesher {
    public static final int QUAD_BYTES = 16;
    private static final int S = LodKey.SIZE;
    // face key: material 0-31, sky 32-35, block light 36-39, marker 40, fluid 41, ambient occlusion 42-49
    private static final long MARKER = 1L << 40;
    private static final long FLUID = 1L << 41;
    private static final int AO_OPEN = 0xFF;
    /// Boundary faces of voxels this close to the surface are always emitted ("skirts"), so the crack between
    /// a fine and a coarse neighbour, whose terrain heights differ, is closed by a wall instead of showing sky.
    private static final int SKIRT_DEPTH = 2;

    private final IdTables ids;
    private int level;
    private final long[] mask = new long[S * S];
    /// Opaque quads per face direction, so the renderer can skip directions facing away from the camera.
    private final IntArrayList[] quads = {
        new IntArrayList(), new IntArrayList(), new IntArrayList(), new IntArrayList(), new IntArrayList(), new IntArrayList()
    };
    private final IntArrayList fluidQuads = new IntArrayList();
    private final int[] directionCounts = new int[6];
    private int occluderTop;
    private int fluidCount;
    /// Real model geometry (level 0 only), {@link #MODEL_VERTEX_BYTES} per vertex, 4 vertices per quad.
    private @Nullable ByteBuffer models;
    private int modelCount;

    public static final int MODEL_VERTEX_BYTES = 28;
    public static final int MODEL_QUAD_BYTES = MODEL_VERTEX_BYTES * 4;
    private static final float[] SHADE = {0.5F, 1.0F, 0.8F, 0.8F, 0.6F, 0.6F};

    public Mesher(IdTables ids) {
        this.ids = ids;
    }

    /// Number of fluid quads at the end of the last buffer returned by {@link #mesh}.
    public int fluidCount() {
        return this.fluidCount;
    }

    /// Height (in voxels from the section bottom) below which every column of the last section is opaque,
    /// or -1 when some column has no opaque voxel. Used as an occluder for horizon culling.
    public int occluderTop() {
        return this.occluderTop;
    }

    /// Opaque quads per direction (DOWN, UP, NORTH, SOUTH, WEST, EAST) of the last buffer, stored in that order.
    public int[] directionCounts() {
        return this.directionCounts.clone();
    }

    /// Model geometry of the last {@link #mesh} call, owned by the caller (free with MemoryUtil), or null.
    public @Nullable ByteBuffer takeModels() {
        ByteBuffer result = this.models;
        this.models = null;
        return result;
    }

    public int modelCount() {
        return this.modelCount;
    }

    /// `neighbors[d]` is the layer of the adjacent section touching face direction d, indexed `v * 32 + u`, or null.
    /// Returns a direct buffer owned by the caller (free with MemoryUtil), or null when the section has no faces.
    /// Opaque quads come first, grouped by direction, fluid quads (drawn translucent) last.
    public @Nullable ByteBuffer mesh(int[] data, @Nullable int[][] neighbors, int level) {
        for (IntArrayList list : this.quads) {
            list.clear();
        }
        this.fluidQuads.clear();
        this.models = null;
        this.modelCount = 0;
        this.level = level;
        this.occluderTop = this.occluder(data);
        if (level == 0) {
            this.meshModels(data);
        }
        for (int d = 0; d < 6; d++) {
            for (int s = 0; s < S; s++) {
                if (this.buildMask(data, neighbors[d], d, s)) {
                    this.merge(d, s);
                }
            }
        }
        this.fluidCount = this.fluidQuads.size() / 4;
        int ints = this.fluidQuads.size();
        for (int d = 0; d < 6; d++) {
            this.directionCounts[d] = this.quads[d].size() / 4;
            ints += this.quads[d].size();
        }
        if (ints == 0) {
            return null;
        }
        ByteBuffer out = MemoryUtil.memAlloc(ints * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (IntArrayList list : this.quads) {
            for (int i = 0; i < list.size(); i++) {
                out.putInt(list.getInt(i));
            }
        }
        for (int i = 0; i < this.fluidQuads.size(); i++) {
            out.putInt(this.fluidQuads.getInt(i));
        }
        out.flip();
        return out;
    }

    /// Voxel coordinates of (slice, u, v) for direction d.
    public static int index(int d, int s, int u, int v) {
        return switch (d) {
            case 0, 1 -> LodKey.index(u, s, v);
            case 2, 3 -> LodKey.index(u, v, s);
            default -> LodKey.index(s, v, u);
        };
    }

    private static boolean positive(int d) {
        return (d & 1) == 1;
    }

    private boolean buildMask(int[] data, int @Nullable [] neighborPlane, int d, int s) {
        boolean any = false;
        int next = positive(d) ? s + 1 : s - 1;
        boolean outside = next < 0 || next >= S;
        for (int v = 0; v < S; v++) {
            for (int u = 0; u < S; u++) {
                int self = data[index(d, s, u, v)];
                int selfState = Voxel.state(self);
                byte selfKind = this.ids.kind(selfState);
                if (this.level == 0 && BlockKind.modelled(selfKind) && BlockModels.quads(this.ids.state(selfState)).length > 0) {
                    // drawn by meshModels with its real shape (blocks without model quads, like chests, stay cubes)
                    selfKind = BlockKind.AIR;
                }
                long key = 0;
                if (selfKind != BlockKind.AIR) {
                    int neighbor;
                    boolean known;
                    if (!outside) {
                        neighbor = data[index(d, next, u, v)];
                        known = true;
                    } else if (neighborPlane != null) {
                        neighbor = neighborPlane[v * S + u];
                        known = true;
                    } else {
                        neighbor = self;
                        known = false;
                    }
                    byte neighborKind = known ? this.ids.kind(Voxel.state(neighbor)) : BlockKind.AIR;
                    boolean visible = !BlockKind.hides(selfKind, neighborKind);
                    int sky = Voxel.sky(neighbor);
                    int block = known ? Voxel.block(neighbor) : Voxel.block(self);
                    int ao = AO_OPEN;
                    if (!visible && outside && d >= 2 && selfKind == BlockKind.OPAQUE) {
                        int skirtSky = this.surfaceSky(data, d, s, u, v);
                        if (skirtSky >= 0) {
                            visible = true;
                            sky = skirtSky;
                        }
                    } else if (visible && !BlockKind.fluid(selfKind)) {
                        ao = this.occlusion(data, neighborPlane, d, next, outside, u, v);
                    }
                    if (visible) {
                        int material = BlockMaterials.material(this.ids, selfState, Voxel.biome(self), d);
                        key = MARKER | (BlockKind.fluid(selfKind) ? FLUID : 0) | ((long) ao << 42) | ((long) block << 36)
                            | ((long) sky << 32) | (material & 0xFFFFFFFFL);
                        any = true;
                    }
                }
                this.mask[v * S + u] = key;
            }
        }
        return any;
    }

    /// The lowest column top over all 32x32 columns, counting only truly opaque voxels (not leaves).
    /// Caves under a column top are ignored: seen from outside, the terrain surface hides them.
    private int occluder(int[] data) {
        int lowest = S;
        for (int z = 0; z < S; z++) {
            for (int x = 0; x < S; x++) {
                int top = -1;
                for (int y = S - 1; y >= 0; y--) {
                    if (this.ids.kind(Voxel.state(data[LodKey.index(x, y, z)])) == BlockKind.OPAQUE) {
                        top = y;
                        break;
                    }
                }
                if (top < 0) {
                    return -1;
                }
                lowest = Math.min(lowest, top + 1);
            }
        }
        return lowest;
    }

    /// Vanilla-style ambient occlusion of a face: for each corner, how many of the two edge neighbours and the
    /// diagonal neighbour in the layer in front of the face are solid. 2 bits per corner, 3 = fully open.
    private int occlusion(int[] data, int @Nullable [] plane, int d, int front, boolean outside, int u, int v) {
        int ao = 0;
        for (int corner = 0; corner < 4; corner++) {
            int du = (corner & 1) == 1 ? 1 : -1;
            int dv = (corner & 2) == 2 ? 1 : -1;
            boolean side1 = this.occluder(data, plane, d, front, outside, u + du, v);
            boolean side2 = this.occluder(data, plane, d, front, outside, u, v + dv);
            boolean diagonal = this.occluder(data, plane, d, front, outside, u + du, v + dv);
            int value = side1 && side2 ? 0 : 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (diagonal ? 1 : 0));
            ao |= value << (corner * 2);
        }
        return ao;
    }

    private boolean occluder(int[] data, int @Nullable [] plane, int d, int front, boolean outside, int u, int v) {
        if (u < 0 || v < 0 || u >= S || v >= S) {
            return false;
        }
        int voxel;
        if (!outside) {
            voxel = data[index(d, front, u, v)];
        } else if (plane != null) {
            voxel = plane[v * S + u];
        } else {
            return false;
        }
        return BlockKind.solid(this.ids.kind(Voxel.state(voxel)));
    }

    /// Writes the real model quads of every modelled block (fences, lanterns, plants, ...) of a level-0 section.
    /// Faces with a cull direction are dropped when the neighbour there is opaque, like vanilla.
    private void meshModels(int[] data) {
        int capacity = 0;
        ByteBuffer out = null;
        int count = 0;
        for (int index = 0; index < data.length; index++) {
            int voxel = data[index];
            int stateId = Voxel.state(voxel);
            if (stateId == Voxel.AIR || !BlockKind.hasModel(this.ids.kind(stateId))) {
                continue;
            }
            int x = index & 31;
            int z = (index >> 5) & 31;
            int y = index >> 10;
            // light inside a partial block is often 0 (stonecutter, slabs); use the brighter of it and the block above
            int sky = Voxel.sky(voxel);
            int light = Voxel.block(voxel);
            if (y + 1 < S) {
                int above = data[LodKey.index(x, y + 1, z)];
                sky = Math.max(sky, Voxel.sky(above));
                light = Math.max(light, Voxel.block(above));
            }
            BlockModels.ModelQuad[] modelQuads = BlockModels.quads(this.ids.state(stateId));
            for (BlockModels.ModelQuad quad : modelQuads) {
                if (quad.cullFace() >= 0 && this.opaqueNeighbor(data, x, y, z, quad.cullFace())) {
                    continue;
                }
                if (out == null || (count + 1) * MODEL_QUAD_BYTES > capacity) {
                    capacity = Math.max(capacity * 2, 64 * MODEL_QUAD_BYTES);
                    ByteBuffer grown = MemoryUtil.memAlloc(capacity).order(ByteOrder.LITTLE_ENDIAN);
                    if (out != null) {
                        out.flip();
                        grown.put(out);
                        MemoryUtil.memFree(out);
                    }
                    out = grown;
                }
                int tint = quad.tintIndex() >= 0 ? BlockMaterials.tintColor(this.ids, stateId, Voxel.biome(voxel), quad.tintIndex()) : -1;
                float shade = SHADE[quad.shadeFace()];
                int r = (int) ((tint == -1 ? 255 : (tint >> 16) & 255) * shade);
                int g = (int) ((tint == -1 ? 255 : (tint >> 8) & 255) * shade);
                int b = (int) ((tint == -1 ? 255 : tint & 255) * shade);
                int color = r | (g << 8) | (b << 16) | (255 << 24);
                short blockLight = (short) (Math.max(light, quad.emission()) * 16);
                short skyLight = (short) (sky * 16);
                float[] p = quad.positions();
                float[] uv = quad.uvs();
                for (int i = 0; i < 4; i++) {
                    out.putFloat(x + p[i * 3]).putFloat(y + p[i * 3 + 1]).putFloat(z + p[i * 3 + 2]);
                    out.putFloat(uv[i * 2]).putFloat(uv[i * 2 + 1]);
                    out.putInt(color);
                    out.putShort(blockLight).putShort(skyLight);
                }
                count++;
            }
        }
        if (out != null) {
            out.flip();
        }
        this.models = out;
        this.modelCount = count;
    }

    private boolean opaqueNeighbor(int[] data, int x, int y, int z, int direction) {
        switch (direction) {
            case 0 -> y--;
            case 1 -> y++;
            case 2 -> z--;
            case 3 -> z++;
            case 4 -> x--;
            default -> x++;
        }
        if (x < 0 || y < 0 || z < 0 || x >= S || y >= S || z >= S) {
            return false;
        }
        return this.ids.kind(Voxel.state(data[LodKey.index(x, y, z)])) == BlockKind.OPAQUE;
    }

    /// For a side voxel at (s, u, v): the sky light of the first non-opaque voxel within {@link #SKIRT_DEPTH}
    /// above it, or -1 when it is buried deeper than that. Above the section counts as open.
    private int surfaceSky(int[] data, int d, int s, int u, int v) {
        for (int up = 1; up <= SKIRT_DEPTH; up++) {
            if (v + up >= S) {
                return 15;
            }
            int above = data[index(d, s, u, v + up)];
            if (this.ids.kind(Voxel.state(above)) != BlockKind.OPAQUE) {
                return Voxel.sky(above);
            }
        }
        return -1;
    }

    private void merge(int d, int s) {
        long[] m = this.mask;
        for (int v = 0; v < S; v++) {
            for (int u = 0; u < S; ) {
                long key = m[v * S + u];
                if (key == 0) {
                    u++;
                    continue;
                }
                // faces with uneven occlusion are not merged: the corner values would smear over the whole rectangle
                int ao = (int) (key >>> 42) & 0xFF;
                boolean mergeable = ao == 0x00 || ao == 0x55 || ao == 0xAA || ao == 0xFF;
                int w = 1;
                while (mergeable && u + w < S && m[v * S + u + w] == key) {
                    w++;
                }
                int h = 1;
                grow:
                while (mergeable && v + h < S) {
                    int row = (v + h) * S;
                    for (int k = 0; k < w; k++) {
                        if (m[row + u + k] != key) {
                            break grow;
                        }
                    }
                    h++;
                }
                for (int dv = 0; dv < h; dv++) {
                    int row = (v + dv) * S;
                    for (int k = 0; k < w; k++) {
                        m[row + u + k] = 0;
                    }
                }
                this.emit(d, s, u, v, w, h, key);
                u += w;
            }
        }
    }

    private void emit(int d, int s, int u, int v, int w, int h, long key) {
        int x, y, z;
        switch (d) {
            case 0, 1 -> { x = u; y = s; z = v; }
            case 2, 3 -> { x = u; y = v; z = s; }
            default -> { x = s; y = v; z = u; }
        }
        int sky = (int) (key >>> 32) & 15;
        int block = (int) (key >>> 36) & 15;
        int ao = (int) (key >>> 42) & 0xFF;
        IntArrayList target = (key & FLUID) != 0 ? this.fluidQuads : this.quads[d];
        target.add(x | (y << 5) | (z << 10) | ((w - 1) << 15) | ((h - 1) << 20) | (d << 25) | (sky << 28));
        target.add((int) key);
        target.add(block | (ao << 4));
        target.add(0);
    }

    /// The layer of `section` that touches a neighbour lying in direction d, in that neighbour's (u, v) layout.
    public static int[] plane(int[] section, int d) {
        // neighbour in direction d sees our layer at the opposite side
        int s = positive(d) ? 0 : S - 1;
        int[] out = new int[S * S];
        for (int v = 0; v < S; v++) {
            for (int u = 0; u < S; u++) {
                out[v * S + u] = section[index(d, s, u, v)];
            }
        }
        return out;
    }
}
