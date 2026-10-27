package net.mistraly.uno.world;

import net.mistraly.uno.mesh.Mesher;

/// 32^3 voxels, kept packed (palette + bit-packed indices). Owned by the world thread; meshers only ever see copies.
public final class LodSection {
    public final long key;
    private PackedVoxels voxels;
    /// Bumped on every change so stale meshes can be recognised.
    public int version;
    public boolean dirty;

    public LodSection(long key, int[] data) {
        this.key = key;
        this.voxels = PackedVoxels.of(data);
    }

    public LodSection(long key) {
        this.key = key;
        this.voxels = PackedVoxels.filled(Voxel.AIR);
    }

    public int get(int index) {
        return this.voxels.get(index);
    }

    /// Returns whether the voxel changed. Call {@link #touch()} after a batch of changes.
    public boolean set(int index, int voxel) {
        return this.voxels.set(index, voxel);
    }

    public void touch() {
        this.version++;
        this.dirty = true;
    }

    public int[] toArray() {
        return this.voxels.toArray();
    }

    public boolean isEmpty() {
        return this.voxels.isEmpty();
    }

    /// Drops palette entries that edits left unused.
    public void compact() {
        this.voxels = this.voxels.compacted();
    }

    /// A copy of the voxels another thread may read (see {@link #plane(PackedVoxels, int)}).
    public PackedVoxels snapshot() {
        return this.voxels.copy();
    }

    public static int[] plane(PackedVoxels voxels, int d) {
        int s = (d & 1) == 1 ? 0 : LodKey.SIZE - 1;
        int[] out = new int[LodKey.SIZE * LodKey.SIZE];
        for (int v = 0; v < LodKey.SIZE; v++) {
            for (int u = 0; u < LodKey.SIZE; u++) {
                out[v * LodKey.SIZE + u] = voxels.get(Mesher.index(d, s, u, v));
            }
        }
        return out;
    }

    /// The layer touching a neighbour lying in direction d, in that neighbour's (u, v) layout (see {@link Mesher}).
    public int[] plane(int d) {
        int s = (d & 1) == 1 ? 0 : LodKey.SIZE - 1;
        int[] out = new int[LodKey.SIZE * LodKey.SIZE];
        for (int v = 0; v < LodKey.SIZE; v++) {
            for (int u = 0; u < LodKey.SIZE; u++) {
                out[v * LodKey.SIZE + u] = this.voxels.get(Mesher.index(d, s, u, v));
            }
        }
        return out;
    }
}
