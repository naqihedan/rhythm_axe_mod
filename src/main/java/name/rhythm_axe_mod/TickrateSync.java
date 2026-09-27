package name.rhythm_axe_mod;

import name.rhythm_axe_mod.networking.TickratePayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 客户端 tick rate 的服务端同步（多人用）。
 *
 * <p>单人 / 集成服务端：服务端与客户端在同一个进程里，命令处理里直接改 {@link TickrateState} 就生效。
 * <p>多人（LAN 里的其他玩家、专用服务器）：客户端是独立进程，必须把速率发过去 —— 否则只有房主
 * （= 集成服务端所在进程）的客户端会变速，别人还是 20tps。
 *
 * <p>原版的 {@code ClientboundTickingStatePacket} 帮不上忙：它只让客户端**变慢**
 * （客户端 {@code getTickTargetMillis} 取 {@code max(50ms, mspt)}，永远 ≤20tps），
 * 加速完全依赖本 mod 客户端侧的 {@code DeltaTrackerTimerMixin}。
 */
public final class TickrateSync {
	/** 最近一次下发的客户端速率（新玩家加入时补发；客户端 join 世界时会把速率重置为 20）。 */
	private static volatile float clientRate = 20.0f;

	private TickrateSync() {
	}

	/**
	 * 广播「客户端应使用的速率」给所有在线玩家（含房主自己，幂等）。
	 *
	 * @param clientRate 已按 sync 参数解析好的值；&lt;20 会被钳到 20（客户端 TickrateState 的语义，
	 *                   变慢由原版的 ticking state 包负责）
	 */
	public static void broadcast(MinecraftServer server, float clientRate) {
		float rate = Math.max(clientRate, 20.0f);
		TickrateSync.clientRate = rate;
		if (server == null) {
			return;
		}
		TickratePayloads.TickratePayload payload = new TickratePayloads.TickratePayload(rate);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/**
	 * 玩家加入时补发当前速率。
	 *
	 * <p>必须补：客户端进世界时 {@code MinecraftClientMixin} 会把 TickrateState 重置为 20，
	 * 而速率变化是「事件」不是「每刻状态」—— 中途进服/重连的玩家否则永远拿不到当前速率。
	 */
	public static void registerJoinSync() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				ServerPlayNetworking.send(handler.player,
						new TickratePayloads.TickratePayload(clientRate)));
	}
}
