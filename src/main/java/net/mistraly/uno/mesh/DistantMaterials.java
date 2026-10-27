package net.mistraly.uno.mesh;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.mistraly.uno.world.BlockKind;
import net.mistraly.uno.world.IdTables;

/// The block categories shader packs read as `dhMaterialId` for far terrain (Distant Horizons' set, which
/// Vitrail and Iris hand over unchanged). Packs use them for waving leaves, water, emissive blocks and so on.
public final class DistantMaterials {
    public static final int UNKNOWN = 0;
    public static final int LEAVES = 1;
    public static final int STONE = 2;
    public static final int WOOD = 3;
    public static final int METAL = 4;
    public static final int DIRT = 5;
    public static final int LAVA = 6;
    public static final int DEEPSLATE = 7;
    public static final int SNOW = 8;
    public static final int SAND = 9;
    public static final int TERRACOTTA = 10;
    public static final int NETHER_STONE = 11;
    public static final int WATER = 12;
    public static final int GRASS = 13;
    public static final int ILLUMINATED = 15;

    private static final ThreadLocal<Int2IntOpenHashMap> CACHE = ThreadLocal.withInitial(() -> {
        Int2IntOpenHashMap map = new Int2IntOpenHashMap();
        map.defaultReturnValue(-1);
        return map;
    });
    private static final ThreadLocal<int[]> CACHE_GENERATION = ThreadLocal.withInitial(() -> new int[] {-1});

    private DistantMaterials() {
    }

    public static int of(IdTables ids, int stateId) {
        Int2IntOpenHashMap cache = CACHE.get();
        int[] generation = CACHE_GENERATION.get();
        if (generation[0] != BlockMaterials.generation()) {
            cache.clear();
            generation[0] = BlockMaterials.generation();
        }
        int cached = cache.get(stateId);
        if (cached >= 0) {
            return cached;
        }
        int material;
        try {
            material = classify(ids.state(stateId), ids.kind(stateId));
        } catch (RuntimeException e) {
            material = UNKNOWN;
        }
        cache.put(stateId, material);
        return material;
    }

    private static int classify(BlockState state, byte kind) {
        if (BlockKind.fluid(kind)) {
            return state.getFluidState().is(FluidTags.LAVA) ? LAVA : WATER;
        }
        if (kind == BlockKind.LEAVES || state.is(BlockTags.LEAVES)) {
            return LEAVES;
        }
        if (state.getLightEmission() > 0) {
            return ILLUMINATED;
        }
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM) || state.is(Blocks.PODZOL)) {
            return GRASS;
        }
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_SLABS)) {
            return WOOD;
        }
        if (state.is(BlockTags.SAND) || state.is(Blocks.SANDSTONE) || state.is(Blocks.RED_SANDSTONE)) {
            return SAND;
        }
        if (state.is(BlockTags.SNOW) || state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)) {
            return SNOW;
        }
        if (state.is(BlockTags.TERRACOTTA)) {
            return TERRACOTTA;
        }
        if (state.is(BlockTags.DIRT)) {
            return DIRT;
        }
        if (state.is(BlockTags.BASE_STONE_NETHER) || state.is(Blocks.NETHERRACK)) {
            return NETHER_STONE;
        }
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (name.contains("deepslate")) {
            return DEEPSLATE;
        }
        if (name.contains("iron_block") || name.contains("gold_block") || name.contains("copper") || name.contains("netherite_block")) {
            return METAL;
        }
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || name.contains("stone") || name.contains("cobble")) {
            return STONE;
        }
        return UNKNOWN;
    }
}
