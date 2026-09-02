package name.rhythm_axe_mod.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import name.rhythm_axe_mod.client.RhythmAxeMusic;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/**
 * 让原版 /stopsound 也能停掉 /playmusic 播放的音乐。
 *
 * 语义约定（与《工具组件.md》一致）：playmusic 的音乐归属 record 通道，
 * 因此 source 为 null（全部通道）或 RECORDS 时，若 id 为 null（该通道全部）
 * 或 id 与当前曲目一致，则一并停止我们的音乐。
 */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "stop(Lnet/minecraft/resources/Identifier;Lnet/minecraft/sounds/SoundSource;)V", at = @At("HEAD"))
	private void rhythmAxe$onStopSound(Identifier id, SoundSource source, CallbackInfo ci) {
		if ((source == null || source == SoundSource.RECORDS) && (id == null || RhythmAxeMusic.matches(id))) {
			RhythmAxeMusic.stop();
		}
	}
}
