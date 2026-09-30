package name.rhythm_axe_mod.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 音乐续播状态（**纯客户端**）：退出存档时把「正在播的曲目 + 出声位置 + 速率 + 音量」落盘，
 * 重进**同一个存档 / 服务器**时读回并接着播。
 *
 * <p>与 {@link name.rhythm_axe_mod.TickratePersistence}（tick rate 落盘，服务端侧、按存档存）
 * 配对，合起来实现「退出存档前什么样，回来还是什么样」：
 * <ul>
 *   <li>tick rate —— 服务端记在存档里（&lt;存档&gt;/rhythm_axe_mod/tickrate.txt）；</li>
 *   <li>音乐进度 —— 客户端记在 config 里（<b>只有音频位置是客户端才知道的</b>）。</li>
 * </ul>
 *
 * <p>只记「正在播放」这一种状态：
 * <ul>
 *   <li>数据包 {@code pausemusic} / {@code stopmusic}（编辑器暂停、打完一局）→ 立刻作废，重进不会莫名出声；</li>
 *   <li>曲子自然播完 → 也作废；</li>
 *   <li>玩家按 ESC 暂停游戏**不算**暂停（mod 只是把 AL 源 pause，用户意图仍是"在播"，
 *       出声位置也冻在原地）→ 保留 —— 这正是「暂停游戏退出了，回来接着听」要续的场景。</li>
 * </ul>
 *
 * <p>位置每客户端刻刷新（{@link RhythmAxeMusic#audibleMs()}），退出时再取一次精确值，
 * 所以落盘的就是「退出那一刻真正听到的位置」。
 */
public final class MusicResume {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** 状态文件（客户端 config 目录；跨存档共用一份，靠 {@code scope} 区分是哪个存档）。 */
	private static final Path FILE = FabricLoader.getInstance().getConfigDir()
			.resolve("rhythm_axe_mod").resolve("music_resume.txt");

	/** 播放中每 N 客户端刻落一次盘：即使客户端崩了最多也只丢这么久（正常退出会再写一次精确值）。 */
	private static final int FLUSH_TICKS = 40;

	private static String scope = "";    // 当前世界标识（单人 = 存档目录名；多人 = 服务器地址）
	private static String soundId;       // 当前在播曲目（null = 没有可续播的进度）
	private static int ms = -1;          // 出声位置（音乐源毫秒）
	private static float speed = 1f;     // 播放速率（保调变速倍率）
	private static float volume = 1f;    // 命令音量（0~1）
	private static boolean wasPlaying;   // 上一刻是否在播（用来识别「播完 / 被停」）
	private static int flushTimer;

	private MusicResume() {
	}

	// ==================== 进入 / 离开世界 ====================

	/**
	 * 进入世界（{@code Minecraft.setLevel(非 null)} 的 HEAD 调用）：
	 * 记下世界标识；若上次是从**这个**存档退出的，就接着播。
	 */
	public static void onWorldJoin(Minecraft mc) {
		resetFields();
		scope = currentScope(mc);
		ResumeState saved = read();
		if (saved == null || saved.soundId == null || saved.ms < 0) {
			return;
		}
		// 不同存档不续播（多存档/换服场景）；scope 读不到（空串）时按「同世界」处理，宁可续也别丢
		if (!scope.isEmpty() && !saved.scope.isEmpty() && !scope.equals(saved.scope)) {
			LOGGER.info("[MusicResume] 存档不同（{} ≠ {}），不续播 {}", saved.scope, scope, saved.soundId);
			return;
		}
		LOGGER.info("[MusicResume] 续播 {} @{}ms speed={} vol={}（退出存档时记下的进度）",
				saved.soundId, saved.ms, saved.speed, saved.volume);
		RhythmAxeMusic.play(saved.soundId, saved.ms, saved.speed, saved.volume);
	}

	/**
	 * 退出世界（{@code Minecraft.setLevel(null)} 的 HEAD 调用，此刻音频还在播、世界标识也还在）
	 * 与客户端关闭时调用：把当前进度落盘。
	 */
	public static void save() {
		if (soundId == null) {
			// 本会话没有在播的音乐 → 不留记录（也顺手清掉上一份，避免重进莫名出声）
			deleteFile();
			return;
		}
		int pos = RhythmAxeMusic.audibleMs();   // 仍在播：取出此刻真正听到的位置（比上一刻更准）
		if (pos >= 0) {
			ms = pos;
		}
		write();
	}

	// ==================== 每客户端刻 ====================

	/**
	 * 每客户端刻（只许挂在 ClientTickEvents）：跟着音频刷新进度，播放中定期落盘。
	 *
	 * <p>不在世界里时**直接返回**：退出世界时 mod 会自动停音乐（{@code RhythmAxeMusic.tick()}），
	 * 那是「卸载世界」而不是「用户停播」，不能当成作废信号（否则刚写的续播记录会被自己删掉）。
	 */
	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			return;
		}
		boolean now = RhythmAxeMusic.isPlaying();
		if (now) {
			int pos = RhythmAxeMusic.audibleMs();
			if (pos >= 0) {
				soundId = RhythmAxeMusic.currentSoundId();
				ms = pos;
				speed = RhythmAxeMusic.currentSpeed();
				volume = RhythmAxeMusic.currentVolume();
			}
			if (++flushTimer >= FLUSH_TICKS) {
				flushTimer = 0;
				write();
			}
		} else if (wasPlaying) {
			// 播完 / 被 pausemusic、stopmusic 停掉 → 进度作废（重进不该自动出声）
			clear();
		}
		wasPlaying = now;
	}

	// ==================== 内部 ====================

	/** 作废当前进度（含磁盘记录）。 */
	private static void clear() {
		resetFields();
		deleteFile();
	}

	private static void resetFields() {
		soundId = null;
		ms = -1;
		speed = 1f;
		volume = 1f;
		wasPlaying = false;
		flushTimer = 0;
	}

	/** 当前世界标识：单人 = 存档目录名（".zip"/备份复制也能区分），多人 = 服务器地址。 */
	private static String currentScope(Minecraft mc) {
		try {
			if (mc.hasSingleplayerServer()) {
				MinecraftServer server = mc.getSingleplayerServer();
				if (server != null) {
					Path root = server.getWorldPath(LevelResource.ROOT);
					Path name = root == null ? null : root.getFileName();
					if (name != null) {
						return "sp:" + name;
					}
				}
			}
			ServerData server = mc.getCurrentServer();
			if (server != null && server.ip != null) {
				return "mp:" + server.ip;
			}
		} catch (Exception e) {
			LOGGER.warn("[MusicResume] 读取世界标识失败: {}", e.toString());
		}
		return "";
	}

	private static void write() {
		if (soundId == null || ms < 0) {
			deleteFile();
			return;
		}
		String text = "# rhythm_axe_mod 音乐续播（退出存档时写入，重进同存档时读回；删掉本文件即关闭该功能）\n"
				+ "scope=" + scope + "\n"
				+ "sound=" + soundId + "\n"
				+ "ms=" + ms + "\n"
				+ "speed=" + speed + "\n"
				+ "volume=" + volume + "\n";
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("[MusicResume] 写入失败: {}", e.toString());
		}
	}

	private static ResumeState read() {
		if (!Files.isRegularFile(FILE)) {
			return null;
		}
		try {
			ResumeState state = new ResumeState();
			for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
				String s = line.trim();
				if (s.isEmpty() || s.startsWith("#")) {
					continue;
				}
				int eq = s.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String key = s.substring(0, eq).trim();
				String val = s.substring(eq + 1).trim();
				switch (key) {
					case "scope" -> state.scope = val;
					case "sound" -> state.soundId = val;
					case "ms" -> state.ms = Integer.parseInt(val);
					case "speed" -> state.speed = Float.parseFloat(val);
					case "volume" -> state.volume = Float.parseFloat(val);
					default -> { }
				}
			}
			return state;
		} catch (IOException | NumberFormatException e) {
			LOGGER.warn("[MusicResume] 读取失败，忽略续播: {}", e.toString());
			return null;
		}
	}

	private static void deleteFile() {
		try {
			Files.deleteIfExists(FILE);
		} catch (IOException e) {
			LOGGER.warn("[MusicResume] 删除状态文件失败: {}", e.toString());
		}
	}

	/** 磁盘上的续播记录。 */
	private static final class ResumeState {
		String scope = "";
		String soundId;
		int ms = -1;
		float speed = 1f;
		float volume = 1f;
	}
}
