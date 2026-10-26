package net.mistraly.uno;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class UnoConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
        .comment("Render distant terrain")
        .define("enabled", true);

    public static final ModConfigSpec.IntValue LOD_DISTANCE = BUILDER
        .comment("How far LODs are drawn, in blocks")
        .defineInRange("lodDistance", 2048, 256, 16384);

    public static final ModConfigSpec.IntValue DETAIL_DISTANCE = BUILDER
        .comment("Full-detail (1 block) LODs reach this many blocks past the render distance; each coarser level doubles it")
        .defineInRange("detailDistance", 256, 32, 2048);

    public static final ModConfigSpec.IntValue TEXTURE_DISTANCE = BUILDER
        .comment("Block textures are drawn on LODs up to this distance (blocks, scaled by zoom); further away LODs use the texture's average colour")
        .defineInRange("textureDistance", 1024, 0, 16384);

    public static final ModConfigSpec.BooleanValue OCCLUSION_CULLING = BUILDER
        .comment("Skip LOD sections hidden behind hills and mountains (CPU horizon test)")
        .define("occlusionCulling", true);

    public static final ModConfigSpec.IntValue GPU_MEMORY_MB = BUILDER
        .comment("Size of the GPU quad buffer, in MB (8 bytes per quad). Applied when joining a world")
        .defineInRange("gpuMemoryMb", 256, 32, 2048);

    public static final ModConfigSpec.IntValue MODEL_MEMORY_MB = BUILDER
        .comment("GPU buffer for real block models (plants, fences, lanterns...) on nearby LODs, in MB. Applied when joining a world")
        .defineInRange("modelMemoryMb", 128, 16, 2048);

    public static final ModConfigSpec.IntValue SECTION_CACHE = BUILDER
        .comment("Voxel sections kept in memory, packed (a few bytes for empty ones, typically 10-40 KB for terrain)")
        .defineInRange("sectionCache", 4096, 128, 65536);

    public static final ModConfigSpec.IntValue MESH_THREADS = BUILDER
        .comment("Mesher threads, 0 = half the cores (1..4)")
        .defineInRange("meshThreads", 0, 0, 16);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private UnoConfig() {
    }

    public static int meshThreads() {
        int configured = MESH_THREADS.get();
        if (configured > 0) {
            return configured;
        }
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
