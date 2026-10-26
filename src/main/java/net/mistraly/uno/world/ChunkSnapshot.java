package net.mistraly.uno.world;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.jspecify.annotations.Nullable;

/// Copy of a client chunk taken on the main thread, so the world thread can read it at leisure.
/// `surface[x + z * 16]` is the y of the highest non-air block; everything above it sees the sky, which also covers
/// chunks whose light data has not arrived yet.
public record ChunkSnapshot(
    int chunkX, int chunkZ, int minSectionY, @Nullable LevelChunkSection[] sections, @Nullable DataLayer[] skyLight,
    @Nullable DataLayer[] blockLight, int[] surface, boolean hasSkyLight
) {
    public static ChunkSnapshot of(LevelChunk chunk) {
        LevelChunkSection[] source = chunk.getSections();
        LevelChunkSection[] sections = new LevelChunkSection[source.length];
        DataLayer[] sky = new DataLayer[source.length];
        DataLayer[] block = new DataLayer[source.length];
        LayerLightEventListener skyListener = chunk.getLevel().getLightEngine().getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockListener = chunk.getLevel().getLightEngine().getLayerListener(LightLayer.BLOCK);
        int minSectionY = chunk.getMinSectionY();
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        for (int i = 0; i < source.length; i++) {
            LevelChunkSection section = source[i];
            sections[i] = section == null || section.hasOnlyAir() ? null : section.copy();
            DataLayer layer = skyListener.getDataLayerData(SectionPos.of(chunkX, minSectionY + i, chunkZ));
            sky[i] = layer == null ? null : layer.copy();
            DataLayer blockLayer = blockListener.getDataLayerData(SectionPos.of(chunkX, minSectionY + i, chunkZ));
            block[i] = blockLayer == null ? null : blockLayer.copy();
        }
        int[] surface = new int[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                surface[x + z * 16] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            }
        }
        return new ChunkSnapshot(chunkX, chunkZ, minSectionY, sections, sky, block, surface, chunk.getLevel().dimensionType().hasSkyLight());
    }
}
