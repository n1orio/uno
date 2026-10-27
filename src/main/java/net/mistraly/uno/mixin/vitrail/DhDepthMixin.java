package net.mistraly.uno.mixin.vitrail;

import net.mistraly.uno.compat.VitrailBridge;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/// Where Vitrail asks Distant Horizons about its far terrain (is it there, how far, which planes), Uno answers for
/// its LODs when DH itself is not installed.
@Mixin(targets = "dev.vitrail.dh.DhDepth", remap = false)
public abstract class DhDepthMixin {
    @Inject(method = "zRow", at = @At("HEAD"), cancellable = true)
    private static void uno$zRow(Vector2f dest, CallbackInfoReturnable<Boolean> cir) {
        if (VitrailBridge.answers()) {
            cir.setReturnValue(VitrailBridge.zRow(dest));
        }
    }

    @Inject(method = "renderDistanceBlocks", at = @At("HEAD"), cancellable = true)
    private static void uno$renderDistance(CallbackInfoReturnable<Integer> cir) {
        if (VitrailBridge.answers()) {
            cir.setReturnValue(VitrailBridge.renderDistanceBlocks());
        }
    }

    @Inject(method = "usable", at = @At("HEAD"), cancellable = true)
    private static void uno$usable(CallbackInfoReturnable<Boolean> cir) {
        if (VitrailBridge.answers()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "present", at = @At("HEAD"), cancellable = true)
    private static void uno$present(CallbackInfoReturnable<Boolean> cir) {
        if (VitrailBridge.answers()) {
            cir.setReturnValue(VitrailBridge.present());
        }
    }
}
