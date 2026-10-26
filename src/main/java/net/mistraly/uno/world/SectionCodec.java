package net.mistraly.uno.world;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.nio.ByteBuffer;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

/// Section on disk: palette of distinct voxels + indices packed into the minimum number of bits, then LZ4.
/// Layout before compression: int paletteSize, int[paletteSize] palette, then ceil(32768 * bits / 64) longs.
final class SectionCodec {
    private static final LZ4Factory LZ4 = LZ4Factory.fastestInstance();

    private SectionCodec() {
    }

    static byte[] encode(int[] data) {
        Int2IntOpenHashMap index = new Int2IntOpenHashMap();
        index.defaultReturnValue(-1);
        IntArrayList palette = new IntArrayList();
        int[] indices = new int[LodKey.VOLUME];
        for (int i = 0; i < LodKey.VOLUME; i++) {
            int voxel = data[i];
            int id = index.get(voxel);
            if (id < 0) {
                id = palette.size();
                palette.add(voxel);
                index.put(voxel, id);
            }
            indices[i] = id;
        }

        int bits = bitsFor(palette.size());
        int longs = (LodKey.VOLUME * bits + 63) / 64;
        ByteBuffer raw = ByteBuffer.allocate(4 + palette.size() * 4 + longs * 8);
        raw.putInt(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            raw.putInt(palette.getInt(i));
        }
        if (bits > 0) {
            long acc = 0;
            int filled = 0;
            for (int i = 0; i < LodKey.VOLUME; i++) {
                acc |= (long) indices[i] << filled;
                filled += bits;
                if (filled >= 64) {
                    raw.putLong(acc);
                    filled -= 64;
                    acc = filled > 0 ? (long) indices[i] >>> (bits - filled) : 0;
                }
            }
            if (filled > 0) {
                raw.putLong(acc);
            }
        }

        LZ4Compressor compressor = LZ4.fastCompressor();
        byte[] rawBytes = raw.array();
        byte[] out = new byte[4 + compressor.maxCompressedLength(rawBytes.length)];
        ByteBuffer.wrap(out).putInt(rawBytes.length);
        int length = compressor.compress(rawBytes, 0, rawBytes.length, out, 4, out.length - 4);
        byte[] trimmed = new byte[4 + length];
        System.arraycopy(out, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    static int[] decode(byte[] blob) {
        int rawLength = ByteBuffer.wrap(blob).getInt();
        LZ4FastDecompressor decompressor = LZ4.fastDecompressor();
        byte[] rawBytes = new byte[rawLength];
        decompressor.decompress(blob, 4, rawBytes, 0, rawLength);
        ByteBuffer raw = ByteBuffer.wrap(rawBytes);

        int paletteSize = raw.getInt();
        int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            palette[i] = raw.getInt();
        }
        int[] data = new int[LodKey.VOLUME];
        int bits = bitsFor(paletteSize);
        if (bits == 0) {
            java.util.Arrays.fill(data, palette.length > 0 ? palette[0] : 0);
            return data;
        }
        long mask = (1L << bits) - 1;
        long acc = raw.getLong();
        int available = 64;
        for (int i = 0; i < LodKey.VOLUME; i++) {
            long value;
            if (available >= bits) {
                value = acc & mask;
                acc >>>= bits;
                available -= bits;
            } else {
                long next = raw.hasRemaining() ? raw.getLong() : 0;
                value = (acc | (next << available)) & mask;
                acc = next >>> (bits - available);
                available = 64 - (bits - available);
            }
            data[i] = palette[(int) value];
        }
        return data;
    }

    private static int bitsFor(int paletteSize) {
        return paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }
}
