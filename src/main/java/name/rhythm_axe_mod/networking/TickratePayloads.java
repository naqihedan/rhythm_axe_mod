package name.rhythm_axe_mod.networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * tick rate 相关的服务端→客户端网络包。
 *
 * <p>为什么要自己发：原版 {@code ClientboundTickingStatePacket} 只让客户端**变慢**
 * （{@code Minecraft.getTickTargetMillis = max(50ms, mspt)}，客户端不会超过 20tps）。
 * 本 mod 的「加速」（&gt;20tps）是靠客户端 {@code DeltaTrackerTimerMixin} 读
 * {@link name.rhythm_axe_mod.TickrateState} 实现的，而这个状态量原先只在服务端命令里设置
 * ⇒ **单人（集成服务端与客户端同进程）有效，多人只有房主生效**，其他玩家仍是 20tps。
 * 因此服务端改速率时必须把「客户端应使用的速率」推给每个玩家（见 {@link name.rhythm_axe_mod.TickrateSync}）。
 */
public final class TickratePayloads {
	private TickratePayloads() {
	}

	/**
	 * 下发客户端应使用的 tick rate。
	 *
	 * <p>clientRate = 已按 {@code sync} 参数解析好的值：同步时 = 服务端速率；{@code false} 时 = 20（客户端不同步）。
	 * 低于 20 的值（慢放）会被客户端钳到 20 —— 变慢交给原版的 ticking state 包处理。
	 */
	public record TickratePayload(float clientRate) implements CustomPacketPayload {
		public static final Type<TickratePayload> TYPE = new Type<>(Identifier.parse("rhythm_axe_mod:tickrate"));
		public static final StreamCodec<ByteBuf, TickratePayload> STREAM_CODEC = StreamCodec.composite(
				ByteBufCodecs.FLOAT, TickratePayload::clientRate,
				TickratePayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
