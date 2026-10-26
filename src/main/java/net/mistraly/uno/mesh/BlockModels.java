package net.mistraly.uno.mesh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/// The real model quads of blocks drawn as models on LODs (fences, lanterns, stonecutters, grass, flowers).
/// Thread-safe; cleared on resource reload.
public final class BlockModels {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Map<BlockState, ModelQuad[]> CACHE = new ConcurrentHashMap<>();

    /// Block-local corner positions (0..1) and atlas UVs, 4 corners each.
    public record ModelQuad(float[] positions, float[] uvs, int tintIndex, int cullFace, int shadeFace, int emission) {
    }

    private BlockModels() {
    }

    public static void invalidate() {
        CACHE.clear();
    }

    public static ModelQuad[] quads(BlockState state) {
        return CACHE.computeIfAbsent(state, BlockModels::build);
    }

    private static ModelQuad[] build(BlockState state) {
        try {
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);
            List<ModelQuad> result = new ArrayList<>();
            for (BlockStateModelPart part : parts) {
                for (Direction direction : DIRECTIONS) {
                    for (BakedQuad quad : part.getQuads(direction)) {
                        result.add(convert(quad, direction.ordinal()));
                    }
                }
                for (BakedQuad quad : part.getQuads(null)) {
                    result.add(convert(quad, -1));
                }
            }
            return result.toArray(ModelQuad[]::new);
        } catch (RuntimeException e) {
            return new ModelQuad[0];
        }
    }

    private static ModelQuad convert(BakedQuad quad, int cullFace) {
        float[] positions = new float[12];
        float[] uvs = new float[8];
        for (int i = 0; i < 4; i++) {
            positions[i * 3] = quad.position(i).x();
            positions[i * 3 + 1] = quad.position(i).y();
            positions[i * 3 + 2] = quad.position(i).z();
            uvs[i * 2] = UVPair.unpackU(quad.packedUV(i));
            uvs[i * 2 + 1] = UVPair.unpackV(quad.packedUV(i));
        }
        BakedQuad.MaterialInfo info = quad.materialInfo();
        Direction shade = info.shadeDirectionOverride() != null ? info.shadeDirectionOverride() : quad.direction();
        return new ModelQuad(positions, uvs, info.tintIndex(), cullFace, shade.ordinal(), info.lightEmission());
    }
}
