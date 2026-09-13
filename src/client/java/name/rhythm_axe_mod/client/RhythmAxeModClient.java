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
	private static int clientTickCounter;   // 位置上报节流（每 5 刻一次 ≈ 4 次/秒）

	@Override
	public void onInitializeClient() {
		// 每游戏刻兜底：退出世界时停止音乐；顺便节流上报音乐位置（诊断 + 后续「音乐驱动播放头」用）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			RhythmAxeMusic.tick();
			// 每刻一次：资源包重载处理（含后台重新预热曲目）+ 挂起起播 + 「音乐对齐游戏」
			RhythmAxeMusic.onClientTick();
			if (client.player != null && client.level != null && RhythmAxeMusic.isPlaying()
					&& (clientTickCounter++ % 5 == 0)) {
				ClientPlayNetworking.send(new MusicPayloads.MusicPosPayload(
						RhythmAxeMusic.audibleMs(), RhythmAxeMusic.queuedMs(),
						RhythmAxeMusic.currentSpeed(), true));
			}
		});
		// 每渲染帧补充音乐缓冲：慢速播放时数据包会把 tick rate 拉到极低（如 2tps），
		// 按游戏刻补充会跟不上声卡消耗导致断流；渲染帧率不受 tick rate 影响。
		LevelRenderEvents.END_MAIN.register(context -> RhythmAxeMusic.tick());

		// 接收服务端音乐控制包（playmusic/pausemusic/resumemusic/stopmusic/preloadmusic）
		ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.PlayPayload.TYPE, (payload, context) ->
				context.client().execute(() ->
						RhythmAxeMusic.play(payload.soundId(), payload.startMs(), payload.speed(), payload.volume())));
		// 预加载：解析路径+读字节在渲染线程（毫秒级），解码在后台线程（preload 内部去重）
		ClientPlayNetworking.registerGlobalReceiver(MusicPayloads.PreloadPayload.TYPE, (payload, context) ->
				context.client().execute(() -> RhythmAxeMusic.preload(payload.soundId())));
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
