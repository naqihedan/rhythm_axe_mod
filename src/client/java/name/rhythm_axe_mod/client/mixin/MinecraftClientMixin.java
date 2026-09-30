package name.rhythm_axe_mod.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import name.rhythm_axe_mod.TickrateState;
import name.rhythm_axe_mod.client.MusicResume;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void onSetLevel(ClientLevel level, CallbackInfo ci) {
        if (level == null) {
            // 退出存档：先把「正在播的音乐进度」落盘（此刻音频还在播、世界标识也还在）。
            // 注意这一刻不能当成「用户停播」—— 真正停音乐的是之后 tick() 的兜底（见 MusicResume.tick）。
            MusicResume.save();
        }
        TickrateState.setClientTickRate(20.0f);
        if (level != null) {
            // 重进存档：上次是从这个存档退出的就从当时的进度接着播
            //（tick rate 由服务端侧 TickratePersistence 在 SERVER_STARTED 恢复）
            MusicResume.onWorldJoin(Minecraft.getInstance());
        }
    }
}
