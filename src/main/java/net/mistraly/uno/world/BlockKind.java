package net.mistraly.uno.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/// How a block state looks from far away. Computed once per state.
public final class BlockKind {
    /// Not drawn, does not hide anything: air, invisible blocks, thin flowing water.
    public static final byte AIR = 0;
    /// Full cube that hides its neighbours.
    public static final byte OPAQUE = 1;
    /// Cube that only hides the same fluid.
    public static final byte FLUID = 2;
    /// Cube that hides nothing: fences, stairs, glass.
    public static final byte PARTIAL = 3;
    /// Leaves: drawn with see-through pixels up close, so they hide nothing (the trunk shows through),
    /// but count as solid when building mips.
    public static final byte LEAVES = 4;
    /// No collision but a visible model: grass, flowers, torches, crops. Drawn with their real model at level 0
    /// only; dropped from coarser levels.
    public static final byte PLANT = 5;
    /// A block with water in it that also has its own model: seagrass, kelp, waterlogged stairs or fences.
    /// Behaves like a fluid (water cube, mips to water) and additionally shows its model at level 0.
    public static final byte FLUID_MODEL = 6;

    private BlockKind() {
    }

    public static byte classify(BlockState state) {
        if (state.isAir()) {
            return AIR;
        }
        if (state.getBlock() instanceof LeavesBlock) {
            return LEAVES;
        }
        // full cubes only: blocks like the stonecutter occlude but are not full, and need their model
        if (state.isSolidRender()) {
            return OPAQUE;
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            // thin flowing water would turn into staircases of full cubes; keep sources and waterfalls only
            boolean falling = fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING);
            if (!fluid.isSource() && !falling) {
                return AIR;
            }
            boolean ownModel = !(state.getBlock() instanceof LiquidBlock) && state.getRenderShape() != RenderShape.INVISIBLE;
            return ownModel ? FLUID_MODEL : FLUID;
        }
        if (state.getBlock() instanceof SnowLayerBlock) {
            return PARTIAL;
        }
        try {
            if (!state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                return PARTIAL;
            }
            return state.getRenderShape() == RenderShape.INVISIBLE ? AIR : PLANT;
        } catch (RuntimeException e) {
            return PARTIAL;
        }
    }

    /// Drawn with its real block model (at level 0) instead of a cube.
    public static boolean modelled(byte kind) {
        return kind == PARTIAL || kind == PLANT;
    }

    /// Has a real block model to draw at level 0 (on its own or inside a water cube).
    public static boolean hasModel(byte kind) {
        return kind == PARTIAL || kind == PLANT || kind == FLUID_MODEL;
    }

    public static boolean fluid(byte kind) {
        return kind == FLUID || kind == FLUID_MODEL;
    }

    /// Solid for mip purposes: survives downsampling like a full block.
    public static boolean solid(byte kind) {
        return kind == OPAQUE || kind == LEAVES;
    }

    /// Whether a face of `self` is hidden by `neighbor`. Different fluids touching is rare enough to ignore.
    public static boolean hides(byte self, byte neighbor) {
        return neighbor == OPAQUE || (fluid(self) && fluid(neighbor));
    }
}
