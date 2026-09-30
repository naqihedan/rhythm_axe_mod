package name.rhythm_axe_mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 存档内 tick rate 持久化（原版 {@code /tick rate} 不落盘）。
 *
 * <p>现状：退出存档后速率丢失，重进必然回到原版默认 20tps。
 * 本类在存档目录里记一个速率文件 ——
 * <ul>
 *   <li>退出存档（{@code SERVER_STOPPING}）：把当前速率写盘；</li>
 *   <li>重进存档（{@code SERVER_STARTED}）：读回并恢复；文件缺失 / 内容非法 → 维持 20tps 兜底。</li>
 * </ul>
 *
 * <p>只记「速率」，不记「冻结 / 冲刺」：后两者是临时状态，不该跨存档粘住。
 */
public final class TickratePersistence {
	/** 速率文件（相对存档根目录的路径；随存档一起复制 / 备份）。 */
	private static final String RATE_FILE = "rhythm_axe_mod/tickrate.txt";
	/** 原版默认速率，也是读不到有效记录时的兜底值。 */
	private static final float DEFAULT_RATE = 20.0f;
	/** 合法区间（与 /tick rate 校验一致：1~1000 tps）。 */
	private static final float MIN_RATE = 1.0f;
	private static final float MAX_RATE = 1000.0f;

	private TickratePersistence() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(TickratePersistence::restore);
		ServerLifecycleEvents.SERVER_STOPPING.register(TickratePersistence::save);
	}

	/** 重进存档：读回上次退出时的速率并应用（无记录 = 不动作，服务端本就是原版 20tps）。 */
	private static void restore(MinecraftServer server) {
		float rate = readRate(server);
		if (rate != DEFAULT_RATE) {
			server.tickRateManager().setTickRate(rate);
			RhythmAxeMod.LOGGER.info("[TickRate] 已从存档恢复速率 {}tps", rate);
		}
		// 同步「客户端待发速率」：此刻还没有玩家，只是把状态记下 —— 玩家加入时由 JOIN 补发。
		// 必须无条件调用（哪怕 rate == 20），否则上一张存档留下的静态值会漏到这张存档。
		TickrateSync.broadcast(server, rate);
	}

	/** 退出存档：把当前速率写进存档。 */
	private static void save(MinecraftServer server) {
		float rate = server.tickRateManager().tickrate();
		if (rate <= 0f) {
			return;
		}
		Path file = rateFile(server);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, Float.toString(rate), StandardCharsets.UTF_8);
			RhythmAxeMod.LOGGER.info("[TickRate] 已记录速率 {}tps 到存档", rate);
		} catch (IOException e) {
			RhythmAxeMod.LOGGER.warn("[TickRate] 速率写入失败: {}", e.toString());
		}
	}

	/** 读存档里的速率；缺失 / 非法一律回落 {@link #DEFAULT_RATE}。 */
	private static float readRate(MinecraftServer server) {
		Path file = rateFile(server);
		if (!Files.isRegularFile(file)) {
			return DEFAULT_RATE;
		}
		try {
			float rate = Float.parseFloat(Files.readString(file, StandardCharsets.UTF_8).trim());
			if (rate < MIN_RATE || rate > MAX_RATE) {
				RhythmAxeMod.LOGGER.warn("[TickRate] 存档里的速率 {} 超出 {}~{}，改用默认 {}tps",
						rate, MIN_RATE, MAX_RATE, DEFAULT_RATE);
				return DEFAULT_RATE;
			}
			return rate;
		} catch (IOException | NumberFormatException e) {
			RhythmAxeMod.LOGGER.warn("[TickRate] 速率读取失败，改用默认 {}tps: {}", DEFAULT_RATE, e.toString());
			return DEFAULT_RATE;
		}
	}

	private static Path rateFile(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(RATE_FILE);
	}
}
