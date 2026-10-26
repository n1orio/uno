package net.mistraly.uno.mixin;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.mistraly.uno.render.LodRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/// Vanilla terrain (without Sodium): chunks that appear where a LOD was drawn are shown at once instead of
/// fading in from the fog colour.
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class SectionVisibilityMixin {
    @Inject(method = "getVisibility", at = @At("HEAD"), cancellable = true)
    private void uno$skipFadeOverLod(long now, long fadeDuration, CallbackInfoReturnable<Float> cir) {
        long node = ((SectionRenderDispatcher.RenderSection) (Object) this).getSectionNode();
        if (LodRenderer.coversChunk(SectionPos.x(node), SectionPos.z(node))) {
            cir.setReturnValue(1.0F);
        }
    }
}
