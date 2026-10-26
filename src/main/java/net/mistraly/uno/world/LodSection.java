package net.mistraly.uno.world;

/// 32^3 voxels. Owned by the world thread; meshers only ever see copies.
public final class LodSection {
    public final long key;
    public final int[] data;
    /// Bumped on every change so stale meshes can be recognised.
    public int version;
    public boolean dirty;

    public LodSection(long key, int[] data) {
        this.key = key;
        this.data = data;
    }

    public LodSection(long key) {
        this(key, new int[LodKey.VOLUME]);
    }

    public void touch() {
        this.version++;
        this.dirty = true;
    }

    public boolean isEmpty() {
        for (int voxel : this.data) {
            if (Voxel.state(voxel) != Voxel.AIR) {
                return false;
            }
        }
        return true;
    }
}
