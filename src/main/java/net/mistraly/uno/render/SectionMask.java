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

    /// Re-reads which sections are drawn, every frame. A built section only counts when it also passes Sodium's
    /// (and vanilla's) distance test: chunks the player just left stay loaded and built for a while but are no
    /// longer drawn, and cutting the LOD there left gaps.
    /// Returns whether anything changed since the last update.
    boolean update(Vec3 camera, int hole) {
        boolean changed = !this.valid;
        int radius = Math.min(hole / 16 + 1, MAX_RADIUS);
        int side = radius * 2 + 1;
        int cameraChunkX = Math.floorDiv((int) Math.floor(camera.x), 16);
        int cameraChunkZ = Math.floorDiv((int) Math.floor(camera.z), 16);
        int originX = cameraChunkX - radius;
        int originZ = cameraChunkZ - radius;
        changed |= originX != this.originX || originZ != this.originZ || side != this.size;
        this.originX = originX;
        this.originZ = originZ;
        this.size = side;
        float limit = hole;
        for (int sy = 0; sy < this.sections; sy++) {
            int sectionY = this.minSection + sy;
            double ny = nearestToZero(sectionY * 16 - 1 - camera.y, sectionY * 16 + 17 - camera.y);
            boolean inHeight = Math.abs(ny) < limit;
            for (int dz = 0; dz < side; dz++) {
                double nz = nearestToZero((this.originZ + dz) * 16 - 1 - camera.z, (this.originZ + dz) * 16 + 17 - camera.z);
                for (int dx = 0; dx < side; dx++) {
                    double nx = nearestToZero((this.originX + dx) * 16 - 1 - camera.x, (this.originX + dx) * 16 + 17 - camera.x);
                    // Sodium's test: nearest point of the (slightly grown) section box within a cylinder of the
                    // render distance
                    boolean inRange = inHeight && nx * nx + nz * nz < (double) limit * limit;
                    boolean drawn = inRange && this.built(this.originX + dx, sectionY, this.originZ + dz);
                    int index = (sy * side + dz) * side + dx;
                    byte value = (byte) (drawn ? 1 : 0);
                    if (this.cpu.get(index) != value) {
                        this.cpu.put(index, value);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            RenderSystem.getDevice().createCommandEncoder()
                .writeToBuffer(this.buffer.slice(0, (long) side * side * this.sections), this.cpu.slice(0, side * side * this.sections));
        }
        this.valid = true;
        return changed;
    }

    private static double nearestToZero(double min, double max) {
        return min > 0 ? min : (max < 0 ? max : 0);
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
