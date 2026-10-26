package net.mistraly.uno.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.function.LongConsumer;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/// Chunk -> level 0 voxels -> mips up to level 4. World thread only.
public final class Ingester {
    private final WorldStore store;
    private final IdTables ids;
    /// Receives every section key whose voxels changed.
    private final LongConsumer changed;
    /// Air never captured (an all-air chunk section has no LOD section) is open sky in dimensions with one.
    private int openAir = Voxel.pack(Voxel.AIR, 0, 15, 0);

    public Ingester(WorldStore store, IdTables ids, LongConsumer changed) {
        this.store = store;
        this.ids = ids;
        this.changed = changed;
    }

    public void ingest(ChunkSnapshot chunk) {
        this.openAir = Voxel.pack(Voxel.AIR, 0, chunk.hasSkyLight() ? 15 : 0, 0);
        LongSet touched = new LongOpenHashSet();
        int ox = (chunk.chunkX() & 1) * 16;
        int oz = (chunk.chunkZ() & 1) * 16;
        int defaultSky = chunk.hasSkyLight() ? 15 : 0;
        int[] column = new int[16 * 16 * 16];
        int[] surface = chunk.hasSkyLight() ? this.solidSurface(chunk) : null;

        for (int i = 0; i < chunk.sections().length; i++) {
            int sectionY = chunk.minSectionY() + i;
            long key = LodKey.pack(0, chunk.chunkX() >> 1, sectionY >> 1, chunk.chunkZ() >> 1);
            LevelChunkSection section = chunk.sections()[i];
            DataLayer sky = chunk.skyLight()[i];

            this.fill(column, section, sky, chunk.blockLight()[i], defaultSky, surface, sectionY * 16);
            LodSection target = section == null ? this.store.get(key) : this.store.getOrCreate(key);
            if (target == null) {
                continue;
            }
            int oy = (sectionY & 1) * 16;
            boolean dirty = false;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int voxel = column[(y << 8) | (z << 4) | x];
                        int index = LodKey.index(ox + x, oy + y, oz + z);
                        dirty |= target.set(index, voxel);
                    }
                }
            }
            if (dirty) {
                target.touch();
                this.store.commit(target);
                touched.add(key);
            }
        }

        LongSet level = touched;
        for (int l = 0; l < LodKey.MAX_LEVEL && !level.isEmpty(); l++) {
            level.forEach(this.changed::accept);
            LongSet parents = new LongOpenHashSet();
            for (long childKey : level) {
                if (this.downsample(childKey)) {
                    parents.add(LodKey.parent(childKey));
                }
            }
            level = parents;
        }
        level.forEach(this.changed::accept);
    }

    /// Per column, the y of the highest truly opaque block (leaves, plants and glass do not count), or MIN_VALUE.
    /// Everything above it sees the sky.
    private int[] solidSurface(ChunkSnapshot chunk) {
        int[] surface = new int[256];
        java.util.Arrays.fill(surface, Integer.MIN_VALUE);
        int found = 0;
        for (int i = chunk.sections().length - 1; i >= 0 && found < 256; i--) {
            LevelChunkSection section = chunk.sections()[i];
            if (section == null) {
                continue;
            }
            int baseY = (chunk.minSectionY() + i) * 16;
            PalettedContainer<BlockState> states = section.getStates();
            for (int column = 0; column < 256; column++) {
                if (surface[column] != Integer.MIN_VALUE) {
                    continue;
                }
                int x = column & 15;
                int z = column >> 4;
                for (int y = 15; y >= 0; y--) {
                    if (BlockKind.classify(states.get(x, y, z)) == BlockKind.OPAQUE) {
                        surface[column] = baseY + y;
                        found++;
                        break;
                    }
                }
            }
        }
        return surface;
    }

    /// Sky light of a block: the light engine's value, but at least 15 above the solid surface. Light data can still
    /// be missing (all zeros) when a chunk is captured, which used to leave whole chunks black; the vanilla
    /// heightmap counts leaves, so forest floors stayed black even with it.
    private static int skyLight(DataLayer sky, int defaultSky, int @org.jspecify.annotations.Nullable [] surface, int baseY, int x, int y, int z) {
        int light = sky == null ? defaultSky : sky.get(x, y, z);
        if (surface != null && baseY + y > surface[x + (z << 4)]) {
            light = 15;
        }
        return light;
    }

    private void fill(int[] out, LevelChunkSection section, DataLayer sky, DataLayer block, int defaultSky, int @org.jspecify.annotations.Nullable [] surface, int baseY) {
        if (section == null) {
            for (int i = 0; i < out.length; i++) {
                int x = i & 15, y = i >> 8, z = (i >> 4) & 15;
                out[i] = Voxel.pack(Voxel.AIR, 0, skyLight(sky, defaultSky, surface, baseY, x, y, z), block == null ? 0 : block.get(x, y, z));
            }
            return;
        }
        PalettedContainer<BlockState> states = section.getStates();
        PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes();
        int[] biomeIds = new int[64];
        for (int b = 0; b < 64; b++) {
            biomeIds[b] = this.ids.biomeId(biomes.get(b & 3, b >> 4, (b >> 2) & 3));
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int state = this.ids.stateId(states.get(x, y, z));
                    int light = skyLight(sky, defaultSky, surface, baseY, x, y, z);
                    int biome = state == Voxel.AIR ? 0 : biomeIds[((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)];
                    out[(y << 8) | (z << 4) | x] = Voxel.pack(state, biome, light, block == null ? 0 : block.get(x, y, z));
                }
            }
        }
    }

    /// Rebuilds the parent octant covered by `childKey`. Returns whether the parent changed.
    private boolean downsample(long childKey) {
        LodSection child = this.store.get(childKey);
        long parentKey = LodKey.parent(childKey);
        LodSection parent = child == null ? this.store.get(parentKey) : this.store.getOrCreate(parentKey);
        if (parent == null) {
            return false;
        }
        int px = (LodKey.x(childKey) & 1) * 16;
        int py = (LodKey.y(childKey) & 1) * 16;
        int pz = (LodKey.z(childKey) & 1) * 16;
        LodSection source = child;
        int[] group = new int[8];
        boolean dirty = false;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int voxel;
                    if (source == null) {
                        // a child that was never created is air; as plain 0 it had sky light 0, which made water
                        // surfaces and tree tops at section borders below it render nearly black
                        voxel = this.openAir;
                    } else {
                        // top layer first, so mip() prefers what is visible from above
                        int n = 0;
                        for (int dy = 1; dy >= 0; dy--) {
                            for (int dz = 0; dz < 2; dz++) {
                                for (int dx = 0; dx < 2; dx++) {
                                    group[n++] = source.get(LodKey.index(x * 2 + dx, y * 2 + dy, z * 2 + dz));
                                }
                            }
                        }
                        voxel = this.mip(group);
                    }
                    int index = LodKey.index(px + x, py + y, pz + z);
                    dirty |= parent.set(index, voxel);
                }
            }
        }
        if (dirty) {
            parent.touch();
            this.store.commit(parent);
        }
        return dirty;
    }

    /// Picks one voxel for a 2x2x2 group (top four first). The top layer decides, because that is what is seen
    /// from above: shallow water over sand stays water instead of turning into a beach. Otherwise solid wins if any
    /// of the eight is solid, so thin layers survive at distance; partial blocks need half of the group so a lone
    /// fence post does not grow into a cube.
    private int mip(int[] group) {
        int sky = 0;
        int block = 0;
        int partialCount = 0;
        int fluid = -1;
        int partial = -1;
        for (int voxel : group) {
            sky = Math.max(sky, Voxel.sky(voxel));
            block = Math.max(block, Voxel.block(voxel));
        }
        int topOpaque = this.countSolid(group, 0, 4);
        int topFluid = this.countFluid(group, 0, 4);
        if (topFluid > topOpaque) {
            return Voxel.withLight(this.firstFluid(group, 0, 4), sky, block);
        }
        int best = topOpaque > 0 ? this.mostCommonSolid(group, 0, 4) : -1;
        if (best == -1 && topFluid == 0) {
            best = this.mostCommonSolid(group, 4, 8);
        }
        if (best != -1) {
            return Voxel.withLight(best, sky, block);
        }
        for (int voxel : group) {
            byte kind = this.ids.kind(Voxel.state(voxel));
            if (BlockKind.fluid(kind) && fluid == -1) {
                fluid = voxel;
            } else if (kind == BlockKind.PARTIAL) {
                partialCount++;
                if (partial == -1) {
                    partial = voxel;
                }
            }
        }
        if (fluid != -1) {
            return Voxel.withLight(fluid, sky, block);
        }
        if (partialCount >= 4) {
            return Voxel.withLight(partial, sky, block);
        }
        return Voxel.pack(Voxel.AIR, 0, sky, block);
    }

    private int countFluid(int[] group, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (BlockKind.fluid(this.ids.kind(Voxel.state(group[i])))) {
                count++;
            }
        }
        return count;
    }

    /// Prefers plain fluid over fluid-with-model (seagrass), so coarse water does not inherit a plant's id.
    private int firstFluid(int[] group, int from, int to) {
        int fallback = -1;
        for (int i = from; i < to; i++) {
            byte kind = this.ids.kind(Voxel.state(group[i]));
            if (kind == BlockKind.FLUID) {
                return group[i];
            }
            if (kind == BlockKind.FLUID_MODEL && fallback == -1) {
                fallback = group[i];
            }
        }
        return fallback;
    }

    private int countSolid(int[] group, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (BlockKind.solid(this.ids.kind(Voxel.state(group[i])))) {
                count++;
            }
        }
        return count;
    }

    private int mostCommonSolid(int[] group, int from, int to) {
        int best = this.mostCommon(group, from, to, BlockKind.OPAQUE);
        if (best == -1) {
            best = this.mostCommon(group, from, to, BlockKind.LEAVES);
        }
        return best;
    }

    private int count(int[] group, int from, int to, byte kind) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (this.ids.kind(Voxel.state(group[i])) == kind) {
                count++;
            }
        }
        return count;
    }

    private int mostCommon(int[] group, int from, int to, byte kind) {
        int best = -1;
        int bestCount = 0;
        for (int i = from; i < to; i++) {
            int state = Voxel.state(group[i]);
            if (this.ids.kind(state) != kind) {
                continue;
            }
            int count = 0;
            for (int j = from; j < to; j++) {
                if (Voxel.state(group[j]) == state) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = group[i];
            }
        }
        return best;
    }
}
