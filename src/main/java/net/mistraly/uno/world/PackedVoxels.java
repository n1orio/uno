package net.mistraly.uno.world;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.Arrays;

/// 32^3 voxels stored as a palette of distinct values plus indices packed into the fewest bits that fit
/// (values never straddle two longs). A section of one value takes a few bytes; typical terrain 20-40 KB
/// instead of 128 KB. The palette only grows; {@link #compacted()} drops entries no longer used.
final class PackedVoxels {
    private int[] palette;
    private int paletteSize;
    private final Int2IntOpenHashMap lookup = new Int2IntOpenHashMap();
    private long[] words;
    private int bits;
    private int perWord;

    private PackedVoxels(int bits, int[] palette, int paletteSize) {
        this.palette = palette;
        this.paletteSize = paletteSize;
        this.lookup.defaultReturnValue(-1);
        for (int i = 0; i < paletteSize; i++) {
            this.lookup.put(palette[i], i);
        }
        this.setBits(bits);
    }

    /// An independent copy (two array clones), so another thread can unpack it while this one keeps changing.
    PackedVoxels copy() {
        PackedVoxels copy = new PackedVoxels(this.bits, this.palette.clone(), this.paletteSize);
        copy.words = this.words.clone();
        return copy;
    }

    static PackedVoxels filled(int value) {
        return new PackedVoxels(0, new int[] {value, 0}, 1);
    }

    static PackedVoxels of(int[] data) {
        Int2IntOpenHashMap ids = new Int2IntOpenHashMap();
        ids.defaultReturnValue(-1);
        int[] palette = new int[16];
        int size = 0;
        for (int value : data) {
            if (ids.get(value) < 0) {
                if (size == palette.length) {
                    palette = Arrays.copyOf(palette, size * 2);
                }
                ids.put(value, size);
                palette[size++] = value;
            }
        }
        PackedVoxels packed = new PackedVoxels(bitsFor(size), palette, size);
        if (packed.bits > 0) {
            for (int i = 0; i < data.length; i++) {
                packed.write(i, ids.get(data[i]));
            }
        }
        return packed;
    }

    private static int bitsFor(int paletteSize) {
        return paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    private void setBits(int bits) {
        this.bits = bits;
        if (bits == 0) {
            this.perWord = 0;
            this.words = new long[0];
        } else {
            this.perWord = 64 / bits;
            this.words = new long[(LodKey.VOLUME + this.perWord - 1) / this.perWord];
        }
    }

    private int read(int index) {
        if (this.bits == 0) {
            return 0;
        }
        long word = this.words[index / this.perWord];
        return (int) ((word >>> ((index % this.perWord) * this.bits)) & ((1L << this.bits) - 1));
    }

    private void write(int index, int id) {
        int slot = index / this.perWord;
        int shift = (index % this.perWord) * this.bits;
        long mask = ((1L << this.bits) - 1) << shift;
        this.words[slot] = (this.words[slot] & ~mask) | ((long) id << shift);
    }

    int get(int index) {
        return this.palette[this.read(index)];
    }

    /// Returns whether the value changed.
    boolean set(int index, int value) {
        int id = this.lookup.get(value);
        if (id < 0) {
            id = this.paletteSize;
            if (id == this.palette.length) {
                this.palette = Arrays.copyOf(this.palette, id * 2);
            }
            this.palette[id] = value;
            this.lookup.put(value, id);
            this.paletteSize++;
            if (bitsFor(this.paletteSize) > this.bits) {
                this.repack(bitsFor(this.paletteSize));
            }
        } else if (this.read(index) == id) {
            return false;
        }
        this.write(index, id);
        return true;
    }

    private void repack(int newBits) {
        int[] ids = new int[LodKey.VOLUME];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = this.read(i);
        }
        this.setBits(newBits);
        for (int i = 0; i < ids.length; i++) {
            this.write(i, ids[i]);
        }
    }

    int[] toArray() {
        int[] out = new int[LodKey.VOLUME];
        if (this.bits == 0) {
            Arrays.fill(out, this.palette[0]);
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = this.palette[this.read(i)];
        }
        return out;
    }

    /// True when no voxel has a non-air state.
    boolean isEmpty() {
        boolean[] nonAir = new boolean[this.paletteSize];
        boolean any = false;
        for (int i = 0; i < this.paletteSize; i++) {
            nonAir[i] = Voxel.state(this.palette[i]) != Voxel.AIR;
            any |= nonAir[i];
        }
        if (!any) {
            return true;
        }
        if (this.bits == 0) {
            return !nonAir[0];
        }
        for (int i = 0; i < LodKey.VOLUME; i++) {
            if (nonAir[this.read(i)]) {
                return false;
            }
        }
        return true;
    }

    /// The same voxels with unused palette entries dropped (worth it when the palette is far larger than needed).
    PackedVoxels compacted() {
        return this.paletteSize > 16 ? of(this.toArray()) : this;
    }

    long bytes() {
        return this.words.length * 8L + this.palette.length * 4L + this.paletteSize * 16L;
    }
}
