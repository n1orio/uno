package net.mistraly.uno.mixin;

import net.minecraft.client.Camera;
import net.mistraly.uno.render.LodRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// Vanilla sets the far plane to 4x the render distance; LODs need it pushed out to the LOD distance.
/// The depth buffer is reversed-Z D32_FLOAT, so a far plane of tens of thousands of blocks costs no precision.
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private float depthFar;

    @Inject(
        method = "update",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER)
    )
    private void uno$extendFarPlane(CallbackInfo ci) {
        float lodFar = LodRenderer.farPlane();
        if (lodFar > this.depthFar) {
            this.depthFar = lodFar;
        }
    }
}
