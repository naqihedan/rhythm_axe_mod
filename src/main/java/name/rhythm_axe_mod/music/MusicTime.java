package name.rhythm_axe_mod.music;

import java.util.Optional;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.CommandStorage;

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
 */
public final class MusicTime {
	private static final Identifier EDITOR_STORAGE = Identifier.parse("rhythm_axe:maps.editor");
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
