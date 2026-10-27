package net.mistraly.uno.mixin.vitrail;

import net.mistraly.uno.compat.VitrailBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/// Vitrail only compiles the pack's far terrain programs ahead of time while Distant Horizons is there; with Uno
/// providing the far terrain they are wanted as well.
@Mixin(targets = "dev.vitrail.render.DistantProgram", remap = false)
public abstract class DistantProgramMixin {
    @Redirect(method = "warmAhead", at = @At(value = "INVOKE", target = "Ldev/vitrail/dh/DhLods;usable()Z"))
    private boolean uno$farTerrainWanted() {
        return dev.vitrail.dh.DhLods.usable() || VitrailBridge.answers();
    }
}
