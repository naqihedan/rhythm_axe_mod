package name.rhythm_axe_mod.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import name.rhythm_axe_mod.networking.TimelinePayloads.ClientWindowPayload;
import name.rhythm_axe_mod.networking.TimelinePayloads.EventEntry;
import name.rhythm_axe_mod.networking.TimelinePayloads.NoteEntry;
import name.rhythm_axe_mod.networking.TimelinePayloads.ShowPayload;
import name.rhythm_axe_mod.networking.TimelinePayloads.TimingEntry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 编辑器可视化时间轴（mod 版）客户端渲染。
 *
 * 顶部居中的黑色矩形内，从上到下七行轨道：时间点 / 事件 / 音符盒 / 木板 / 唱片机 / 混凝土 / 染色玻璃。
 * 播放头固定在前 1/3 处，内容随谱面播放向右→左滚动（PR/剪辑软件风格）。纯显示，无交互。
 * 数据来自服务端 TimelineSync 推送的 ShowPayload（最近一次缓存）。
 */
public final class TimelineGui implements HudElement {
	private static final ShowPayload NONE = null;
	private static volatile ShowPayload data = NONE;
	private static volatile boolean visible = false;

	// 布局常量
	private static final int INFO_H = 16;   // 顶部信息行高
	private static final int ROW_H = 14;    // 每条轨道行高
	private static final int ROWS = 7;      // 轨道行数（时间点/事件/五音符）
	private static final int MARGIN = 8;    // 顶部/底部边距
	private static final int SIDE = 10;     // 左右留白百分比
	private static final int BOSS_BAR_CLEARANCE = 32; // 时间轴整体下移，避免盖住顶部 bossbar

	// 轨道行颜色（交替，区分行）
	private static final int ROW_BG = 0x10000000;
	private static final int ROW_BG_ALT = 0x08000000;

	// 音符类型固定色（0 音符盒 / 1 木板 / 2 唱片机）
	private static final int[] NOTE_TYPE_COLOR = { 0xFFE6C229, 0xFF8B5A2B, 0xFFB44FCC };

	// 节奏染色颜色（重拍优先，从粗到细）
	private static final int COLOR_BAR = 0xFFFFFFFF;  // 每小节（最粗）
	private static final int COLOR_BEAT = 0xE0FFFFFF; // 每拍
	private static final int COLOR_2 = 0xFFFF4D4D;   // 半拍 红
	private static final int COLOR_3 = 0xFFFF66CC;   // 三连 品红
	private static final int COLOR_4 = 0xFF4DA6FF;   // 四连 蓝
	private static final int COLOR_6 = 0xFFB36BFF;   // 六连 紫
	private static final int COLOR_8 = 0xFFFFD33D;   // 八连 黄
	private static final int COLOR_12 = 0xFF9E9E9E;  // 十二连 淡灰
	private static final int COLOR_DEF = 0xFF8A8A8A; // 默认刻 更亮灰

	// 时间轴尺寸缩放（1 = 默认；后续接收服务端设置）
	private static volatile float scale = 1f;
	// 闲置淡出：10 秒无变化则整体透明度减半，逐渐过渡
	private static volatile float alpha = 1f;
	private static long lastActivity;
	// 平滑运动：客户端插值播放头（float），内容随显示播放头平滑滚动，而非每 tick 跳变
	private static volatile float displayPlayhead;
	private static boolean displayInitialized;
	private static int lastSentLen;

	// 16 染料色（color 1-16；混凝土默认 9、玻璃默认 6）
	private static final int[] DYE = {
			0xFFF0F0F0, 0xFF7F7F7F, 0xFFD0D0D0, 0xFF1F1F1F, 0xFF56331C,
			0xFF993333, 0xFFD87F33, 0xFFF2C14D, 0xFF66CC33, 0xFF33CC33,
			0xFF0099CC, 0xFF7FCCD9, 0xFF3333CC, 0xFF9933CC, 0xFFCC33CC,
			0xFFED8DAC
	};
	private static final int DYE_UNSET = 0xFF808080;

	// 染料色号 1-16 对应的染色混凝土方块
	private static final Block[] CONCRETE = {
			Blocks.WHITE_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.BLACK_CONCRETE,
			Blocks.BROWN_CONCRETE, Blocks.RED_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.YELLOW_CONCRETE,
			Blocks.LIME_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.CYAN_CONCRETE, Blocks.LIGHT_BLUE_CONCRETE,
			Blocks.BLUE_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.PINK_CONCRETE
	};
	private static final Block[] GLASS = {
			Blocks.WHITE_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS, Blocks.LIGHT_GRAY_STAINED_GLASS, Blocks.BLACK_STAINED_GLASS,
			Blocks.BROWN_STAINED_GLASS, Blocks.RED_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS, Blocks.YELLOW_STAINED_GLASS,
			Blocks.LIME_STAINED_GLASS, Blocks.GREEN_STAINED_GLASS, Blocks.CYAN_STAINED_GLASS, Blocks.LIGHT_BLUE_STAINED_GLASS,
			Blocks.BLUE_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS, Blocks.PINK_STAINED_GLASS
	};

	public TimelineGui() {
	}

	public static void show(ShowPayload payload) {
		data = payload;
		visible = true;
		lastActivity = System.currentTimeMillis();
		// 首次收到 → 直接对齐；之后无论播放/暂停(快进快退/seek)都交由渲染插值平滑过渡
		if (!displayInitialized) {
			displayPlayhead = payload.playhead();
			displayInitialized = true;
		}
	}

	public static void hide() {
		visible = false;
		data = NONE;
		alpha = 1f;
		displayInitialized = false;
	}

	/** 把颜色按 f 缩放 alpha 通道（f 0~1），用于整体淡出。 */
	private static int withAlpha(int color, float f) {
		int a = (int) (((color >>> 24) & 0xFF) * f);
		if (a < 0) {
			a = 0;
		} else if (a > 255) {
			a = 255;
		}
		return (a << 24) | (color & 0xFFFFFF);
	}

	private static int dyeColor(int color) {
		if (color >= 1 && color <= 16) {
			return DYE[color - 1];
		}
		return DYE_UNSET;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!visible || data == null) {
			return;
		}
		ShowPayload d = data;
		Minecraft mc = Minecraft.getInstance();
		// 闲置淡出：60 秒无变化 → 半透明，缓慢过渡
		long now = System.currentTimeMillis();
		float target = (now - lastActivity > 60000) ? 0.5f : 1f;
		alpha += (target - alpha) * 0.04f;
		if (alpha < 0.02f) {
			alpha = 0.02f;
		} else if (alpha > 1f) {
			alpha = 1f;
		}
		Font font = mc.font;
		int w = graphics.guiWidth();

		// ── 时间轴尺寸：方块边长 = 界面尺寸 × 8（界面尺寸 2 → 16px），高 = 7 格 ──
		int guiScale = Math.max(1, mc.options.guiScale().get());
		int grid = 8;                               // 固定每格 8px（不再随界面尺寸变大），面板能放更多格、音符铺满
		int infoH = grid;                            // 信息行高 = 一格
		int rows = 7;                                // 时间点/事件/五音符
		// 横向占屏幕 70%，格子(每刻间距)固定 = 界面尺寸×8(如 16px)，窗口格数 = 宽/grid
		int panelW = (int) (w * 0.70f);
		int x1 = (w - panelW) / 2;
		int x2 = x1 + panelW;
		// 显示刻数 = 面板能容纳的格数（每刻 = grid 固定间距）；服务端推全谱面，客户端按面板裁剪显示，音符完整呈现
		int len = Math.max(8, panelW / grid);
		float pxPerTick = grid;
		int top = grid / 2 + BOSS_BAR_CLEARANCE;   // 下移避开顶部 bossbar
		int timelineTop = top + infoH;
		int y2 = timelineTop + rows * grid + 2;
		int playheadX = x1 + (len / 3) * grid;
		// 向服务端上报当前显示的区间长度，服务端按此窗口裁剪推送数据
		if (len != lastSentLen) {
			lastSentLen = len;
			try {
				ClientPlayNetworking.send(new ClientWindowPayload(len));
			} catch (Exception ignored) {
			}
		}

		// ── 背景 + 边框（四条边线精确贴合黑色区域，避免 outline 右下外扩）──
		graphics.fill(x1, top, x2, y2, withAlpha(0xC0000000, alpha));
		graphics.fill(x1, top, x2, top + 1, withAlpha(0xFFFFFFFF, alpha));   // 上
		graphics.fill(x1, y2 - 1, x2, y2, withAlpha(0xFFFFFFFF, alpha));     // 下
		graphics.fill(x1, top, x1 + 1, y2, withAlpha(0xFFFFFFFF, alpha));    // 左
		graphics.fill(x2 - 1, top, x2, y2, withAlpha(0xFFFFFFFF, alpha));    // 右

		// ── 顶部信息行 ──
		String title = (d.title() == null || d.title().isEmpty()) ? "" : d.title();
		String artist = (d.artist() == null || d.artist().isEmpty()) ? "" : d.artist();
		String left = title + (artist.isEmpty() ? "" : " - " + artist);
		String mid = String.format("%.1fbpm %d/%d", d.bpm(), d.bpb(), d.tpb());
		// 状态栏右：播放速度 + 音符流速（note_speed，如 1.00x 16）+ 播放头/结尾
		String right = String.format("%.2fx %d流速", d.playSpeed(), d.noteSpeed())
				+ "  " + d.playhead() + "/"
				+ (d.hasEndTime() ? Integer.toString(d.endTime()) : "?");
		// 状态栏左：曲名-歌手（最左）+ 未保存编辑数（标题右侧）
		String unsavedText = d.unsavedCount() + "个未保存的编辑";
		int leftW = font.width(left);
		graphics.text(font, left, x1 + 2, top + 1, withAlpha(0xFFFFFFFF, alpha));
		graphics.text(font, unsavedText, x1 + 2 + leftW + 6, top + 1, withAlpha(0xFFFFFFB0, alpha));
		int midW = font.width(mid);
		graphics.text(font, mid, x1 + (x2 - x1) / 2 - midW / 2, top + 1, withAlpha(0xFFFFFFC0, alpha));
		int rightW = font.width(right);
		graphics.text(font, right, x2 - rightW - 2, top + 1, withAlpha(0xFFFFFFFF, alpha));

		// 平滑运动：无论播放还是暂停(快进/快退/seek)，都在服务端 playhead 之间做插值；仅首次初始化直接对齐
		if (displayInitialized) {
			displayPlayhead += (d.playhead() - displayPlayhead) * 0.15f;
		} else {
			displayPlayhead = d.playhead();
			displayInitialized = true;
		}

		// ── 节奏染色刻度：按时间点分段（每段用各自 tpb/bpb 画小节线；时间点存在即新小节起点） ──
		float start = displayPlayhead - len / 3.0f;
		int end = (int) (start + len);
		List<TimingEntry> timings = d.timings();
		// 无时间点时用播放头所在段参数作全局网格（sectionStart=0）
		TimingEntry fallback = new TimingEntry(0, d.bpm(), d.tpb(), d.bpb(), true);
		int ti = 0; // 当前段索引（timings 按 time 升序）
		int tFirst = (int) Math.floor(start);
		for (int t = tFirst; t <= end; t++) {
			int x = x1 + (int) ((t - start) * pxPerTick);
			if (x <= x1 || x >= x2) {
				continue;
			}
			// 推进到 t 所在段：最后一个 time<=t 的时间点
			while (ti + 1 < timings.size() && timings.get(ti + 1).time() <= t) {
				ti++;
			}
			TimingEntry tp = timings.isEmpty() ? fallback : timings.get(ti);
			int tpb = Math.max(1, tp.tpb());
			int bpb = Math.max(1, tp.bpb());
			int beatLen = tpb * bpb;
			int rel = t - tp.time();                 // 相对段首
			int r = ((rel % tpb) + tpb) % tpb;       // 拍内偏移
			if (rel % beatLen == 0) {
				graphics.fill(x - 2, timelineTop, x + 1, y2 - 1, withAlpha(COLOR_BAR, alpha));
			} else if (r == 0) {
				graphics.fill(x, timelineTop, Math.min(x + 2, x2), y2 - 1, withAlpha(COLOR_BEAT, alpha));
			} else {
				graphics.fill(x, timelineTop, Math.min(x + 2, x2), y2 - 1, withAlpha(rhythmColor(tpb, r), alpha));
			}
		}

		// ── 行 0：时间点 → 红/绿混凝土材质 ──
		for (TimingEntry tp : d.timings()) {
			int x = xFor(start, d.playhead(), pxPerTick, x1, x2, tp.time());
			if (x < 0) {
				continue;
			}
			Block b = tp.red() ? Blocks.RED_CONCRETE : Blocks.GREEN_CONCRETE;
			drawBlock(graphics, b, x, timelineTop + 1, grid);
		}

		// ── 行 1：事件 → 淡蓝混凝土材质；多条指令的事件点右下角显示指令数量 ──
		for (EventEntry ev : d.events()) {
			int x = xFor(start, d.playhead(), pxPerTick, x1, x2, ev.time());
			if (x < 0) {
				continue;
			}
			drawBlock(graphics, Blocks.LIGHT_BLUE_CONCRETE, x, timelineTop + grid + 1, grid);
			if (ev.commandCount() >= 2) {
				drawCountTag(graphics, font, x, timelineTop + grid + 1, grid, ev.commandCount(), alpha);
			}
		}

		// ── 行 2-6：五种音符 ──
		// 先统计每类音符每刻(格子)的覆盖数，用于右下角数量角标（>=2 才显示）
		Map<Integer, Map<Integer, Integer>> perType = new HashMap<>();
		for (NoteEntry n : d.notes()) {
			int t0 = n.time();
			// 混凝土(3) 覆盖 [time, time+duration) 的每一格（持续 duration 刻），其余仅占 time 一格；
			// duration=0 也计入 time 一格，便于同一刻度重合的多个零时长混凝土显示数量角标
			int t1 = (n.type() == 3) ? Math.max(n.time(), n.time() + n.duration() - 1) : n.time();
			Map<Integer, Integer> m = perType.computeIfAbsent(n.type(), k -> new HashMap<>());
			for (int t = t0; t <= t1; t++) {
				m.merge(t, 1, Integer::sum);
			}
		}
		// ── 行分割线（每格高；先绘制，音符最后绘制保证在最上层）──
		for (int r = 0; r <= rows; r++) {
			int ly = timelineTop + r * grid;
			graphics.fill(x1 + 1, ly, x2 - 1, ly + 1, withAlpha(0xA8A8A8A8, alpha));
		}
		// ── 音符最后绘制：显示在最上层（覆盖行分割线）──
		for (NoteEntry n : d.notes()) {
			drawNote(graphics, n, start, d.playhead(), pxPerTick, x1, x2, timelineTop, grid);
		}
		// 数量角标：该行(类型)该格覆盖数 >= 2 才显示
		for (Map.Entry<Integer, Map<Integer, Integer>> e : perType.entrySet()) {
			int row = 2 + e.getKey();
			int cellY = timelineTop + row * grid + 1;
			for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) {
				int cnt = c.getValue();
				if (cnt < 2) {
					continue;
				}
				int x = xFor(start, d.playhead(), pxPerTick, x1, x2, c.getKey());
				if (x < 0) {
					continue;
				}
				drawCountTag(graphics, font, x, cellY, grid, cnt, alpha);
			}
		}

		// ── 播放头（粗黄绿，贯穿所有行，固定）──
		graphics.fill(playheadX - 1, timelineTop - 1, playheadX + 2, y2 - 1, withAlpha(0xFFCCFF33, alpha));
		graphics.fill(playheadX - 3, timelineTop - 3, playheadX + 4, timelineTop + 1, withAlpha(0xFFCCFF33, alpha));
	}

	/** 音符 time 换算到 x；在窗口外返回 -1。 */
	private static int xFor(float start, int playhead, float pxPerTick, int x1, int x2, int time) {
		int x = x1 + (int) ((time - start) * pxPerTick);
		if (x < x1 || x > x2) {
			return -1;
		}
		return x;
	}

	/** 在格子右下角绘制小数量角标（类物品栏数字；白字带阴影，随 alpha 淡出）。 */
	private static void drawCountTag(GuiGraphicsExtractor g, Font font, int cellX, int cellY,
			int cellSize, int count, float alpha) {
		String s = Integer.toString(count);
		int tx = cellX + cellSize - font.width(s) - 1;
		int ty = cellY + cellSize - font.lineHeight + 1;
		if (ty < cellY) {
			ty = cellY;
		}
		g.text(font, s, tx, ty, withAlpha(0xFFFFFFFF, alpha));
	}

	private static void drawNote(GuiGraphicsExtractor g, NoteEntry n, float start, int playhead,
			float pxPerTick, int x1, int x2, int timelineTop, int grid) {
		int row = 2 + n.type();
		int yTop = timelineTop + row * grid + 1;
		if (n.type() == 3) { // 混凝土：长条，显示 [time, time+duration) 与窗口的交集，头出窗口也画可见部分
			Block b = concreteBlock(n.color());
			float x0 = x1 + (n.time() - start) * pxPerTick;
			// 最终宽度：持续时长>0 时最后一刻只显示半格（宽 = duration - 0.5 格），让长条尾部收窄；
			// 时长=0 时显示四分之一格（此前 xEnd=x0，宽度为 0 被过滤，导致不显示）
			float durW = n.duration() > 0 ? (n.duration() - 0.5f) : 0.25f;
			float xEnd = x0 + durW * pxPerTick;
			float visL = Math.max(x0, x1);
			float visR = Math.min(xEnd, x2);
			if (visR - visL >= 1f) {
				int dw = (int) (visR - visL);
				drawBlock(g, b, (int) visL, yTop, dw, grid - 2);
				if (n.selected()) {
					outlineSelection(g, (int) visL, yTop, dw, grid - 2, alpha);
				}
			}
			return;
		}
		int x = xFor(start, playhead, pxPerTick, x1, x2, n.time());
		if (x < 0) {
			return;
		}
		Block block = (n.type() == 4) ? glassBlock(n.color()) : noteBlock(n.type());
		if (block != null) {
			int nw = (x + grid > x2) ? (x2 - x) : grid;
			drawBlock(g, block, x, yTop, nw, grid - 2);
			if (n.selected()) {
				outlineSelection(g, x, yTop, nw, grid - 2, alpha);
			}
		}
		// 引导线（引导线连接）画下边缘亮条作提示
		if (n.followingPoint() && n.type() <= 2) {
			g.fill(x, yTop + grid - 3, x + grid, yTop + grid - 1, withAlpha(0xFF33FFCC, alpha));
		}
	}

	/** 选中音符黄色描边：比音符大一圈（向外扩 1px），画 1px 边框，绘制在音符上方。 */
	private static void outlineSelection(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
		int c = withAlpha(0xFFFFFF00, alpha);
		g.fill(x - 1, y - 1, x + w + 1, y, c);         // 上边
		g.fill(x - 1, y + h, x + w + 1, y + h + 1, c); // 下边
		g.fill(x - 1, y - 1, x, y + h + 1, c);         // 左边
		g.fill(x + w, y - 1, x + w + 1, y + h + 1, c); // 右边
	}

	/** 画一个方块纹理，size×size（正方形）。失败回退灰色方块。 */
	private static void drawBlock(GuiGraphicsExtractor g, Block block, int x, int y, int size) {
		drawBlock(g, block, x, y, size, size);
	}

	/** 画一个方块纹理，w×h（可拉伸成长条）。失败回退灰色方块。 */
	private static void drawBlock(GuiGraphicsExtractor g, Block block, int x, int y, int w, int h) {
		try {
			Minecraft mc = Minecraft.getInstance();
			BlockState state = block.defaultBlockState();
			TextureAtlasSprite sprite = mc.getModelManager().getBlockStateModelSet().getParticleMaterial(state).sprite();
			// 用 color 作 tint，alpha 通道缩放纹理 → 闲置淡出时音符也随整体变半透明
			g.blitSprite(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, sprite, x, y, w, h, withAlpha(0xFFFFFFFF, alpha));
		} catch (Exception e) {
			g.fill(x, y, x + w, y + h, withAlpha(0xFF808080, alpha));
		}
	}

	/** 音符类型 0/1/2 对应方块：音符盒 / 木板 / 唱片机。 */
	private static Block noteBlock(int type) {
		switch (type) {
			case 0: return Blocks.NOTE_BLOCK;
			case 1: return Blocks.OAK_PLANKS;
			case 2: return Blocks.JUKEBOX;
			default: return null;
		}
	}

	/** 染料色号 1-16 对应染色混凝土方块。 */
	private static Block concreteBlock(int color) {
		if (color < 1 || color > 16) {
			return Blocks.STONE;
		}
		return CONCRETE[color - 1];
	}

	/** 染料色号 1-16 对应染色玻璃方块。 */
	private static Block glassBlock(int color) {
		if (color < 1 || color > 16) {
			return Blocks.GLASS;
		}
		return GLASS[color - 1];
	}

	/** 节奏染色：按拍内偏移 r 与 tpb，返回细分段颜色（重拍优先）。 */
	private static int rhythmColor(int tpb, int r) {
		if (tpb % 2 == 0 && r == tpb / 2) {
			return COLOR_2;
		}
		if (tpb % 3 == 0 && (r == tpb / 3 || r == 2 * tpb / 3)) {
			return COLOR_3;
		}
		if (tpb % 4 == 0 && (r == tpb / 4 || r == 3 * tpb / 4)) {
			return COLOR_4;
		}
		if (tpb % 6 == 0) {
			int s6 = tpb / 6;
			if (r % s6 == 0) {
				return COLOR_6;
			}
		}
		if (tpb % 8 == 0) {
			int s8 = tpb / 8;
			if (r % s8 == 0) {
				return COLOR_8;
			}
		}
		if (tpb % 12 == 0) {
			int s12 = tpb / 12;
			if (r % s12 == 0) {
				return COLOR_12;
			}
		}
		return COLOR_DEF;
	}
}
