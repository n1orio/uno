package net.mistraly.uno.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.util.ARGB;
import net.mistraly.uno.mesh.BlockMaterials;
import org.lwjgl.system.MemoryUtil;

/// Every material LODs use, as an RGBA32_FLOAT texel buffer, 4 texels each:
/// base atlas rect, overlay atlas rect, (base average rgb, flags), (overlay average rgb, overlay coverage).
/// New entries are appended as meshers discover materials.
final class MaterialTable implements AutoCloseable {
    private static final int ENTRY_BYTES = 64;

    final GpuBuffer buffer = RenderSystem.getDevice()
        .createBuffer(() -> "Uno materials", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, (long) BlockMaterials.MAX_MATERIALS * ENTRY_BYTES);
    int uploaded;

    void sync() {
        int count = BlockMaterials.materialCount();
        if (count <= this.uploaded) {
            this.uploaded = Math.min(this.uploaded, count);
            return;
        }
        ByteBuffer data = MemoryUtil.memAlloc((count - this.uploaded) * ENTRY_BYTES).order(ByteOrder.nativeOrder());
        try {
            for (int i = this.uploaded; i < count; i++) {
                BlockMaterials.MaterialEntry entry = BlockMaterials.material(i);
                data.putFloat(entry.u0()).putFloat(entry.v0()).putFloat(entry.u1()).putFloat(entry.v1());
                data.putFloat(entry.overlayU0()).putFloat(entry.overlayV0()).putFloat(entry.overlayU1()).putFloat(entry.overlayV1());
                putRgb(data, entry.baseAverage()).putFloat(entry.flags());
                putRgb(data, entry.overlayAverage()).putFloat(entry.hasOverlay() ? ARGB.alpha(entry.overlayAverage()) / 255.0F : 0.0F);
            }
            data.flip();
            RenderSystem.getDevice().createCommandEncoder()
                .writeToBuffer(this.buffer.slice((long) this.uploaded * ENTRY_BYTES, data.remaining()), data);
            this.uploaded = count;
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static ByteBuffer putRgb(ByteBuffer data, int argb) {
        return data.putFloat(ARGB.red(argb) / 255.0F).putFloat(ARGB.green(argb) / 255.0F).putFloat(ARGB.blue(argb) / 255.0F);
    }

    @Override
    public void close() {
        this.buffer.close();
    }
}
