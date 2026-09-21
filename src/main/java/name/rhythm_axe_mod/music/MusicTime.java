package name.rhythm_axe_mod.music;

import java.util.Optional;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;

/**
 * /playmusic 的 tick→毫秒 换算。
 *
 * 规则（见《工具组件.md》音频播放控件）：
 * 1. 正在编辑谱面（storage rhythm_axe:maps.editor 中 active=1 且有 mapid）且该谱面
 *    timing_points 非空 → 按时间点分段换算（BPM 可变，每刻毫秒 = 60000/(bpm×tpb)）；
 * 2. 否则回退：ms = tick × 当前服务端 mspt（未编辑时 20tps → 1tick=50ms）。
 *
 * storage 读取用 26.1 公开 API MinecraftServer.getCommandStorage()，无需 mixin。
 * 任何存储异常/数据缺失都静默回退到 mspt，保证 playmusic 永远可用。
 *
 * 另：{@link #gamePlayheadMs} 给「音乐对齐游戏」提供游戏播放头毫秒
 * （编辑器试听 = maps.editor.playhead；正式游玩 = play_state.time），见《设置.md》对齐开关。
 */
public final class MusicTime {
	private static final Identifier EDITOR_STORAGE = Identifier.parse("rhythm_axe:maps.editor");
	/** 游玩的运行存储（开局从谱面拷贝，含 timing_points）与计分板（time = 音乐时间轴刻） */
	private static final Identifier RUNTIME_STORAGE = Identifier.parse("rhythm_axe:runtime");
	private static final String PLAY_OBJECTIVE = "play_state";
	private static final double MAX_MS = 2_000_000_000.0;

	private MusicTime() {
	}

	public record Result(int startMs, boolean fromTimingPoints) {
	}

	public static Result convert(CommandSourceStack source, int startTick) {
		MinecraftServer server = source.getServer();
		try {
			CommandStorage storage = server.getCommandStorage();
			CompoundTag editor = storage.get(EDITOR_STORAGE);
			if (editor != null && editor.getBooleanOr("active", false) && editor.contains("mapid")) {
				String mapid = editor.getStringOr("mapid", "");
				if (!mapid.isEmpty()) {
					// 编辑器播放的是工作副本（history[history_cursor]，未保存的编辑内容）→ 优先用它
					// （与编辑器 tickrate_ 读同一数据源，所见即所得；正式存储 maps.<mapid> 是上次保存的旧版本）
					Optional<ListTag> tps = workingCopyTimingPoints(editor);
					if (tps.isEmpty() || tps.get().isEmpty()) {
						// 工作副本无时间点（新谱面未建/无）→ 回退正式存储
						CompoundTag map = storage.get(Identifier.parse("rhythm_axe:maps." + mapid));
						tps = map == null ? Optional.empty() : map.getList("timing_points");
					}
					if (tps.isPresent() && !tps.get().isEmpty()) {
						double ms = piecewise(tps.get(), startTick);
						if (ms >= 0) {
							return new Result((int) Math.min(ms, MAX_MS), true);
						}
					}
				}
			}
		} catch (Exception ignored) {
			// 存储数据异常 → 回退 mspt
		}
		float msPerTick = server.tickRateManager().millisecondsPerTick();
		return new Result((int) Math.min((float) startTick * msPerTick, (float) MAX_MS), false);
	}

	/**
	 * **游戏播放头毫秒**（「音乐对齐游戏」的目标位置）：
	 * - 正在编辑谱面（maps.editor.active=1）→ 编辑器播放头（playhead 刻），时间点取编辑工作副本；
	 * - 正在游玩谱面（play_state.is_running=1）→ 歌曲时间轴（play_state.time 刻），
	 *   时间点取运行存储 {@code rhythm_axe:runtime.timing_points}（开局从谱面拷贝，与 tick rate 同源）；
	 * - 都不在 / 对齐开关关闭 / 音乐尚未开始（time&lt;0）/ 读取异常 → 返回 -1（调用方跳过对齐）。
	 *
	 * 开关 = options 计分板 {@code editor_audio_align} / {@code play_audio_align}
	 * （0=关，缺失或其它值=开，见《设置.md》）。
	 */
	public static int gamePlayheadMs(MinecraftServer server) {
		try {
			boolean editing = editorActive(server);
			if (!optionEnabled(server, editing ? "editor_audio_align" : "play_audio_align")) {
				return -1;
			}
			int tick = editing ? editorPlayheadTick(server) : playTick(server);
			if (tick < 0) {
				return -1;   // 游玩：time<0 表示音乐还没开始，没有可对齐的目标
			}
			return clampMs(headMs(server, tick));
		} catch (Exception ignored) {
			return -1;
		}
	}

	/**
	 * 游戏播放头（刻）—— 编辑器播放头优先，其次游玩歌曲时间轴（{@code play_state.time}）；
	 * 都没有返回 {@link Integer#MIN_VALUE}。供诊断日志用（游玩的 time 可能为负 = 音乐尚未开始）。
	 */
	public static int gamePlayhead(MinecraftServer server) {
		if (editorActive(server)) {
			return editorPlayheadTick(server);
		}
		return playTick(server);
	}

	/**
	 * 播放头刻 → 毫秒（按当前处于编辑器还是游玩自动选时间点来源，忽略对齐开关）。
	 * 诊断日志用；对齐目标请走 {@link #gamePlayheadMs}。
	 */
	public static double headMs(MinecraftServer server, int tick) {
		return editorActive(server) ? msAtTick(server, tick) : playMsAtTick(server, tick);
	}

	/** 是否正在编辑谱面（maps.editor.active）。 */
	private static boolean editorActive(MinecraftServer server) {
		try {
			CompoundTag editor = server.getCommandStorage().get(EDITOR_STORAGE);
			return editor != null && editor.getBooleanOr("active", false);
		} catch (Exception ignored) {
			return false;
		}
	}

	/** 编辑器播放头（刻）。 */
	private static int editorPlayheadTick(MinecraftServer server) {
		try {
			CompoundTag editor = server.getCommandStorage().get(EDITOR_STORAGE);
			return editor == null ? 0 : editor.getIntOr("playhead", 0);
		} catch (Exception ignored) {
			return 0;
		}
	}

	/**
	 * 游玩歌曲时间轴的当前刻（{@code play_state.time}）。
	 * 未在游玩（is_running≠1）/ 读取异常 → {@link Integer#MIN_VALUE}。
	 * 注意刻可以是负数（time 起点 = min(0, 最早出生) - 1），音乐起点是 time==0。
	 */
	private static int playTick(MinecraftServer server) {
		try {
			Objective obj = server.getScoreboard().getObjective(PLAY_OBJECTIVE);
			if (obj == null) {
				return Integer.MIN_VALUE;
			}
			ReadOnlyScoreInfo running = server.getScoreboard()
					.getPlayerScoreInfo(ScoreHolder.forNameOnly("is_running"), obj);
			if (running == null || running.value() != 1) {
				return Integer.MIN_VALUE;
			}
			ReadOnlyScoreInfo time = server.getScoreboard()
					.getPlayerScoreInfo(ScoreHolder.forNameOnly("time"), obj);
			return time == null ? Integer.MIN_VALUE : time.value();
		} catch (Exception ignored) {
			return Integer.MIN_VALUE;
		}
	}

	/**
	 * 游玩刻→毫秒：时间点取运行存储 {@code rhythm_axe:runtime.timing_points}（与 tick rate 同源）；
	 * 无时间点 / 数据异常 → 回退 tick×当前 mspt。
	 */
	private static double playMsAtTick(MinecraftServer server, int tick) {
		try {
			CompoundTag runtime = server.getCommandStorage().get(RUNTIME_STORAGE);
			if (runtime != null) {
				Optional<ListTag> tps = runtime.getList("timing_points");
				if (tps.isPresent() && !tps.get().isEmpty()) {
					double ms = piecewise(tps.get(), tick);
					if (ms >= 0) {
						return ms;
					}
				}
			}
		} catch (Exception ignored) {
			// 回退 mspt
		}
		return tick * server.tickRateManager().millisecondsPerTick();
	}

	/** 对齐开关：options 计分板 &lt;holder&gt;（0=关；缺失或 options 未初始化=开）。 */
	private static boolean optionEnabled(MinecraftServer server, String holder) {
		try {
			Objective obj = server.getScoreboard().getObjective("options");
			if (obj == null) {
				return true;
			}
			ReadOnlyScoreInfo info = server.getScoreboard()
					.getPlayerScoreInfo(ScoreHolder.forNameOnly(holder), obj);
			return info == null || info.value() != 0;
		} catch (Exception e) {
			return true;
		}
	}

	/** 毫秒钳到 int 安全范围。 */
	private static int clampMs(double ms) {
		return (int) Math.min(ms, MAX_MS);
	}

	/**
	 * 与 {@link #convert} 同一套规则的 tick→毫秒（不依赖 CommandSourceStack，供诊断/服务端逻辑用）。
	 * 优先工作副本时间点分段换算；回退 tick×当前 mspt。
	 */
	public static double msAtTick(MinecraftServer server, int tick) {
		try {
			CommandStorage storage = server.getCommandStorage();
			CompoundTag editor = storage.get(EDITOR_STORAGE);
			if (editor != null && editor.getBooleanOr("active", false) && editor.contains("mapid")) {
				Optional<ListTag> tps = workingCopyTimingPoints(editor);
				if (tps.isEmpty() || tps.get().isEmpty()) {
					String mapid = editor.getStringOr("mapid", "");
					if (!mapid.isEmpty()) {
						CompoundTag map = storage.get(Identifier.parse("rhythm_axe:maps." + mapid));
						tps = map == null ? Optional.empty() : map.getList("timing_points");
					}
				}
				if (tps.isPresent() && !tps.get().isEmpty()) {
					double ms = piecewise(tps.get(), tick);
					if (ms >= 0) {
						return ms;
					}
				}
			}
		} catch (Exception ignored) {
			// 回退 mspt
		}
		return tick * server.tickRateManager().millisecondsPerTick();
	}

	/** 从编辑工作副本（maps.editor.history[history_cursor]）取 timing_points，没有则空。 */
	private static Optional<ListTag> workingCopyTimingPoints(CompoundTag editor) {
		Optional<ListTag> history = editor.getList("history");
		if (history.isEmpty()) {
			return Optional.empty();
		}
		int cursor = editor.getIntOr("history_cursor", 0);
		ListTag list = history.get();
		if (cursor < 0 || cursor >= list.size()) {
			return Optional.empty();
		}
		CompoundTag snapshot = list.getCompoundOrEmpty(cursor);
		return snapshot.getList("timing_points");
	}

	/**
	 * 分段换算：时间点 (time,bpm,tpb) 从 time 起生效"每刻 = 60000/(bpm×tpb) 毫秒"，
	 * 逐段累加至 tick；tick 超出最后时间点则按最后一段延续。
	 * 数据异常（bpm/tpb 非法）返回 -1，由调用方回退 mspt。
	 */
	private static double piecewise(ListTag tps, int tick) {
		if (tick <= 0) {
			return 0;
		}
		double ms = 0;
		int n = tps.size();
		for (int i = 0; i < n; i++) {
			CompoundTag tp = tps.getCompoundOrEmpty(i);
			long time = tp.getLongOr("time", 0L);
			double bpm = tp.getDoubleOr("bpm", 0.0);
			int tpb = tp.getIntOr("tpb", 0);
			if (bpm <= 0 || tpb <= 0) {
				return -1;
			}
			double msp = 60000.0 / (bpm * tpb);
			if (tick <= time) {
				break; // tick 早于首个时间点（格式保证首个 time=0）
			}
			long segEnd = (i + 1 < n) ? tps.getCompoundOrEmpty(i + 1).getLongOr("time", 0L) : Long.MAX_VALUE;
			long end = Math.min((long) tick, segEnd);
			ms += (end - time) * msp;
			if (tick <= segEnd) {
				break;
			}
		}
		return ms;
	}
}
