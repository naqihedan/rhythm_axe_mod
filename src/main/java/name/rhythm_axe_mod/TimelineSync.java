package name.rhythm_axe_mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import name.rhythm_axe_mod.networking.TimelinePayloads;
import name.rhythm_axe_mod.networking.TimelinePayloads.EventEntry;
import name.rhythm_axe_mod.networking.TimelinePayloads.NoteEntry;
import name.rhythm_axe_mod.networking.TimelinePayloads.ShowPayload;
import name.rhythm_axe_mod.networking.TimelinePayloads.TimingEntry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;

/**
 * 编辑器可视化时间轴（mod 版）服务端数据推送。
 *
 * 由数据包侧 options 计分板 {@code editor_timeline_gui} 控制开关（编辑器进入置 1、退出置 0）。
 * 每游戏刻：
 *  - gui>0：读取谱面工作副本 maps.editor.history[history_cursor]，裁剪出播放头附近窗口，
 *    若数据有变化（播放中每刻、暂停时快进/快退/编辑都会变）则打包 ShowPayload 推给编辑玩家；
 *  - gui 从开转关：给所有在线玩家发 HidePayload 清理客户端残留。
 *
 * 变化检测键 = (playing, playhead, history_cursor)：播放中 playhead 每刻变→每刻推；
 * 暂停时快进/快退 playhead 变、编辑/撤销重做 history_cursor 变→再推；暂停静止→不推（省资源）。
 */
public final class TimelineSync {
	private static final Identifier EDITOR_STORAGE = Identifier.parse("rhythm_axe:maps.editor");

	// 上次推送状态（变化检测）
	private static boolean initialized;
	private static int lastPlayhead;
	private static int lastCursor;
	private static float lastPlaySpeed;
	private static int lastNoteSpeed;
	private static int lastUnsaved;
	private static int lastContentVer;
	private static String lastSelectionFp = "";
	private static boolean wasOn;
	private static int windowLen = 64;
	private static int lastLoggedGui = -1;

	private TimelineSync() {
	}

	/** 客户端上报的显示区间长度。 */
	public static void setWindowLen(int len) {
		windowLen = Math.max(8, len);
	}

	/** 服务端（重）启动时重置变化检测与推送状态：
	 *  重进存档后 maps.editor 持久化仍在，但客户端 HUD 状态可能已被清（或上次 wasOn 残留），
	 *  若不重置 initialized，首刻 changed=false 会不推送 → 时间轴不出现。重置后首刻必推一次。 */
	private static void resetForServerStart() {
		initialized = false;
		wasOn = false;
		lastPlayhead = 0;
		lastCursor = 0;
		lastPlaySpeed = 1.0f;
		lastNoteSpeed = 0;
		lastUnsaved = 0;
		lastContentVer = 0;
		lastSelectionFp = "";
		lastLoggedGui = -1;
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> resetForServerStart());
		ServerTickEvents.END_SERVER_TICK.register(server -> tick(server));
	}

	private static void tick(MinecraftServer server) {
		int gui = readGuiToggle(server);
		boolean on = gui > 0;
		if (gui != lastLoggedGui) {
			lastLoggedGui = gui;
		}
		if (on) {
			wasOn = true;
			CommandStorage storage = server.getCommandStorage();
			CompoundTag editor = storage.get(EDITOR_STORAGE);
			if (editor != null && editor.getBooleanOr("active", false)) {
				// 变化检测：播放中每刻、暂停时快进/快退/编辑/调速才推；暂停静止不推（省性能）
				boolean playing = editor.getBooleanOr("playing", false);
				int playhead = editor.getIntOr("playhead", 0);
				int cursor = editor.getIntOr("history_cursor", 0);
				float playSpeed = f(editor, "play_speed", 1.0f);
				int noteSpeed = readNoteSpeed(server);
				int unsaved = Math.abs(cursor - editor.getIntOr("saved_cursor", 0));
				int contentVer = editor.getIntOr("content_ver", 0);
				// content_ver：编辑器数据保存/修改时自增（数据包在确认保存处 bump），
				// 用于在 cursor/playhead/playing/speed 都不变时也强制刷新（如音符属性面板保存）。
				// selection_fp：选中音符集合变化（选中/取消/清空）→ 刷新让客户端画黄色选中描边。
				String selFp = selectionFingerprint(collectSelectedIds(editor));
				boolean changed = !initialized || playing || playhead != lastPlayhead
						|| cursor != lastCursor || playSpeed != lastPlaySpeed
						|| noteSpeed != lastNoteSpeed || unsaved != lastUnsaved
						|| contentVer != lastContentVer || !selFp.equals(lastSelectionFp);
				if (changed) {
					ShowPayload payload = buildPayload(editor, noteSpeed, unsaved);
					sendShow(server, payload);
				}
				initialized = true;
				lastPlayhead = playhead;
				lastCursor = cursor;
				lastPlaySpeed = playSpeed;
				lastNoteSpeed = noteSpeed;
				lastUnsaved = unsaved;
				lastContentVer = contentVer;
				lastSelectionFp = selFp;
			}
		} else if (wasOn) {
			// 开关关闭/编辑器退出 → 清理所有客户端
			wasOn = false;
			initialized = false;
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				ServerPlayNetworking.send(player, new TimelinePayloads.HidePayload());
			}
		}
	}

	/** 读取 options 计分板 editor_timeline_gui 值（无分返回 0）。 */
	private static int readGuiToggle(MinecraftServer server) {
		try {
			Objective obj = server.getScoreboard().getObjective("options");
			if (obj == null) {
				return 0;
			}
			ReadOnlyScoreInfo info = server.getScoreboard()
					.getPlayerScoreInfo(ScoreHolder.forNameOnly("editor_timeline_gui"), obj);
			return info == null ? 0 : info.value();
		} catch (Exception e) {
			return 0;
		}
	}

	/** 读取 options 计分板 note_speed（音符流速，状态栏显示；无分返回 16 默认）。 */
	private static int readNoteSpeed(MinecraftServer server) {
		try {
			Objective obj = server.getScoreboard().getObjective("options");
			if (obj == null) {
				return 16;
			}
			ReadOnlyScoreInfo info = server.getScoreboard()
					.getPlayerScoreInfo(ScoreHolder.forNameOnly("note_speed"), obj);
			if (info == null || info.value() <= 0) {
				return 16;
			}
			return info.value();
		} catch (Exception e) {
			return 16;
		}
	}

	/** NBT float 安全读取（26.1 getFloat 返回 Optional<Float>）。 */
	private static float f(CompoundTag tag, String key, float def) {
		return tag.getFloat(key).orElse(def);
	}

	/** 把谱面 title 解析成纯文本，支持 JSON 文本组件（对象/列表/带引号字符串）与复合/列表 NBT。 */
	private static String resolveTitle(CompoundTag snapshot) {
		if (!snapshot.contains("title")) {
			return "";
		}
		net.minecraft.nbt.Tag tag = snapshot.get("title");
		// 字符串：JSON 文本组件字符串，如 {"text":"A"} / [{"text":"A"},{"text":"B"}] / "新手教程"
		String raw = snapshot.getStringOr("title", "");
		if (!raw.isEmpty()) {
			String t = raw.trim();
			if (t.startsWith("[")) {
				return extractComponentList(t);
			}
			if (t.startsWith("{")) {
				return extractComponentText(t);
			}
			if (t.startsWith("\"")) {
				return unquote(t);
			}
			return t;
		}
		// 复合 {text:...}：取 text 子串（同数据包显示清洗）
		if (tag instanceof CompoundTag ct && ct.contains("text")) {
			return ct.getStringOr("text", "");
		}
		// 列表 [{...},{...}]：直接作为 NBT 列表存，拼接各元素 text
		if (tag instanceof ListTag lt) {
			StringBuilder sb = new StringBuilder();
			for (net.minecraft.nbt.Tag el : lt) {
				if (el instanceof CompoundTag et && et.contains("text")) {
					sb.append(et.getStringOr("text", ""));
				}
			}
			return sb.toString();
		}
		return "";
	}

	/** 从单个 JSON 组件对象 {...,"text":"...",...} 提取 text 文本（简单解析，含转义）。 */
	private static String extractComponentText(String obj) {
		int i = obj.indexOf("\"text\"");
		if (i < 0) {
			return "";
		}
		int colon = obj.indexOf(':', i);
		if (colon < 0) {
			return "";
		}
		int p = colon + 1;
		while (p < obj.length() && Character.isWhitespace(obj.charAt(p))) {
			p++;
		}
		if (p < obj.length() && obj.charAt(p) == '"') {
			StringBuilder sb = new StringBuilder();
			int j = p + 1;
			while (j < obj.length() && obj.charAt(j) != '"') {
				if (obj.charAt(j) == '\\' && j + 1 < obj.length()) {
					j++;
				}
				sb.append(obj.charAt(j));
				j++;
			}
			return sb.toString();
		}
		return "";
	}

	/** 从列表组件 [{...},{...}] 拼接各元素 text。 */
	private static String extractComponentList(String list) {
		StringBuilder sb = new StringBuilder();
		int from = 0;
		while (true) {
			int i = list.indexOf("\"text\"", from);
			if (i < 0) {
				break;
			}
			int colon = list.indexOf(':', i);
			if (colon < 0) {
				break;
			}
			int p = colon + 1;
			while (p < list.length() && Character.isWhitespace(list.charAt(p))) {
				p++;
			}
			if (p < list.length() && list.charAt(p) == '"') {
				int j = p + 1;
				StringBuilder seg = new StringBuilder();
				while (j < list.length() && list.charAt(j) != '"') {
					if (list.charAt(j) == '\\' && j + 1 < list.length()) {
						j++;
					}
					seg.append(list.charAt(j));
					j++;
				}
				sb.append(seg);
				from = j + 1;
			} else {
				from = colon + 1;
			}
		}
		return sb.toString();
	}

	/** 去除 JSON 字符串两端引号并反转义（\n \t \" \\ 等）。 */
	private static String unquote(String s) {
		StringBuilder sb = new StringBuilder();
		int i = 1;
		int end = s.length() - 1;
		while (i < end) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < end) {
				char n = s.charAt(i + 1);
				switch (n) {
					case 'n' -> sb.append('\n');
					case 't' -> sb.append('\t');
					case 'r' -> sb.append('\r');
					case '\\' -> sb.append('\\');
					default -> sb.append(n);
				}
				i += 2;
			} else {
				sb.append(c);
				i++;
			}
		}
		return sb.toString();
	}

	/** 从 maps.editor 构造窗口 ShowPayload。异常一律返回最小空数据，保证不崩。 */
	private static ShowPayload buildPayload(CompoundTag editor, int noteSpeed, int unsaved) {
		try {
			CompoundTag snapshot = currentSnapshot(editor);
			int playhead = editor.getIntOr("playhead", 0);
			boolean playing = editor.getBooleanOr("playing", false);
			float playSpeed = f(editor, "play_speed", 1.0f);

			// 窗口长度 = 客户端上报的显示区间；播放头固定前 1/3，内容向右滚动 → [playhead - len/3, playhead + 2len/3]
			int windowStart = playhead - windowLen / 3;
			int windowEnd = windowStart + windowLen;

			List<NoteEntry> notes = collectNotes(snapshot, windowStart, windowEnd, collectSelectedIds(editor));
			TimingBundle timing = collectTimings(snapshot, windowStart, windowEnd, playhead);
			List<EventEntry> events = collectEvents(snapshot, windowStart, windowEnd);

			return new ShowPayload(
					playhead, playing, windowLen,
					timing.bpm, timing.bpb, timing.tpb,
					playSpeed, noteSpeed, unsaved,
					snapshot.contains("end_time"), snapshot.getIntOr("end_time", 0),
					resolveTitle(snapshot), snapshot.getStringOr("artist", ""),
					notes, timing.list, events);
		} catch (Exception e) {
			// 任何存储异常 → 返回一个最小可渲染包（仅显示信息，无物件），不输出日志噪音
			return new ShowPayload(
					editor.getIntOr("playhead", 0), editor.getBooleanOr("playing", false),
					windowLen,
					150.0f, 4, 8, 1.0f, noteSpeed, unsaved,
					false, 0, "", "",
					List.of(), List.of(), List.of());
		}
	}

	/** 工作副本：history[history_cursor]；取不到则回退编辑器根（新谱面未建正式存储等）。 */
	private static CompoundTag currentSnapshot(CompoundTag editor) {
		Optional<ListTag> history = editor.getList("history");
		if (history.isPresent()) {
			ListTag list = history.get();
			int cursor = editor.getIntOr("history_cursor", 0);
			if (cursor >= 0 && cursor < list.size()) {
				return list.getCompoundOrEmpty(cursor);
			}
		}
		return editor;
	}

	private static List<NoteEntry> collectNotes(CompoundTag snap, int start, int end, Set<Integer> selected) {
		List<NoteEntry> out = new ArrayList<>();
		Optional<ListTag> notes = snap.getList("notes");
		if (notes.isEmpty()) {
			return out;
		}
		for (net.minecraft.nbt.Tag t : notes.get()) {
			CompoundTag n = (CompoundTag) t;
			int time = n.getIntOr("time", 0);
			int type = n.getIntOr("type", 0);
			int dur = n.getIntOr("duration", 8);
			// 混凝土(type 3)是长条：头已出窗但尾部仍在窗口内时也保留，让客户端绘制可见部分；
			// 其余音符仅当头落在窗口内才显示。
			boolean inWindow = (type == 3)
					? (time <= end && time + dur >= start)
					: (time >= start && time <= end);
			if (!inWindow) {
				continue;
			}
			// 颜色缺省：混凝土默认 9、染色玻璃默认 6（与其他逻辑一致）
			int color = n.getIntOr("color", 0);
			if (color <= 0) {
				color = (type == 3) ? 9 : (type == 4) ? 6 : 0;
			}
			int id = n.getIntOr("id", -1);
			out.add(new NoteEntry(
					id,
					time,
					type,
					n.getIntOr("duration", 8),
					color,
					(float) n.getDoubleOr("size", 1.0),
					// 数据包编辑器侧：following_point 缺省 0（关闭），关闭时字段被移除（缺席=禁用）
					n.getBooleanOr("following_point", false),
					selected.contains(id)));
		}
		return out;
	}

	/** 读取编辑器选中音符 id 集合（maps.editor.selection，列表存音符 id 整数）。 */
	private static Set<Integer> collectSelectedIds(CompoundTag editor) {
		Set<Integer> ids = new HashSet<>();
		Optional<ListTag> sel = editor.getList("selection");
		if (sel.isPresent()) {
			for (net.minecraft.nbt.Tag t : sel.get()) {
				// selection 列表存的是音符 id（整数），由数据包 `store result ... int 1` 写入 → IntTag。
				// 本版本 NBT 用 asInt()（返回 Optional<Integer>）读取数值，而非 getAsInt()/getAsString()。
				t.asInt().ifPresent(ids::add);
			}
		}
		return ids;
	}

	/** 选中 id 集合的稳定指纹（排序后 join），用于检测选中变化。 */
	private static String selectionFingerprint(Set<Integer> ids) {
		List<Integer> sorted = new ArrayList<>(ids);
		Collections.sort(sorted);
		return sorted.toString();
	}

	/** 收集时间点（含 red 判定），并算出播放头所在时间点参数。
	 *  发送列表从「窗口起点所在段」开始（含该段，供客户端画小节线），到窗口末尾的时间点为止；
	 *  这样客户端能按每个时间点分段绘制，而不是全窗口用同一段样式。 */
	private static TimingBundle collectTimings(CompoundTag snap, int start, int end, int playhead) {
		List<TimingEntry> list = new ArrayList<>();
		Optional<ListTag> tps = snap.getList("timing_points");
		if (tps.isEmpty()) {
			return new TimingBundle(150.0f, 4, 8, list);
		}
		ListTag arr = tps.get();
		CompoundTag cur = arr.getCompoundOrEmpty(0);
		int prevTpsKey = Integer.MIN_VALUE;
		int beginIdx = 0; // 最后一个 time <= start 的时间点（窗口起点所在段），没有则为 0
		for (int i = 0; i < arr.size(); i++) {
			CompoundTag tp = arr.getCompoundOrEmpty(i);
			int time = tp.getIntOr("time", 0);
			float bpm = f(tp, "bpm", 150.0f);
			int tpb = tp.getIntOr("tpb", 8);
			int bpb = tp.getIntOr("bpb", 4);
			int key = Math.round(bpm * tpb * 1000.0f);
			// 谱面第一个时间点恒为红色（起点），后续按 BPM 是否变化判定红/绿
			boolean red = (i == 0) || (prevTpsKey != Integer.MIN_VALUE && key != prevTpsKey);
			prevTpsKey = key;
			if (time <= playhead) {
				cur = tp; // 播放头所在时间点
			}
			if (time <= start) {
				beginIdx = i; // 记录窗口起点所在段
			}
			if (time > end) {
				break; // 已排序：超出窗口即止
			}
			if (i >= beginIdx) {
				list.add(new TimingEntry(time, bpm, tpb, bpb, red));
			}
		}
		return new TimingBundle(
				f(cur, "bpm", 150.0f),
				cur.getIntOr("bpb", 4),
				cur.getIntOr("tpb", 8),
				list);
	}

	private static List<EventEntry> collectEvents(CompoundTag snap, int start, int end) {
		List<EventEntry> out = new ArrayList<>();
		Optional<ListTag> events = snap.getList("events");
		if (events.isEmpty()) {
			return out;
		}
		for (net.minecraft.nbt.Tag t : events.get()) {
			CompoundTag e = (CompoundTag) t;
			int time = e.getIntOr("time", 0);
			if (time < start || time > end) {
				continue;
			}
			Optional<ListTag> cmds = e.getList("commands");
			int count = cmds.isPresent() ? cmds.get().size() : 0;
			out.add(new EventEntry(time, count));
		}
		return out;
	}

	private static void sendShow(MinecraftServer server, ShowPayload payload) {
		ServerPlayer target = editorPlayer(server);
		if (target != null) {
			ServerPlayNetworking.send(target, payload);
		}
	}

	/** 编辑玩家 = maps.editor.player 的 UUID；解析失败返回 null（不推）。 */
	private static ServerPlayer editorPlayer(MinecraftServer server) {
		try {
			CommandStorage storage = server.getCommandStorage();
			CompoundTag editor = storage.get(EDITOR_STORAGE);
			if (editor == null || !editor.contains("player")) {
				return null;
			}
			// 数据包 `data modify ... player set from entity @s UUID` 存的是 UUID 的 int-array [I;...]，
			// 不是字符串！必须用 getIntArray 读四个 int，再拼成 UUID（26.1 返回 Optional<int[]>）。
			int[] id = editor.getIntArray("player").orElse(new int[0]);
			if (id.length != 4) {
				return null;
			}
			UUID uuid = new UUID(
					((long) id[0] << 32) | (id[1] & 0xFFFFFFFFL),
					((long) id[2] << 32) | (id[3] & 0xFFFFFFFFL));
			return server.getPlayerList().getPlayer(uuid);
		} catch (Exception e) {
			return null;
		}
	}

	private record TimingBundle(float bpm, int bpb, int tpb, List<TimingEntry> list) {
	}
}
