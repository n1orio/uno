package net.mistraly.uno.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.mistraly.uno.Uno;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// Every block change the client sees (placed, broken, server updates) schedules its chunk for a new LOD snapshot.
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void uno$blockChanged(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
        if (old != current) {
            Uno.onBlockChanged(pos);
        }
    }
}
