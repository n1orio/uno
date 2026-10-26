package net.mistraly.uno.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.mistraly.uno.compat.SodiumSections;
import net.neoforged.fml.ModList;
import org.lwjgl.system.MemoryUtil;

/// Which 16^3 sections around the camera vanilla or Sodium actually draw, as an R8_UINT texel buffer
/// (1 = drawn). LODs are cut away exactly there: no gap while a chunk is still loading, no overlap at the edge.
final class SectionMask implements AutoCloseable {
    /// Largest render distance the buffer is sized for, in chunks.
    private static final int MAX_RADIUS = 66;
    private static final int UPDATE_EVERY = 3;

    private final int minSection;
    private final int sections;
    final GpuBuffer buffer;
    private final ByteBuffer cpu;
    private final boolean sodium = ModList.get().isLoaded("sodium");
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    /// Mask origin (chunk x, chunk z) and side length in chunks; valid once the first update ran.
    int originX;
    int originZ;
    int size;
    boolean valid;
    private long frame;

    SectionMask(int minY, int maxY) {
        this.minSection = Math.floorDiv(minY, 16);
        this.sections = Math.floorDiv(maxY - 1, 16) - this.minSection + 1;
        int side = MAX_RADIUS * 2 + 1;
        long bytes = (long) side * side * this.sections;
        this.buffer = RenderSystem.getDevice().createBuffer(() -> "Uno section mask", GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, bytes);
        this.cpu = MemoryUtil.memCalloc((int) bytes);
    }

    int minSection() {
        return this.minSection;
    }

    int sections() {
        return this.sections;
    }

    /// Re-reads which sections are drawn (every few frames). `hole` is the render distance in blocks: sections
    /// further above or below the camera than that are not drawn by vanilla even when built.
    void update(Vec3 camera, int hole) {
        if (this.valid && this.frame++ % UPDATE_EVERY != 0) {
            return;
        }
        int radius = Math.min(hole / 16 + 1, MAX_RADIUS);
        int side = radius * 2 + 1;
        int cameraChunkX = Math.floorDiv((int) Math.floor(camera.x), 16);
        int cameraChunkZ = Math.floorDiv((int) Math.floor(camera.z), 16);
        this.originX = cameraChunkX - radius;
        this.originZ = cameraChunkZ - radius;
        this.size = side;
        for (int sy = 0; sy < this.sections; sy++) {
            int sectionY = this.minSection + sy;
            boolean inHeight = Math.abs(sectionY * 16 + 8 - camera.y) <= hole + 8;
            for (int dz = 0; dz < side; dz++) {
                for (int dx = 0; dx < side; dx++) {
                    boolean drawn = inHeight && this.built(this.originX + dx, sectionY, this.originZ + dz);
                    this.cpu.put((sy * side + dz) * side + dx, (byte) (drawn ? 1 : 0));
                }
            }
        }
        RenderSystem.getDevice().createCommandEncoder()
            .writeToBuffer(this.buffer.slice(0, (long) side * side * this.sections), this.cpu.slice(0, side * side * this.sections));
        this.valid = true;
    }

    private boolean built(int x, int y, int z) {
        if (this.sodium) {
            return SodiumSections.built(x, y, z);
        }
        this.probe.set(x * 16 + 8, y * 16 + 8, z * 16 + 8);
        return Minecraft.getInstance().levelRenderer.isSectionCompiledAndVisible(this.probe, 0L);
    }

    /// Whether the section is drawn by vanilla / Sodium (false outside the mask).
    boolean drawn(int chunkX, int sectionY, int chunkZ) {
        if (!this.valid) {
            return false;
        }
        int dx = chunkX - this.originX;
        int dz = chunkZ - this.originZ;
        int sy = sectionY - this.minSection;
        if (dx < 0 || dz < 0 || dx >= this.size || dz >= this.size || sy < 0 || sy >= this.sections) {
            return false;
        }
        return this.cpu.get((sy * this.size + dz) * this.size + dx) != 0;
    }

    @Override
    public void close() {
        this.buffer.close();
        MemoryUtil.memFree(this.cpu);
    }
}
