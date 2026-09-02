package name.rhythm_axe_mod.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import name.rhythm_axe_mod.TickrateState;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void onSetLevel(ClientLevel level, CallbackInfo ci) {
        TickrateState.setClientTickRate(20.0f);
    }
}
