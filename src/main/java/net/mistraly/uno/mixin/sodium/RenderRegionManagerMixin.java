package net.mistraly.uno.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.mistraly.uno.render.LodRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/// Sodium terrain: a freshly built section's mesh time decides its fade-in, and -1 means "no fade". Sections that
/// appear where a LOD was drawn get -1. The section is not reachable at the time write (it sits in a private
/// record), so it is remembered from the distance check Sodium does on it just before.
@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class RenderRegionManagerMixin {
    @Unique
    private RenderSection uno$current;

    @WrapOperation(
        method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
        at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;getCenterX()I")
    )
    private int uno$rememberSection(RenderSection section, Operation<Integer> original) {
        this.uno$current = section;
        return original.call(section);
    }

    @WrapOperation(
        method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
        at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;writeMeshTimes(III)V")
    )
    private void uno$skipFadeOverLod(UniformBufferManager uniforms, int id, int sectionIndex, int builtTime, Operation<Void> original) {
        RenderSection section = this.uno$current;
        if (section != null && LodRenderer.coversChunk(section.getChunkX(), section.getChunkZ())) {
            builtTime = -1;
        }
        original.call(uniforms, id, sectionIndex, builtTime);
    }
}
