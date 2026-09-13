package name.rhythm_axe_mod;

import java.util.Collection;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import name.rhythm_axe_mod.music.MusicTime;
import name.rhythm_axe_mod.networking.MusicPayloads;
import name.rhythm_axe_mod.networking.TimelinePayloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RhythmAxeMod implements ModInitializer {
	public static final String MOD_ID = "rhythm_axe_mod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final SimpleCommandExceptionType ERROR_NO_TARGET =
			new SimpleCommandExceptionType(Component.literal("必须指定目标玩家"));

	/** 音乐位置诊断日志节流（每秒最多一行）+ 只在偏差超阈/刚开始播放时打印 */
	private static long lastMusicPosLogMs;
	private static boolean lastMusicPosPlaying;

	@Override
	public void onInitialize() {
		LOGGER.info("Rhythm Axe Mod 已加载！");

		// 注册自定义 gamerule（buildAndRegister 在静态初始化时自动注册）
		ModGameRules.CONFIRM_COMMAND.getClass();

		// 注册音乐网络包编解码器（服务端→客户端）
		PayloadTypeRegistry.clientboundPlay().register(MusicPayloads.PlayPayload.TYPE, MusicPayloads.PlayPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MusicPayloads.PreloadPayload.TYPE, MusicPayloads.PreloadPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MusicPayloads.PausePayload.TYPE, MusicPayloads.PausePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MusicPayloads.ResumePayload.TYPE, MusicPayloads.ResumePayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MusicPayloads.StopPayload.TYPE, MusicPayloads.StopPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TimelinePayloads.ShowPayload.TYPE, TimelinePayloads.ShowPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TimelinePayloads.HidePayload.TYPE, TimelinePayloads.HidePayload.STREAM_CODEC);
		// 客户端→服务端：上报时间轴显示区间长度（服务端按此窗口裁剪推送数据）
		PayloadTypeRegistry.serverboundPlay().register(TimelinePayloads.ClientWindowPayload.TYPE, TimelinePayloads.ClientWindowPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(TimelinePayloads.ClientWindowPayload.TYPE, (payload, context) ->
				TimelineSync.setWindowLen(payload.windowLen()));
		// 客户端→服务端：音乐实际播放位置（诊断「音乐/游戏不同步」；也为后续「音乐驱动播放头」预留）
		PayloadTypeRegistry.serverboundPlay().register(MusicPayloads.MusicPosPayload.TYPE, MusicPayloads.MusicPosPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(MusicPayloads.MusicPosPayload.TYPE, (payload, context) -> {
			MinecraftServer server = context.player().level().getServer();
			if (server == null) {
				return;
			}
			boolean justStarted = payload.playing() && !lastMusicPosPlaying;
			lastMusicPosPlaying = payload.playing();
			int playhead = MusicTime.editorPlayhead(server);
			double playheadMs = playhead == Integer.MIN_VALUE ? -1 : MusicTime.msAtTick(server, playhead);
			double deltaMs = (playheadMs < 0 || payload.audibleMs() < 0)
					? Double.NaN : payload.audibleMs() - playheadMs;
			// 只在「刚开始播放」（每次一行基线）或「偏差 > 40ms」时打印：正常局静默，不同步时留证据
			boolean offSync = Double.isNaN(deltaMs) || Math.abs(deltaMs) > 40;
			if (!justStarted && !offSync) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - lastMusicPosLogMs < 1000) {
				return; // 每秒最多一行
			}
			lastMusicPosLogMs = now;
			double mspt = server.tickRateManager().millisecondsPerTick();
			// delta<0 ⇒ 音乐落后（画面先到）；delta>0 ⇒ 音乐超前
			LOGGER.info("[MusicSync] {}tps(mspt={}) playhead={}t({}ms) 出声={}ms 已排队={}ms delta={}ms speed={}",
					String.format("%.1f", mspt <= 0 ? 0 : 1000.0 / mspt), String.format("%.2f", mspt),
					playhead, playheadMs < 0 ? "n/a" : String.format("%.0f", playheadMs),
					payload.audibleMs(), payload.queuedMs(),
					Double.isNaN(deltaMs) ? "n/a" : String.format("%.0f", deltaMs), payload.speed());
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			registerHelpCommand(dispatcher);
			registerMusicCommands(dispatcher);
		});

		// 编辑器可视化时间轴：每刻推送窗口数据（开关 = options 计分板 editor_timeline_gui）
		TimelineSync.register();
	}

	// ── 音乐播放指令（文档《工具组件.md》：/playmusic /pausemusic /resumemusic） ──

	/** 仅当命令执行者是玩家时才发成功反馈；执行者为命令方块/非玩家实体时不刷聊天栏（同 data get 行为）。 */
	private static void feedbackIfPlayer(CommandSourceStack source, Component msg) {
		if (source.getEntity() instanceof ServerPlayer) {
			source.sendSuccess(() -> msg, false);
		}
	}

	private static void registerMusicCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("playmusic")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("sound", IdentifierArgument.id())
						.suggests(SuggestionProviders.cast(SuggestionProviders.AVAILABLE_SOUNDS))
						.executes(ctx -> playMusic(ctx, 0, 1.0f, null, 1.0f))
						.then(Commands.argument("start", IntegerArgumentType.integer(0))
								.executes(ctx -> playMusic(ctx, IntegerArgumentType.getInteger(ctx, "start"), 1.0f, null, 1.0f))
								.then(Commands.argument("speed", FloatArgumentType.floatArg(0.1f, 8f))
										.executes(ctx -> playMusic(ctx, IntegerArgumentType.getInteger(ctx, "start"), FloatArgumentType.getFloat(ctx, "speed"), null, 1.0f))
										.then(Commands.argument("target", EntityArgument.players())
												.executes(ctx -> playMusic(ctx, IntegerArgumentType.getInteger(ctx, "start"), FloatArgumentType.getFloat(ctx, "speed"), EntityArgument.getPlayers(ctx, "target"), 1.0f))
												.then(Commands.argument("volume", FloatArgumentType.floatArg(0f, 1f))
														.executes(ctx -> playMusic(ctx, IntegerArgumentType.getInteger(ctx, "start"), FloatArgumentType.getFloat(ctx, "speed"), EntityArgument.getPlayers(ctx, "target"), FloatArgumentType.getFloat(ctx, "volume")))))))));

		dispatcher.register(Commands.literal("pausemusic")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("target", EntityArgument.players())
						.executes(ctx -> simpleMusic(ctx, "pause"))));

		dispatcher.register(Commands.literal("preloadmusic")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("sound", IdentifierArgument.id())
						.suggests(SuggestionProviders.cast(SuggestionProviders.AVAILABLE_SOUNDS))
						.executes(ctx -> {
							Identifier soundId = IdentifierArgument.getId(ctx, "sound");
							for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
								ServerPlayNetworking.send(player, new MusicPayloads.PreloadPayload(soundId.toString()));
							}
						feedbackIfPlayer(ctx.getSource(), Component.literal("已发送预加载：" + soundId));
							return 1;
						})));

		dispatcher.register(Commands.literal("resumemusic")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("target", EntityArgument.players())
						.executes(ctx -> simpleMusic(ctx, "resume"))));

		dispatcher.register(Commands.literal("stopmusic")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("target", EntityArgument.players())
						.executes(ctx -> simpleMusic(ctx, "stop"))));
	}

	/**
	 * 播放音乐。起始时间单位=tick，换算规则见 MusicTime：
	 * 正在编辑谱面且有时间点 → 按谱面时间点分段换算；否则 tick×当前 mspt。
	 */
	private static int playMusic(CommandContext<CommandSourceStack> ctx, int startTick, float speed, Collection<ServerPlayer> targets, float volume) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Identifier soundId = IdentifierArgument.getId(ctx, "sound");

		Collection<ServerPlayer> players = targets;
		if (players == null || players.isEmpty()) {
			if (source.getEntity() instanceof ServerPlayer player) {
				players = List.of(player);
			} else {
				throw ERROR_NO_TARGET.create();
			}
		}

		MusicTime.Result conv = MusicTime.convert(source, startTick);
		int startMs = conv.startMs();
		String convLabel = conv.fromTimingPoints() ? "谱面时间点换算" : "mspt换算";

		for (ServerPlayer player : players) {
			ServerPlayNetworking.send(player, new MusicPayloads.PlayPayload(soundId.toString(), startMs, speed, volume));
		}
		int count = players.size();
		feedbackIfPlayer(source, Component.literal("已向 " + count + " 名玩家发送播放：" + soundId
				+ "（" + startTick + "tick → " + startMs + "ms，" + convLabel + "，速度×" + speed + "，音量" + volume + "）"));
		return count;
	}

	private static int simpleMusic(CommandContext<CommandSourceStack> ctx, String action) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "target");
		for (ServerPlayer player : players) {
			switch (action) {
				case "pause" -> ServerPlayNetworking.send(player, new MusicPayloads.PausePayload());
				case "resume" -> ServerPlayNetworking.send(player, new MusicPayloads.ResumePayload());
				case "stop" -> ServerPlayNetworking.send(player, new MusicPayloads.StopPayload());
				default -> throw new IllegalArgumentException("未知操作: " + action);
			}
		}
		String label = switch (action) {
			case "pause" -> "暂停";
			case "resume" -> "继续";
			default -> "停止";
		};
		feedbackIfPlayer(ctx.getSource(), Component.literal("已向 " + players.size() + " 名玩家发送" + label + "指令"));
		return players.size();
	}

	// ── 帮助指令 ──

	private static void registerHelpCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("rhythm_axe_mod")
			.executes(ctx -> {
				CommandSourceStack source = ctx.getSource();
				source.sendSuccess(() -> Component.literal("§6=== 节奏地图 Mod 帮助 (Rhythm Axe Mod) ==="), false);
				source.sendSuccess(() -> Component.literal("§e简介：§f增强原版 /tick rate 指令 + 音乐播放"), false);
				source.sendSuccess(() -> Component.literal(""), false);
				source.sendSuccess(() -> Component.literal("§e指令格式："), false);
				source.sendSuccess(() -> Component.literal("  §a/tick rate <数字>§f  每秒刻数 (1~1000)"), false);
				source.sendSuccess(() -> Component.literal("  §a/tick rate <数字>t§f  每秒刻数，带单位"), false);
				source.sendSuccess(() -> Component.literal("  §a/tick rate <数字>ms§f 每刻毫秒数 (1~1000)"), false);
				source.sendSuccess(() -> Component.literal("  §a/tick rate <数字>bpm <tpb>§f  BPM节奏模式"), false);
				source.sendSuccess(() -> Component.literal(""), false);
				source.sendSuccess(() -> Component.literal("§e可选参数："), false);
				source.sendSuccess(() -> Component.literal("  §7末尾添加 true/false 控制客户端同步（默认true）§f"), false);
				source.sendSuccess(() -> Component.literal("  §7例: §f/tick rate 30t false §7(服务端加速，客户端重置为20tps)"), false);
				source.sendSuccess(() -> Component.literal(""), false);
				source.sendSuccess(() -> Component.literal("§e音乐播放："), false);
				source.sendSuccess(() -> Component.literal("  §a/playmusic <音效事件id> [起始tick] [速度] [目标] [音量]§f  播放"), false);
				source.sendSuccess(() -> Component.literal("  §a/pausemusic <目标>  §a/resumemusic <目标>  §a/stopmusic <目标>"), false);
				source.sendSuccess(() -> Component.literal("  §7起始tick 按当前 tick 速率自动换算毫秒（默认20tps→1tick=50ms）"), false);
				source.sendSuccess(() -> Component.literal("  §7速度=播放速率，保调不变音高（0.1~8，默认1）；音量 0~1（默认1）"), false);
				source.sendSuccess(() -> Component.literal("  §7例: §f/playmusic rhythm_axe:rhythm_axe.audio 100 1 @s 1"), false);
				source.sendSuccess(() -> Component.literal(""), false);
				source.sendSuccess(() -> Component.literal("§e编辑器可视化时间轴："), false);
				source.sendSuccess(() -> Component.literal("  §7进编辑器自动开启。顶部黑色半透明矩形，显示节奏刻度与物件（时间点/事件/音符）"), false);
				source.sendSuccess(() -> Component.literal("  §7开关 = options 计分板 §feditor_timeline_gui§7（1=显示，0=隐藏）"), false);
				source.sendSuccess(() -> Component.literal("  §7纯显示无交互；播放头固定前 1/3，内容随谱面滚动；闲置 10s 自动淡出"), false);
				source.sendSuccess(() -> Component.literal("  §7音符数/事件指令数 ≥2 时在格子右下角显示数量角标"), false);
				source.sendSuccess(() -> Component.literal(""), false);
				source.sendSuccess(() -> Component.literal("§e音乐自动对齐游戏："), false);
				source.sendSuccess(() -> Component.literal("  §7编辑器试听时，音频与播放头偏差 >40ms 且已稳住 → 自动把音频挪到播放头（只动音频）"), false);
				source.sendSuccess(() -> Component.literal("  §7开关 = options 计分板 §feditor_audio_align§7（1=开，0=关）"), false);
				source.sendSuccess(() -> Component.literal(""), false);
				source.sendSuccess(() -> Component.literal("§e权限等级：§f/tick 已降为 2 级，音乐指令为 2 级"), false);
				source.sendSuccess(() -> Component.literal("§e客户端同步：§f速率 > 20 tps 时自动加速渲染"), false);
				source.sendSuccess(() -> Component.literal("§e切换世界：§f自动重置为 20 tps"), false);
				source.sendSuccess(() -> Component.literal("§e命令确认弹窗：§f由 gamerule rhythm_axe_mod:confirm_command 控制（默认true=原版确认，false=跳过）"), false);
				source.sendSuccess(() -> Component.literal("§6========================================"), false);
				return 1;
			})
		);
	}
}
