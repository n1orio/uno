package net.mistraly.uno.mesh;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.mistraly.uno.world.IdTables;
import org.jspecify.annotations.Nullable;

/// What one face of a block looks like on a LOD: a material (index into the material table) and the biome tint.
/// Packed into the second word of a quad as material (bits 0-15) | tint RGB565 (bits 16-31).
///
/// A material is up to two sprite layers, like vanilla block models: a base and an optional overlay drawn on top
/// (the grass block side is dirt + a grey fringe tinted by the biome). Flags say which layer takes the tint.
///
/// Thread-safe; cleared on resource reload, after which every mesh is rebuilt.
public final class BlockMaterials {
    public static final int MAX_MATERIALS = 1 << 16;
    public static final int TINT_BASE = 1;
    public static final int TINT_OVERLAY = 2;
    /// Texture orientation, from the model quad's UVs: swap u/v, then flip u, flip v.
    public static final int SWAP_UV = 4;
    public static final int FLIP_U = 8;
    public static final int FLIP_V = 16;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int WHITE_565 = 0xFFFF;

    private static final Map<BlockState, Face[]> FACES = new ConcurrentHashMap<>();
    private static final Map<MaterialKey, Integer> MATERIAL_INDEX = new ConcurrentHashMap<>();
    private static final Map<TextureAtlasSprite, Integer> AVERAGES = new ConcurrentHashMap<>();
    /// Index 0 is "no material": the shader draws plain grey.
    private static final List<MaterialEntry> MATERIALS = new ArrayList<>(List.of(MaterialEntry.NONE));
    private static volatile int generation;

    private static final ThreadLocal<Long2IntMap> LOCAL = ThreadLocal.withInitial(Long2IntOpenHashMap::new);
    private static final ThreadLocal<int[]> LOCAL_GENERATION = ThreadLocal.withInitial(() -> new int[] {-1});

    /// Atlas rectangles of the layers, their average colours (ARGB; the overlay's alpha is its coverage) and flags.
    public record MaterialEntry(
        float u0, float v0, float u1, float v1,
        float overlayU0, float overlayV0, float overlayU1, float overlayV1,
        int baseAverage, int overlayAverage, int flags, boolean hasOverlay
    ) {
        static final MaterialEntry NONE = new MaterialEntry(0, 0, 0, 0, 0, 0, 0, 0, 0xFF808080, 0, 0, false);
    }

    private record MaterialKey(@Nullable TextureAtlasSprite base, @Nullable TextureAtlasSprite overlay, int flags) {
    }

    /// One face: base layer, optional overlay, and the tint source of whichever layer is tinted.
    private record Face(@Nullable TextureAtlasSprite base, @Nullable TextureAtlasSprite overlay, int flags, @Nullable BlockTintSource tint) {
        static final Face EMPTY = new Face(null, null, 0, null);
    }

    private BlockMaterials() {
    }

    public static synchronized void invalidate() {
        FACES.clear();
        MATERIAL_INDEX.clear();
        AVERAGES.clear();
        MATERIALS.subList(1, MATERIALS.size()).clear();
        generation++;
    }

    public static int generation() {
        return generation;
    }

    public static synchronized int materialCount() {
        return MATERIALS.size();
    }

    public static synchronized MaterialEntry material(int index) {
        return MATERIALS.get(index);
    }

    /// Second quad word for a face. `direction` uses {@link Direction} ordinals.
    public static int material(IdTables ids, int stateId, int biomeId, int direction) {
        int[] seen = LOCAL_GENERATION.get();
        Long2IntMap local = LOCAL.get();
        if (seen[0] != generation) {
            local.clear();
            seen[0] = generation;
        }
        long key = ((long) stateId << 11) | ((long) biomeId << 3) | direction;
        int cached = local.getOrDefault(key, -1);
        if (cached != -1) {
            return cached;
        }
        int material = compute(ids.state(stateId), ids.biome(biomeId), direction);
        local.put(key, material);
        return material;
    }

    private static int compute(BlockState state, Biome biome, int direction) {
        Face face = FACES.computeIfAbsent(state, BlockMaterials::faces)[direction];
        int material = face.base == null ? 0 : materialIndex(face);
        int tint = WHITE_565;
        if (face.tint != null) {
            try {
                int argb = face.tint.colorInWorld(state, new BiomeTintGetter(biome), BlockPos.ZERO);
                if (argb != -1) {
                    tint = to565(argb);
                }
            } catch (RuntimeException ignored) {
                // keep white
            }
        }
        return material | (tint << 16);
    }

    private static int to565(int argb) {
        return ((ARGB.red(argb) >> 3) << 11) | ((ARGB.green(argb) >> 2) << 5) | (ARGB.blue(argb) >> 3);
    }

    private static int materialIndex(Face face) {
        MaterialKey key = new MaterialKey(face.base, face.overlay, face.flags);
        Integer index = MATERIAL_INDEX.get(key);
        if (index != null) {
            return index;
        }
        TextureAtlasSprite base = face.base;
        TextureAtlasSprite overlay = face.overlay;
        MaterialEntry entry = new MaterialEntry(
            base.getU0(), base.getV0(), base.getU1(), base.getV1(),
            overlay == null ? 0 : overlay.getU0(), overlay == null ? 0 : overlay.getV0(),
            overlay == null ? 0 : overlay.getU1(), overlay == null ? 0 : overlay.getV1(),
            AVERAGES.computeIfAbsent(base, BlockMaterials::average),
            overlay == null ? 0 : AVERAGES.computeIfAbsent(overlay, BlockMaterials::average),
            face.flags,
            overlay != null
        );
        synchronized (BlockMaterials.class) {
            index = MATERIAL_INDEX.get(key);
            if (index != null) {
                return index;
            }
            if (MATERIALS.size() >= MAX_MATERIALS) {
                return 0;
            }
            index = MATERIALS.size();
            MATERIALS.add(entry);
            MATERIAL_INDEX.put(key, index);
            return index;
        }
    }

    private static Face[] faces(BlockState state) {
        Face[] result = new Face[DIRECTIONS.length];
        try {
            // fluids (also waterlogged blocks, which classify as fluid) look like the fluid: still texture + fluid tint
            if (!state.getFluidState().isEmpty() && !state.canOcclude()) {
                FluidModel fluid = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(state.getFluidState());
                Face face = new Face(fluid.stillMaterial().sprite(), null, fluid.fluidTintSource() != null ? TINT_BASE : 0, fluid.fluidTintSource());
                java.util.Arrays.fill(result, face);
                return result;
            }
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);
            TextureAtlasSprite particle = model.particleMaterial().sprite();
            for (Direction direction : DIRECTIONS) {
                List<BakedQuad> quads = quads(parts, direction);
                if (quads.isEmpty()) {
                    quads = quads(parts, null);
                }
                result[direction.ordinal()] = quads.isEmpty() ? new Face(particle, null, 0, null) : face(state, quads, direction.ordinal());
            }
        } catch (RuntimeException e) {
            java.util.Arrays.fill(result, Face.EMPTY);
        }
        return result;
    }

    /// Base = first quad, overlay = a second quad with a different sprite (grass sides, snowy grass, ...).
    private static Face face(BlockState state, List<BakedQuad> quads, int direction) {
        BakedQuad base = quads.getFirst();
        BakedQuad overlay = null;
        for (int i = 1; i < quads.size(); i++) {
            if (quads.get(i).materialInfo().sprite() != base.materialInfo().sprite()) {
                overlay = quads.get(i);
                break;
            }
        }
        int flags = 0;
        BlockTintSource tint = null;
        if (overlay != null && overlay.materialInfo().tintIndex() >= 0) {
            flags |= TINT_OVERLAY;
            tint = Minecraft.getInstance().getBlockColors().getTintSource(state, overlay.materialInfo().tintIndex());
        }
        if (base.materialInfo().tintIndex() >= 0) {
            flags |= TINT_BASE;
            if (tint == null) {
                tint = Minecraft.getInstance().getBlockColors().getTintSource(state, base.materialInfo().tintIndex());
            }
        }
        if (tint == null) {
            flags &= ~(TINT_BASE | TINT_OVERLAY);
        }
        flags |= orientation(base, direction);
        return new Face(base.materialInfo().sprite(), overlay == null ? null : overlay.materialInfo().sprite(), flags, tint);
    }

    /// Face-plane coordinates in the shader's convention: Y faces (x, z), Z faces (x, -y), X faces (z, -y).
    private static float planeA(int direction, org.joml.Vector3fc p) {
        return direction < 4 ? p.x() : p.z();
    }

    private static float planeB(int direction, org.joml.Vector3fc p) {
        return direction < 2 ? p.z() : -p.y();
    }

    /// How the quad's texture is laid onto the face relative to the shader's default (u along a, v along b):
    /// fits u and v as linear functions of the plane coordinates and rounds to one of the 8 rotations/mirrors.
    /// Covers rotated logs, mirrored bottoms and the like.
    private static int orientation(BakedQuad quad, int direction) {
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        float su = sprite.getU1() - sprite.getU0();
        float sv = sprite.getV1() - sprite.getV0();
        if (su == 0 || sv == 0) {
            return 0;
        }
        float[] a = new float[3];
        float[] b = new float[3];
        float[] u = new float[3];
        float[] v = new float[3];
        int[] corners = {0, 1, 3};
        for (int i = 0; i < 3; i++) {
            org.joml.Vector3fc p = quad.position(corners[i]);
            long uv = quad.packedUV(corners[i]);
            a[i] = planeA(direction, p);
            b[i] = planeB(direction, p);
            u[i] = (net.minecraft.client.model.geom.builders.UVPair.unpackU(uv) - sprite.getU0()) / su;
            v[i] = (net.minecraft.client.model.geom.builders.UVPair.unpackV(uv) - sprite.getV0()) / sv;
        }
        float a1 = a[1] - a[0], b1 = b[1] - b[0], a2 = a[2] - a[0], b2 = b[2] - b[0];
        float det = a1 * b2 - b1 * a2;
        if (Math.abs(det) < 1.0E-6F) {
            return 0;
        }
        float du1 = u[1] - u[0], du2 = u[2] - u[0], dv1 = v[1] - v[0], dv2 = v[2] - v[0];
        float ua = (du1 * b2 - du2 * b1) / det;
        float ub = (a1 * du2 - a2 * du1) / det;
        float va = (dv1 * b2 - dv2 * b1) / det;
        float vb = (a1 * dv2 - a2 * dv1) / det;
        boolean swap = Math.abs(ub) > Math.abs(ua);
        int flags = swap ? SWAP_UV : 0;
        if ((swap ? ub : ua) < 0) {
            flags |= FLIP_U;
        }
        if ((swap ? va : vb) < 0) {
            flags |= FLIP_V;
        }
        return flags;
    }

    /// Biome tint (ARGB, -1 = none) of a model quad's tint index, cached per thread like materials.
    public static int tintColor(IdTables ids, int stateId, int biomeId, int tintIndex) {
        int[] seen = LOCAL_GENERATION.get();
        Long2IntMap local = LOCAL.get();
        if (seen[0] != generation) {
            local.clear();
            seen[0] = generation;
        }
        long key = (1L << 62) | ((long) stateId << 20) | ((long) biomeId << 12) | (tintIndex & 0xFFF);
        if (local.containsKey(key)) {
            return local.get(key);
        }
        int color = -1;
        BlockState state = ids.state(stateId);
        try {
            BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, tintIndex);
            if (source != null) {
                color = source.colorInWorld(state, new BiomeTintGetter(ids.biome(biomeId)), BlockPos.ZERO);
            }
        } catch (RuntimeException ignored) {
            // untinted
        }
        local.put(key, color);
        return color;
    }

    private static List<BakedQuad> quads(List<BlockStateModelPart> parts, @Nullable Direction direction) {
        List<BakedQuad> result = new ArrayList<>();
        for (BlockStateModelPart part : parts) {
            result.addAll(part.getQuads(direction));
        }
        return result;
    }

    private static int average(TextureAtlasSprite sprite) {
        try {
            SpriteContents contents = sprite.contents();
            var image = contents.getOriginalImage();
            int width = Math.min(contents.width(), image.getWidth());
            int height = Math.min(contents.height(), image.getHeight());
            long r = 0, g = 0, b = 0, weight = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getPixel(x, y);
                    int alpha = ARGB.alpha(argb);
                    if (alpha < 16) {
                        continue;
                    }
                    r += (long) ARGB.red(argb) * alpha;
                    g += (long) ARGB.green(argb) * alpha;
                    b += (long) ARGB.blue(argb) * alpha;
                    weight += alpha;
                }
            }
            if (weight == 0) {
                return 0x00808080;
            }
            int coverage = (int) Math.min(255, weight / Math.max(1, (long) width * height));
            return ARGB.color(coverage, (int) (r / weight), (int) (g / weight), (int) (b / weight));
        } catch (RuntimeException e) {
            return 0xFF808080;
        }
    }

    /// Answers tint queries with a fixed biome; everything else is empty space.
    private record BiomeTintGetter(Biome biome) implements BlockAndTintGetter {
        @Override
        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver color) {
            return color.getColor(this.biome, pos.getX(), pos.getZ());
        }

        @Override
        public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
