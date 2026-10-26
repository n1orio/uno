package net.mistraly.uno.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import it.unimi.dsi.fastutil.ints.Int2IntRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import java.nio.ByteBuffer;

/// One big GPU buffer of fixed-size units (quads), with a first-fit allocator over free ranges merged on free.
/// Used for the packed LOD quads (texel buffer) and for model vertices (vertex buffer).
final class QuadArena implements AutoCloseable {
    final GpuBuffer buffer;
    final int capacity;
    private final int unitBytes;
    /// start -> length of every free range
    private final Int2IntRBTreeMap free = new Int2IntRBTreeMap();
    private int used;

    QuadArena(String label, int megabytes, int unitBytes, @GpuBuffer.Usage int usage) {
        long bytes = (long) megabytes * 1024 * 1024;
        this.unitBytes = unitBytes;
        this.capacity = (int) (bytes / unitBytes);
        this.buffer = RenderSystem.getDevice().createBuffer(() -> label, usage | GpuBuffer.USAGE_COPY_DST, (long) this.capacity * unitBytes);
        this.free.put(0, this.capacity);
    }

    /// Returns the first quad of the allocation, or -1 when no range is large enough.
    int allocate(int quads) {
        for (Int2IntMap.Entry range : this.free.int2IntEntrySet()) {
            int start = range.getIntKey();
            int length = range.getIntValue();
            if (length >= quads) {
                this.free.remove(start);
                if (length > quads) {
                    this.free.put(start + quads, length - quads);
                }
                this.used += quads;
                return start;
            }
        }
        return -1;
    }

    void release(int start, int quads) {
        this.used -= quads;
        int end = start + quads;
        int length = quads;
        // merge with the following range
        if (this.free.containsKey(end)) {
            length += this.free.remove(end);
        }
        // merge with the preceding range
        var before = this.free.headMap(start);
        if (!before.isEmpty()) {
            int previous = before.lastIntKey();
            int previousLength = this.free.get(previous);
            if (previous + previousLength == start) {
                start = previous;
                length += previousLength;
            }
        }
        this.free.put(start, length);
    }

    void upload(int start, ByteBuffer quads) {
        RenderSystem.getDevice().createCommandEncoder()
            .writeToBuffer(this.buffer.slice((long) start * this.unitBytes, quads.remaining()), quads);
    }

    int used() {
        return this.used;
    }

    @Override
    public void close() {
        this.buffer.close();
    }
}
