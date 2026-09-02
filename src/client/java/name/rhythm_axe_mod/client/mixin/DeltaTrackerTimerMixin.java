package name.rhythm_axe_mod.client.mixin;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import name.rhythm_axe_mod.TickrateState;

@Mixin(DeltaTracker.Timer.class)
public abstract class DeltaTrackerTimerMixin implements DeltaTracker {
    @Shadow private float deltaTickResidual;
    @Shadow private long lastMs;

    @Inject(method = "advanceGameTime", at = @At("HEAD"), cancellable = true)
    private void onAdvanceGameTime(long now, CallbackInfoReturnable<Integer> cir) {
        float targetRate = TickrateState.getClientTickRate();
        if (targetRate > 20.0f) {
            float msPerTick = 1000.0f / targetRate;
            float deltaTicks = (now - this.lastMs) / msPerTick;
            this.lastMs = now;
            this.deltaTickResidual += deltaTicks;
            int wholeTicks = (int) this.deltaTickResidual;
            this.deltaTickResidual -= wholeTicks;
            cir.setReturnValue(wholeTicks);
        }
    }
}
