package name.rhythm_axe_mod.client;

import name.rhythm_axe_mod.networking.MusicPayloads;
import name.rhythm_axe_mod.networking.TimelinePayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;

public class RhythmAxeModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 每游戏刻兜底：退出世界时停止音乐
		ClientTickEvents.END_CLIENT_TICK.register(client -> RhythmAxeMusic.tick());
		// 每渲染帧补充音乐缓冲：慢速播放时数据包会把 tick rate 拉到极低（如 2tps），
		// 按游戏刻补充会跟不上声卡消耗导致断流；渲染帧率不受 tick rate 影响。
		LevelRenderEvents.END_MAIN.register(context -> RhythmAxeMusic.tick());

		// 接收服务端音乐控制包（playmusic/pausemusic/resumemusic/stopmusic/preloadmusic）
		ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.PlayPayload.TYPE, (payload, context) ->
				context.client().execute(() ->
						RhythmAxeMusic.play(payload.soundId(), payload.startMs(), payload.speed(), payload.volume())));
		// 预加载：后台线程解码，不需渲染线程
		ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.PreloadPayload.TYPE, (payload, context) ->
				RhythmAxeMusic.preload(payload.soundId()));
		ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.PausePayload.TYPE, (payload, context) ->
				context.client().execute(RhythmAxeMusic::pause));
		ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.ResumePayload.TYPE, (payload, context) ->
				context.client().execute(RhythmAxeMusic::resume));
		ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.StopPayload.TYPE, (payload, context) ->
				context.client().execute(RhythmAxeMusic::stop));

		// 编辑器可视化时间轴：接收窗口数据 → 更新 HUD 覆盖层；开关关闭/退出编辑器时隐藏
		ClientPlayNetworking.registerGlobalReceiver(TimelinePayloads.ShowPayload.TYPE, (payload, context) ->
				context.client().execute(() -> TimelineGui.show(payload)));
		ClientPlayNetworking.registerGlobalReceiver(TimelinePayloads.HidePayload.TYPE, (payload, context) ->
				context.client().execute(TimelineGui::hide));
		HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR,
				Identifier.parse("rhythm_axe_mod:editor_timeline"), new TimelineGui());
	}
}
